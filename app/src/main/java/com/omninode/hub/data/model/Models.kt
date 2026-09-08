package com.omninode.hub.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ─────────────────────────────────────────────────────────────────────────────
//  Domain Models — OmniNode Smart Living Ecosystem
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Represents a physical smart home structure (e.g. "Primary Residence").
 */
data class SmartStructure(
    val id: String,
    val name: String,
    val rooms: List<SmartRoom> = emptyList(),
)

/**
 * A user-defined room within a structure (e.g. "Living Room").
 */
data class SmartRoom(
    val id: String,
    val name: String,
    val devices: List<SmartDevice> = emptyList(),
)

/**
 * A physical smart device endpoint, classified by [DeviceType].
 */
data class SmartDevice(
    val id: String,
    val name: String,
    val type: DeviceType,
    val roomId: String,
    val traits: Map<TraitType, Any> = emptyMap(),
    val isOnline: Boolean = true,
    val protocolSource: ProtocolSource = ProtocolSource.MATTER,
)

enum class DeviceType {
    DIMMABLE_LIGHT,
    COLOR_TEMPERATURE_LIGHT,
    FULL_COLOR_LIGHT,
    ON_OFF_PLUG,
    CONTACT_SENSOR,
    OCCUPANCY_SENSOR,
    THERMOSTAT,
    MEDIA_PLAYER,
    SMART_LOCK,
    CAMERA,
    ROBOTIC_VACUUM,
    UNKNOWN,
}

enum class TraitType {
    ON_OFF,
    BRIGHTNESS,       // 0–100 %
    COLOR_TEMPERATURE, // Kelvin
    COLOR_HSV,
    TEMPERATURE,      // °C
    HUMIDITY,
    CONTACT_STATE,
    OCCUPANCY,
    VOLUME,
    LOCK_STATE,
}

enum class ProtocolSource {
    MATTER,           // Controlled via Google Home APIs SDK
    HOME_ASSISTANT,   // Controlled via OkHttp WebSocket
    BLE_MESH,         // Direct BLE Mesh fallback
    WIFI_DIRECT,      // Wi-Fi P2P fallback
}

// ─────────────────────────────────────────────────────────────────────────────
//  Home Assistant WebSocket Protocol Models (JSON deserialization via Moshi)
// ─────────────────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class HaWsMessage(
    @Json(name = "type") val type: String,
    @Json(name = "id")   val id: Int? = null,
)

@JsonClass(generateAdapter = true)
data class HaAuthMessage(
    @Json(name = "type")         val type: String = "auth",
    @Json(name = "access_token") val accessToken: String,
)

@JsonClass(generateAdapter = true)
data class HaSubscribeEvents(
    @Json(name = "id")         val id: Int,
    @Json(name = "type")       val type: String = "subscribe_events",
    @Json(name = "event_type") val eventType: String = "state_changed",
)

@JsonClass(generateAdapter = true)
data class HaCallService(
    @Json(name = "id")           val id: Int,
    @Json(name = "type")         val type: String = "call_service",
    @Json(name = "domain")       val domain: String,
    @Json(name = "service")      val service: String,
    @Json(name = "service_data") val serviceData: Map<String, Any>,
)

@JsonClass(generateAdapter = true)
data class HaStateChangedEvent(
    @Json(name = "entity_id") val entityId: String,
    @Json(name = "new_state") val newState: HaEntityState?,
)

@JsonClass(generateAdapter = true)
data class HaEntityState(
    @Json(name = "entity_id")    val entityId: String,
    @Json(name = "state")        val state: String,
    @Json(name = "attributes")   val attributes: Map<String, Any> = emptyMap(),
    @Json(name = "last_changed") val lastChanged: String,
)

// ─────────────────────────────────────────────────────────────────────────────
//  AI Agent Models
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Structured JSON payload emitted by the on-device Gemma-4-2B LLM after
 * interpreting user intent, mapped to IoT control actions.
 */
@JsonClass(generateAdapter = true)
data class AgentCommand(
    @Json(name = "device_id")    val deviceId: String,
    @Json(name = "trait")        val trait: String,
    @Json(name = "value")        val value: Any,
    @Json(name = "confidence")   val confidence: Float = 1.0f,
    @Json(name = "reasoning")    val reasoning: String = "",
)

/**
 * Visual scene analysis output from FastVLM.
 */
data class SceneAnalysis(
    val description: String,
    val detectedObjects: List<String>,
    val suggestedAutomations: List<AgentCommand>,
    val inferenceTimeMs: Long,
)

/**
 * UI state wrapper for reactive Compose screens.
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>
}

/**
 * Network connectivity mode — determines which protocol OmniNode uses.
 */
enum class ConnectivityMode {
    FULL_WIFI,         // Primary LAN — Matter + WebSocket available
    LOCAL_ONLY,        // LAN only (internet down) — Matter + WebSocket available
    WIFI_DIRECT,       // Router offline — Wi-Fi Direct P2P active
    BLE_MESH,          // Wi-Fi degraded — BLE Mesh active
    OFFLINE,           // All connectivity lost
}

/**
 * Automation preset — maps to a named profile that adjusts multiple devices.
 */
enum class AutomationPreset(
    val displayName: String,
    val description: String,
    val iconName: String,
) {
    READING_MODE(
        "Reading Mode",
        "Dims lights to 40% • 4000K warm white • Mutes media",
        "menu_book"
    ),
    DEEP_FOCUS(
        "Deep Focus",
        "5000K cool white • Volume −50% • Redirects alerts to PC",
        "psychology"
    ),
    CINEMA_MODE(
        "Cinema Mode",
        "Lights off • TV on • Blackout blinds if available",
        "movie"
    ),
    GOOD_MORNING(
        "Good Morning",
        "Gradual brightness • Warm coffee-hour temperature • News playlist",
        "wb_sunny"
    ),
    AWAY_MODE(
        "Away Mode",
        "All off • Security arm • Notify on motion events",
        "home_work"
    ),
    CUSTOM(
        "Custom",
        "User-defined automation preset",
        "tune"
    ),
}
