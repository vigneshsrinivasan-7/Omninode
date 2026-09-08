package com.omninode.hub.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omninode.hub.ai.FastVlmEngine
import com.omninode.hub.ai.LiteRtManager
import com.omninode.hub.data.model.SceneAnalysis
import com.omninode.hub.data.model.SmartDevice
import com.omninode.hub.data.model.SmartStructure
import com.omninode.hub.data.model.TraitType
import com.omninode.hub.network.HomeAssistantWsClient
import com.omninode.hub.network.MatterController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class VisionViewModel @Inject constructor(
    private val fastVlmEngine: FastVlmEngine,
    private val haWsClient: HomeAssistantWsClient,
) : ViewModel() {

    private val _sceneAnalysis = MutableStateFlow<SceneAnalysis?>(null)
    val sceneAnalysis: StateFlow<SceneAnalysis?> = _sceneAnalysis.asStateFlow()

    private val _isAnalysing = MutableStateFlow(false)
    val isAnalysing: StateFlow<Boolean> = _isAnalysing.asStateFlow()

    private val _lastInferenceMs = MutableStateFlow(0L)
    val lastInferenceMs: StateFlow<Long> = _lastInferenceMs.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    fun captureAndAnalyse() {
        viewModelScope.launch {
            if (_isAnalysing.value) return@launch
            _isAnalysing.value = true

            try {
                // In production: inject ImageCapture from CameraX and capture real Bitmap
                // For demo: use a synthetic bitmap
                val syntheticBitmap = createTestBitmap()
                val analysis = fastVlmEngine.analyzeScene(
                    bitmap        = syntheticBitmap,
                    deviceContext = "light_ceiling_01, plug_tv_01",
                )
                _sceneAnalysis.value  = analysis
                _lastInferenceMs.value = analysis.inferenceTimeMs
                Timber.i("FastVLM scene analysis complete: ${analysis.description}")
            } catch (e: Exception) {
                Timber.e(e, "Scene analysis failed")
            } finally {
                _isAnalysing.value = false
            }
        }
    }

    fun toggleLiveMode() {
        viewModelScope.launch {
            _isCapturing.value = !_isCapturing.value
            if (_isCapturing.value) {
                // Simulate continuous live analysis every 2s
                while (_isCapturing.value) {
                    captureAndAnalyse()
                    delay(2_000)
                }
            }
        }
    }

    fun applyCommand(deviceId: String) {
        val suggestion = _sceneAnalysis.value?.suggestedAutomations
            ?.firstOrNull { it.deviceId == deviceId } ?: return

        val (domain, service) = when (suggestion.trait) {
            "OnOff"            -> "light" to if (suggestion.value == true) "turn_on" else "turn_off"
            "Brightness"       -> "light" to "turn_on"
            "ColorTemperature" -> "light" to "turn_on"
            else               -> return
        }
        val data = mutableMapOf<String, Any>("entity_id" to "light.$deviceId")
        when (suggestion.trait) {
            "Brightness"       -> data["brightness_pct"] = suggestion.value
            "ColorTemperature" -> data["color_temp_kelvin"] = suggestion.value
        }
        haWsClient.callService(domain, service, data)
        Timber.i("VisionVM: applied command ${suggestion.trait}=${suggestion.value} on $deviceId")
    }

    private fun createTestBitmap(): Bitmap {
        return Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.rgb(200, 190, 160))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  DevicesViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val matterController: MatterController,
    private val haWsClient: HomeAssistantWsClient,
) : ViewModel() {

    private val _structure = MutableStateFlow<SmartStructure?>(null)
    val structure: StateFlow<SmartStructure?> = _structure.asStateFlow()

    init { refreshDevices() }

    fun refreshDevices() {
        viewModelScope.launch {
            matterController.observeStructure().collect { _structure.value = it }
        }
    }

    fun toggleDevice(device: SmartDevice, on: Boolean) {
        viewModelScope.launch {
            try {
                matterController.setOnOff(device.id, on)
            } catch (e: Exception) {
                haWsClient.callService("light", if (on) "turn_on" else "turn_off", mapOf("entity_id" to "light.${device.id}"))
            }
        }
    }

    fun startCommissioning() {
        Timber.i("Matter commissioning requested")
        // Launch CommissioningActivity via registered ActivityResultLauncher in the calling composable
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  SettingsViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val haWsClient: HomeAssistantWsClient,
    private val liteRtManager: LiteRtManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val prefs = context.getSharedPreferences("omninode_config", android.content.Context.MODE_PRIVATE)

    private val _haUrl      = MutableStateFlow(prefs.getString("ha_ws_url", HomeAssistantWsClient.DEFAULT_WS_URL) ?: HomeAssistantWsClient.DEFAULT_WS_URL)
    val haUrl: StateFlow<String> = _haUrl.asStateFlow()

    private val _haToken    = MutableStateFlow(prefs.getString("ha_token", "") ?: "")
    val haToken: StateFlow<String> = _haToken.asStateFlow()

    private val _picovoiceKey = MutableStateFlow(prefs.getString("picovoice_key", "") ?: "")
    val picovoiceKey: StateFlow<String> = _picovoiceKey.asStateFlow()

    private val _npuEnabled = MutableStateFlow(true)
    val npuEnabled: StateFlow<Boolean> = _npuEnabled.asStateFlow()

    private val _officeKitEnabled = MutableStateFlow(true)
    val officeKitEnabled: StateFlow<Boolean> = _officeKitEnabled.asStateFlow()

    private val _activeDelegate = MutableStateFlow(liteRtManager.getActiveDelegate().name)
    val activeDelegate: StateFlow<String> = _activeDelegate.asStateFlow()

    fun setHaUrl(url: String)      { _haUrl.value = url }
    fun setHaToken(token: String)  { _haToken.value = token }
    fun setPicovoiceKey(key: String) { _picovoiceKey.value = key }
    fun setNpuEnabled(e: Boolean)  { _npuEnabled.value = e }
    fun setOfficeKitEnabled(e: Boolean) { _officeKitEnabled.value = e }

    fun saveAndReconnect() {
        prefs.edit()
            .putString("ha_ws_url", _haUrl.value)
            .putString("ha_token", _haToken.value)
            .putString("picovoice_key", _picovoiceKey.value)
            .apply()

        viewModelScope.launch {
            haWsClient.disconnect()
            haWsClient.connect(_haUrl.value, _haToken.value)
            _activeDelegate.value = liteRtManager.getActiveDelegate().name
            Timber.i("HA reconnected to ${_haUrl.value}")
        }
    }
}
