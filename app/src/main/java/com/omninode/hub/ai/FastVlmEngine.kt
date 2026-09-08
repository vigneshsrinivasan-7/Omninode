package com.omninode.hub.ai

import android.graphics.Bitmap
import com.omninode.hub.data.model.AgentCommand
import com.omninode.hub.data.model.SceneAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FastVlmEngine — On-device FastVLM 0.5B Vision-Language Model engine.
 *
 * ═════════════════════════════════════════════════════════════════════════════
 *  Model Specs (Snapdragon 8 Elite Gen 5):
 *    • Parameters   : 0.5 billion
 *    • Input        : 1024 × 1024 RGB image frames from CameraX
 *    • TTFT         : 0.12 seconds (Time-to-First-Token)
 *    • Prefill speed: 11,000+ tokens/sec (Hexagon NPU, quantized INT8)
 *    • Decode speed : 100+ tokens/sec
 *    • Quantization : INT8 weights via QNN delegate (zero-copy NPU access)
 *
 *  Visual pipeline:
 *    CameraX ImageAnalysis → Bitmap → FastVLM → Scene JSON → GemmaEngine
 *
 *  Privacy guarantee:
 *    All image processing is local-only — no pixel data leaves the device.
 *
 *  Model file: fastvlm_05b_sm8850.litertlm
 *    • Pre-compiled for SM8850 via Qualcomm AI Hub Workbench
 *    • Qualcomm Spectra triple 20-bit AI-ISPs provide pre-processed frames
 * ═════════════════════════════════════════════════════════════════════════════
 */
@Singleton
class FastVlmEngine @Inject constructor(
    private val liteRtManager: LiteRtManager,
) {
    // ── Vision prompt template ────────────────────────────────────────────────
    private val visionPrompt = """
        Analyze this smart home room image. Output ONLY a JSON object:
        {
          "description": "<1–2 sentence scene summary>",
          "detected_objects": ["<object1>", "<object2>"],
          "device_states": {
            "<device_name>": "<observed state>"
          },
          "suggested_automations": [
            {
              "device_id": "<id>",
              "trait": "<trait>",
              "value": <value>,
              "confidence": <0.0–1.0>,
              "reasoning": "<reason>"
            }
          ]
        }
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Processes a camera frame and returns a [SceneAnalysis] describing the
     * room state and suggested IoT automations.
     *
     * This is the core function driving OmniNode's autonomous "camera → action"
     * demonstration capability.
     *
     * @param bitmap       Camera frame (ideally 1024×1024, pre-processed by Spectra ISP)
     * @param deviceContext JSON of available devices for the model to reference
     */
    suspend fun analyzeScene(
        bitmap: Bitmap,
        deviceContext: String = "",
    ): SceneAnalysis = withContext(Dispatchers.Default) {
        if (!liteRtManager.isReady()) {
            Timber.w("FastVlmEngine: LiteRT not initialised")
            return@withContext errorSceneAnalysis("NPU runtime not ready")
        }

        val startMs = System.currentTimeMillis()
        Timber.d("FastVLM: starting scene analysis — bitmap=${bitmap.width}×${bitmap.height}")

        // ── TODO: Replace with real LiteRT MultiModal inference ───────────────
        // val inputTensor = preprocessBitmap(bitmap)  // Normalize to [-1, 1], resize to 1024x1024
        // val textTokens  = tokenize(visionPrompt + "\n" + deviceContext)
        //
        // interpreter.run(
        //     inputs  = mapOf("image" to inputTensor, "text" to textTokens),
        //     outputs = mapOf("logits" to outputBuffer)
        // )
        // val rawJson = decode(outputBuffer)
        // ──────────────────────────────────────────────────────────────────────

        // Simulated inference (replaced by real LiteRT call in Phase 3)
        delay(120) // Simulate 0.12 s TTFT (FastVLM spec on SM8850)
        val rawJson = simulateSceneAnalysis(bitmap)

        val inferenceMs = System.currentTimeMillis() - startMs
        Timber.i("FastVLM: scene analysis completed in ${inferenceMs}ms")

        parseSceneAnalysis(rawJson, inferenceMs)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Preprocessing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scales a bitmap to 1024×1024 and normalizes pixel values to [-1.0, 1.0].
     * The Qualcomm Spectra ISP provides already-optimized frames when using
     * the high-res CameraX stream, minimizing preprocessing overhead.
     */
    private fun preprocessBitmap(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 1024, 1024, true)
        val pixels = IntArray(1024 * 1024)
        scaled.getPixels(pixels, 0, 1024, 0, 0, 1024, 1024)

        val floatBuffer = FloatArray(1024 * 1024 * 3)
        pixels.forEachIndexed { index, pixel ->
            val r = ((pixel shr 16) and 0xFF) / 127.5f - 1.0f
            val g = ((pixel shr  8) and 0xFF) / 127.5f - 1.0f
            val b = ((pixel       ) and 0xFF) / 127.5f - 1.0f
            floatBuffer[index * 3]     = r
            floatBuffer[index * 3 + 1] = g
            floatBuffer[index * 3 + 2] = b
        }
        return floatBuffer
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  JSON parsing
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseSceneAnalysis(rawJson: String, inferenceMs: Long): SceneAnalysis {
        return try {
            // Simplified parse — in production use Moshi adapter
            SceneAnalysis(
                description          = extractJsonString(rawJson, "description"),
                detectedObjects      = extractJsonStringArray(rawJson, "detected_objects"),
                suggestedAutomations = emptyList(), // Parsed by GemmaEngine command parser
                inferenceTimeMs      = inferenceMs,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse FastVLM scene analysis JSON")
            errorSceneAnalysis("JSON parse failure: ${e.message}")
        }
    }

    private fun extractJsonString(json: String, key: String): String {
        val pattern = Regex(""""$key"\s*:\s*"([^"]+)"""")
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun extractJsonStringArray(json: String, key: String): List<String> {
        val pattern = Regex(""""$key"\s*:\s*\[([^\]]+)\]""")
        val arrayStr = pattern.find(json)?.groupValues?.get(1) ?: return emptyList()
        return arrayStr.split(",").map { it.trim().removeSurrounding("\"") }
    }

    private fun errorSceneAnalysis(reason: String) = SceneAnalysis(
        description          = "Scene analysis unavailable: $reason",
        detectedObjects      = emptyList(),
        suggestedAutomations = emptyList(),
        inferenceTimeMs      = 0L,
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Simulation (scaffold placeholder)
    // ─────────────────────────────────────────────────────────────────────────

    private fun simulateSceneAnalysis(bitmap: Bitmap): String {
        // Vary the simulated output based on image brightness (trivial heuristic for demo)
        val avgBrightness = estimateBrightness(bitmap)
        return if (avgBrightness > 128) {
            """
            {
              "description": "Well-lit living room. Overhead lights are active at high brightness. A person is seated on the sofa reading a physical book.",
              "detected_objects": ["sofa", "person", "book", "ceiling_light", "television"],
              "device_states": {"ceiling_light": "on, high brightness", "television": "off"},
              "suggested_automations": [
                {"device_id": "light_ceiling_01", "trait": "Brightness", "value": 40, "confidence": 0.94, "reasoning": "Person is reading — reduce glare"},
                {"device_id": "light_ceiling_01", "trait": "ColorTemperature", "value": 4000, "confidence": 0.91, "reasoning": "Warm white reduces eye strain for reading"}
              ]
            }
            """.trimIndent()
        } else {
            """
            {
              "description": "Dim room detected. Low ambient light. No occupants visible. Television is off.",
              "detected_objects": ["sofa", "television", "floor_lamp"],
              "device_states": {"ceiling_light": "off or low brightness", "television": "off"},
              "suggested_automations": [
                {"device_id": "light_ceiling_01", "trait": "OnOff", "value": false, "confidence": 0.89, "reasoning": "Room appears unoccupied — conserve energy"}
              ]
            }
            """.trimIndent()
        }
    }

    private fun estimateBrightness(bitmap: Bitmap): Float {
        val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        var total = 0L
        for (x in 0 until 32) {
            for (y in 0 until 32) {
                val pixel = scaled.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                total += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
            }
        }
        return total / (32 * 32).toFloat()
    }
}
