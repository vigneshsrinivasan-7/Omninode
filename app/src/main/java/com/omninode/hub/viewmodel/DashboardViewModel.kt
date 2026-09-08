package com.omninode.hub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omninode.hub.ai.LiteRtManager
import com.omninode.hub.data.model.*
import com.omninode.hub.network.HomeAssistantWsClient
import com.omninode.hub.network.MatterController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val matterController: MatterController,
    private val haWsClient: HomeAssistantWsClient,
    private val liteRtManager: LiteRtManager,
) : ViewModel() {

    private val _structure = MutableStateFlow<SmartStructure?>(null)
    val structure: StateFlow<SmartStructure?> = _structure.asStateFlow()

    private val _connectivityMode = MutableStateFlow(ConnectivityMode.FULL_WIFI)
    val connectivityMode: StateFlow<ConnectivityMode> = _connectivityMode.asStateFlow()

    private val _wsConnectionStatus = MutableStateFlow(false)
    val wsConnectionStatus: StateFlow<Boolean> = _wsConnectionStatus.asStateFlow()

    private val _npuDelegate = MutableStateFlow("QNN_NPU")
    val npuDelegate: StateFlow<String> = _npuDelegate.asStateFlow()

    init {
        loadStructure()
        observeWsStatus()
        observeNpuDelegate()
    }

    private fun loadStructure() {
        viewModelScope.launch {
            matterController.observeStructure().collect { structure ->
                _structure.value = structure
            }
        }
    }

    private fun observeWsStatus() {
        viewModelScope.launch {
            haWsClient.connectionStatus.collect { status ->
                _wsConnectionStatus.value = status is HomeAssistantWsClient.ConnectionStatus.Connected
            }
        }
    }

    private fun observeNpuDelegate() {
        viewModelScope.launch {
            _npuDelegate.value = liteRtManager.getActiveDelegate().name
        }
    }

    fun toggleDevice(device: SmartDevice, on: Boolean) {
        viewModelScope.launch {
            when (device.protocolSource) {
                ProtocolSource.MATTER -> {
                    try {
                        matterController.setOnOff(device.id, on)
                    } catch (e: Exception) {
                        Timber.e(e, "Matter toggle failed — falling back to WebSocket")
                        toggleViaWebSocket(device, on)
                    }
                }
                ProtocolSource.HOME_ASSISTANT -> toggleViaWebSocket(device, on)
                else -> Timber.w("No protocol handler for ${device.protocolSource}")
            }
            // Optimistic UI update
            updateDeviceState(device.id, on)
        }
    }

    private fun toggleViaWebSocket(device: SmartDevice, on: Boolean) {
        val (domain, service) = if (device.type == DeviceType.SMART_LOCK) {
            "lock" to if (on) "lock" else "unlock"
        } else {
            "light" to if (on) "turn_on" else "turn_off"
        }
        haWsClient.callService(
            domain      = domain,
            service     = service,
            serviceData = mapOf("entity_id" to "$domain.${device.id}"),
        )
    }

    private fun updateDeviceState(deviceId: String, on: Boolean) {
        _structure.value = _structure.value?.let { structure ->
            structure.copy(
                rooms = structure.rooms.map { room ->
                    room.copy(
                        devices = room.devices.map { dev ->
                            if (dev.id == deviceId) {
                                dev.copy(traits = dev.traits + (TraitType.ON_OFF to on))
                            } else dev
                        }
                    )
                }
            )
        }
    }

    fun triggerPreset(preset: AutomationPreset) {
        viewModelScope.launch {
            Timber.i("Triggering preset: ${preset.displayName}")
            when (preset) {
                AutomationPreset.READING_MODE -> {
                    haWsClient.callService("light", "turn_on", mapOf(
                        "entity_id" to "light.light_ceiling_01",
                        "brightness_pct" to 40,
                        "color_temp_kelvin" to 4000,
                    ))
                }
                AutomationPreset.DEEP_FOCUS -> {
                    haWsClient.callService("light", "turn_on", mapOf(
                        "entity_id" to "light.light_ceiling_01",
                        "color_temp_kelvin" to 5000,
                        "brightness_pct" to 70,
                    ))
                    haWsClient.callService("media_player", "volume_set", mapOf(
                        "entity_id" to "media_player.living_room",
                        "volume_level" to 0.3,
                    ))
                }
                AutomationPreset.CINEMA_MODE -> {
                    haWsClient.callService("light", "turn_off", mapOf(
                        "entity_id" to "light.light_ceiling_01",
                    ))
                    haWsClient.callService("switch", "turn_on", mapOf(
                        "entity_id" to "switch.plug_tv_01",
                    ))
                }
                AutomationPreset.GOOD_MORNING -> {
                    haWsClient.callService("light", "turn_on", mapOf(
                        "entity_id" to "light.light_ceiling_01",
                        "brightness_pct" to 60,
                        "color_temp_kelvin" to 3000,
                    ))
                }
                AutomationPreset.AWAY_MODE -> {
                    haWsClient.callService("homeassistant", "turn_off", mapOf(
                        "entity_id" to "all",
                    ))
                }
                else -> Timber.w("Preset ${preset.name} not handled")
            }
        }
    }
}
