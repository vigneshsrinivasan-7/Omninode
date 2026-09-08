package com.omninode.hub.ai

import android.content.Context
import com.omninode.hub.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRtManager — Manages the LiteRT (successor to TensorFlow Lite) runtime
 * and the Qualcomm QNN delegate for Hexagon NPU inference.
 *
 * ═════════════════════════════════════════════════════════════════════════════
 *  Execution Priority Chain (Snapdragon 8 Elite Gen 5 / SM8850):
 *    1. QNN Delegate   → Hexagon NPU   (INT8/INT16 quantized AOT .litertlm)
 *    2. GPU Delegate   → Adreno 840    (FP16 via OpenCL, JIT compiled)
 *    3. XNNPack        → Oryon CPU     (Fallback — highest power, lowest throughput)
 *
 *  AOT (.litertlm) model compilation:
 *    • Compiled offline via Qualcomm AI Hub Workbench (qai_hub Python package).
 *    • Targets SM8850 device signature to lock into Hexagon Tensor Accelerator.
 *    • Pushed to device via ADB before demonstration:
 *        adb push gemma4_2b_sm8850.litertlm /data/local/tmp/
 *
 *  Native libraries required (bundled in app/libs/ from QAIRT SDK):
 *    • libQnnHtp.so          — Hexagon Tensor Processor runtime
 *    • libQnnHtpV68Stub.so   — SM8850 stub for HTP v6.8
 *    • libQnnSystem.so       — QNN system library
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * NOTE: Full LiteRT + QNN delegate initialisation requires the .aar from
 * the Qualcomm AI Engine Direct SDK (QAIRT). The code below is structured
 * to compile cleanly as an architectural scaffold with clear TODOs marking
 * each integration point. Replace stub calls with real LiteRT API calls
 * once the SDK .aar is placed in app/libs/.
 */
@Singleton
class LiteRtManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // ── Runtime state ─────────────────────────────────────────────────────────
    private var isInitialised: Boolean = false
    private var activeDelegate: InferenceDelegate = InferenceDelegate.NONE

    // ── Model file paths (written by ADB push or in-app download) ─────────────
    companion object {
        const val MODEL_DIR    = "models"
        const val GEMMA_MODEL  = "gemma4_2b_sm8850.litertlm"
        const val FASTVLM_MODEL = "fastvlm_05b_sm8850.litertlm"

        // QNN native library names (must match JNI entries in the .aar)
        private const val LIB_QNN_HTP        = "QnnHtp"
        private const val LIB_QNN_HTP_STUB   = "QnnHtpV68Stub"
        private const val LIB_QNN_SYSTEM     = "QnnSystem"
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initialises the LiteRT runtime and selects the optimal delegate.
     * Must be called from a background coroutine (IO dispatcher).
     *
     * Avoids JIT compilation by requiring pre-compiled .litertlm models
     * to prevent 30–120 s startup delays and OOM failures on the device.
     */
    suspend fun initialise(): InitResult = withContext(Dispatchers.IO) {
        if (isInitialised) return@withContext InitResult.AlreadyInitialised

        Timber.i("LiteRtManager: initialising — NPU_ENABLED=${BuildConfig.ENABLE_NPU}")

        // Step 1: Load QNN native libraries
        if (BuildConfig.ENABLE_NPU) {
            val nativeLoadResult = loadQnnNativeLibraries()
            if (nativeLoadResult is NativeLoadResult.Failure) {
                Timber.w("QNN native libraries failed to load: ${nativeLoadResult.reason}")
                Timber.w("Falling back to GPU delegate (Adreno OpenCL)")
            }
        }

        // Step 2: Locate pre-compiled model files
        val modelDir = File(context.filesDir, MODEL_DIR)
        val gemmaModel   = File(modelDir, GEMMA_MODEL)
        val fastVlmModel = File(modelDir, FASTVLM_MODEL)

        Timber.d("Gemma model exists: ${gemmaModel.exists()} [${gemmaModel.absolutePath}]")
        Timber.d("FastVLM model exists: ${fastVlmModel.exists()} [${fastVlmModel.absolutePath}]")

        // Step 3: Select delegate and initialise interpreter
        activeDelegate = selectDelegate()
        Timber.i("LiteRT delegate selected: $activeDelegate")

        // ── TODO: Replace with real LiteRT API initialisation ──────────────────
        // val options = Interpreter.Options().apply {
        //     when (activeDelegate) {
        //         InferenceDelegate.QNN_NPU -> {
        //             val qnnDelegate = QnnDelegate(
        //                 QnnDelegate.Options().apply {
        //                     setBackendType(QnnDelegate.Options.BackendType.HTP)
        //                     setSkelLibraryDir(context.applicationInfo.nativeLibraryDir)
        //                 }
        //             )
        //             addDelegate(qnnDelegate)
        //         }
        //         InferenceDelegate.GPU -> {
        //             val gpuDelegate = GpuDelegate(
        //                 GpuDelegate.Options().apply {
        //                     setPrecisionLossAllowed(true)
        //                 }
        //             )
        //             addDelegate(gpuDelegate)
        //         }
        //         InferenceDelegate.CPU_XNNPACK -> {
        //             setUseXNNPACK(true)
        //             setNumThreads(Runtime.getRuntime().availableProcessors())
        //         }
        //         else -> {}
        //     }
        // }
        // gemmaInterpreter = Interpreter(MappedByteBuffer(gemmaModel), options)
        // ──────────────────────────────────────────────────────────────────────

        isInitialised = true
        Timber.i("LiteRtManager initialised successfully with delegate=$activeDelegate")
        InitResult.Success(delegate = activeDelegate)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Delegate selection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Selects the optimal inference delegate based on hardware availability.
     * Priority: QNN NPU → GPU (Adreno OpenCL) → CPU (XNNPack)
     */
    private fun selectDelegate(): InferenceDelegate {
        if (!BuildConfig.ENABLE_NPU) return InferenceDelegate.CPU_XNNPACK

        return try {
            // Check if QNN HTP backend is available on this device
            val htpLibFile = File(context.applicationInfo.nativeLibraryDir, "libQnnHtp.so")
            if (htpLibFile.exists()) {
                InferenceDelegate.QNN_NPU
            } else {
                Timber.w("libQnnHtp.so not found in nativeLibraryDir — falling back to GPU")
                InferenceDelegate.GPU_ADRENO
            }
        } catch (e: Exception) {
            Timber.e(e, "Delegate selection failed — using CPU fallback")
            InferenceDelegate.CPU_XNNPACK
        }
    }

    /**
     * Loads the Qualcomm QNN native shared libraries required for NPU inference.
     *
     * Libraries are pre-packaged in the .aar from the QAIRT SDK and extracted
     * to [applicationInfo.nativeLibraryDir] by the Android package manager.
     */
    private fun loadQnnNativeLibraries(): NativeLoadResult {
        return try {
            System.loadLibrary(LIB_QNN_HTP)
            System.loadLibrary(LIB_QNN_HTP_STUB)
            System.loadLibrary(LIB_QNN_SYSTEM)
            Timber.d("QNN native libraries loaded successfully")
            NativeLoadResult.Success
        } catch (e: UnsatisfiedLinkError) {
            NativeLoadResult.Failure("Library not found: ${e.message}")
        } catch (e: SecurityException) {
            NativeLoadResult.Failure("Security restriction: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Accessors
    // ─────────────────────────────────────────────────────────────────────────

    fun isReady(): Boolean = isInitialised
    fun getActiveDelegate(): InferenceDelegate = activeDelegate

    // ─────────────────────────────────────────────────────────────────────────
    //  Sealed result types
    // ─────────────────────────────────────────────────────────────────────────

    sealed class InitResult {
        data class  Success(val delegate: InferenceDelegate) : InitResult()
        data object AlreadyInitialised : InitResult()
        data class  Failure(val reason: String, val cause: Throwable? = null) : InitResult()
    }

    sealed class NativeLoadResult {
        data object Success : NativeLoadResult()
        data class  Failure(val reason: String) : NativeLoadResult()
    }

    enum class InferenceDelegate {
        NONE,
        QNN_NPU,       // Hexagon NPU via Qualcomm QNN delegate (primary)
        GPU_ADRENO,    // Adreno 840 via OpenCL GPU delegate (secondary)
        CPU_XNNPACK,   // Oryon CPU via XNNPack (last resort)
    }
}
