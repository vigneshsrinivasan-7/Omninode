package com.omninode.hub.network

import com.omninode.hub.data.model.HaStateChangedEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualDeviceRegistry — Local in-memory smart-home device state store.
 *
 * Activated automatically by [HomeAssistantWsClient] when the real Home Assistant
 * instance cannot be reached (UnknownHostException / ConnectException after
 * MAX_REAL_ATTEMPTS failures).
 *
 * Architecture:
 *  ┌────────────────────────────────────────────────────────────────────────┐
 *  │  OmniBackgroundService / OmniNodeForegroundService                    │
 *  │          ↕  callService() / toggle()                                  │
 *  │  HomeAssistantWsClient  ──(mock mode)──►  VirtualDeviceRegistry       │
 *  │          ↕  HaStateChangedEvent  (same flow as real mode)             │
 *  │  DashboardViewModel / AgentViewModel                                  │
 *  └────────────────────────────────────────────────────────────────────────┘
 *
 * Pre-seeded devices (matching typical OmniNode demo environment):
 *  • living_room_light  (OnOff / Brightness)
 *  • ceiling_fan_1      (OnOff / Speed 0–3)
 *  • air_conditioner    (OnOff / Temperature)
 */
@Singleton
class VirtualDeviceRegistry @Inject constructor() {

    // ── Internal mutable state ────────────────────────────────────────────────

    /** Full state map: entity_id → DeviceState */
    private val _devices = MutableStateFlow(buildDefaultDevices())
    val devices: StateFlow<Map<String, DeviceState>> = _devices.asStateFlow()

    /**
     * Fake state-changed events emitted whenever a device is toggled.
     * Observed by the same collector as the real HA WebSocket events.
     */
    private val _stateChanges = MutableSharedFlow<HaStateChangedEvent>(
        replay = 10,
        extraBufferCapacity = 64,
    )
    val stateChanges = _stateChanges.asSharedFlow()

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API — mirrors the subset of HA WebSocket API used by OmniNode
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the current [DeviceState] for [entityId], or null if unknown.
     */
    fun getState(entityId: String): DeviceState? = _devices.value[entityId]

    /**
     * Directly sets the state of a device and emits a [HaStateChangedEvent].
     * Thread-safe — MutableStateFlow.update() is atomic.
     */
    suspend fun setState(
        entityId: String,
        newState: String,
        attributes: Map<String, Any> = emptyMap(),
    ) {
        val updated = _devices.value[entityId]?.copy(
            state = newState,
            attributes = _devices.value[entityId]!!.attributes + attributes,
        ) ?: DeviceState(entityId = entityId, state = newState, attributes = attributes)

        _devices.value = _devices.value.toMutableMap().also { it[entityId] = updated }

        Timber.i("VirtualRegistry: $entityId → $newState (attrs=${attributes})")

        _stateChanges.emit(
            HaStateChangedEvent(
                entityId = entityId,
                newState = null, // Minimal event — same as real HA parser
            )
        )
    }

    /**
     * Toggles an OnOff device between "on" and "off".
     * Returns the new state string.
     */
    suspend fun toggle(entityId: String): String {
        val current = _devices.value[entityId]?.state ?: "off"
        val next = if (current == "on") "off" else "on"
        setState(entityId, next)
        return next
    }

    /**
     * Applies a HA-style service call (domain + service + serviceData) to the
     * virtual registry. Mirrors the [HomeAssistantWsClient.callService] signature
     * so the caller does not need to know whether it is talking to real HA or mock.
     *
     * Supported mappings:
     *  • light.turn_on / light.turn_off
     *  • fan.turn_on / fan.turn_off / fan.set_speed
     *  • climate.turn_on / climate.turn_off / climate.set_temperature
     *  • homeassistant.toggle
     */
    suspend fun handleServiceCall(
        domain: String,
        service: String,
        serviceData: Map<String, Any>,
    ) {
        val entityId = serviceData["entity_id"] as? String ?: return
        Timber.d("VirtualRegistry.handleServiceCall: $domain.$service on $entityId data=$serviceData")

        when ("$domain.$service") {
            "light.turn_on", "fan.turn_on", "climate.turn_on", "switch.turn_on" -> {
                val attrs = buildMap<String, Any> {
                    serviceData["brightness_pct"]?.let { put("brightness_pct", it) }
                    serviceData["color_temp_kelvin"]?.let { put("color_temp_kelvin", it) }
                    serviceData["speed"]?.let { put("speed", it) }
                    serviceData["temperature"]?.let { put("temperature", it) }
                }
                setState(entityId, "on", attrs)
            }

            "light.turn_off", "fan.turn_off", "climate.turn_off", "switch.turn_off" ->
                setState(entityId, "off")

            "lock.lock" ->
                setState(entityId, "locked")

            "lock.unlock" ->
                setState(entityId, "unlocked")

            "homeassistant.toggle" ->
                toggle(entityId)

            "fan.set_speed" -> {
                val speed = serviceData["speed"] as? String ?: "medium"
                setState(entityId, "on", mapOf("speed" to speed))
            }

            "climate.set_temperature" -> {
                val temp = serviceData["temperature"] ?: 24
                setState(entityId, "on", mapOf("temperature" to temp))
            }

            else -> Timber.w("VirtualRegistry: unsupported service '$domain.$service' — ignored")
        }
    }

    /**
     * Returns a human-readable summary of all device states (for TTS / chat display).
     */
    fun getSummary(): String =
        _devices.value.entries.joinToString(separator = ". ") { (id, state) ->
            "${id.replace('_', ' ')} is ${state.state}"
        }

    // ─────────────────────────────────────────────────────────────────────────
    //  Default device seed
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildDefaultDevices(): Map<String, DeviceState> = mapOf(
        "living_room_light" to DeviceState(
            entityId = "living_room_light",
            state = "off",
            attributes = mapOf("brightness_pct" to 100, "color_temp_kelvin" to 4000),
        ),
        "ceiling_fan_1" to DeviceState(
            entityId = "ceiling_fan_1",
            state = "off",
            attributes = mapOf("speed" to "medium"),
        ),
        "air_conditioner" to DeviceState(
            entityId = "air_conditioner",
            state = "off",
            attributes = mapOf("temperature" to 24),
        ),
        "front_door_lock" to DeviceState(
            entityId = "front_door_lock",
            state = "locked",
            attributes = mapOf("battery_level" to 95),
        ),
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Device state data class
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lightweight in-memory representation of a virtual device state.
     * Mirrors the subset of [com.omninode.hub.data.model.HaEntityState] used by OmniNode.
     */
    data class DeviceState(
        val entityId: String,
        val state: String,              // "on" | "off" | custom
        val attributes: Map<String, Any> = emptyMap(),
    )
}
