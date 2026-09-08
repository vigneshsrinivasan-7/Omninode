package com.omninode.hub.ai

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NlpParser — Rule-based semantic intent parser for OmniNode voice commands.
 *
 * Replaces the hardcoded keyword-matching stub in [GemmaEngine.buildSimulatedResponse].
 * This parser runs synchronously (< 1 ms) with zero model weight, making it suitable
 * as an always-on pre-filter before the full LiteRT inference path.
 *
 * Design:
 *  • Priority order: EXPLAIN > HELP > device-control intents.
 *  • Entity detection is multi-word aware (e.g. "ceiling fan", "air con").
 *  • Attribute extraction covers brightness %, temperature °C, and fan speed.
 *
 * Structured output:
 *  [ParsedIntent] → fed to [GemmaEngine] for JSON command generation and to
 *  [OmniBackgroundService] TTS engine for voice confirmation.
 */
@Singleton
class NlpParser @Inject constructor() {

    // ─────────────────────────────────────────────────────────────────────────
    //  Intent keywords
    // ─────────────────────────────────────────────────────────────────────────

    private val turnOnKeywords  = listOf("turn on", "switch on", "enable", "activate", "on", "lock", "close")
    private val turnOffKeywords = listOf("turn off", "switch off", "disable", "deactivate", "off", "unlock", "open")
    private val queryKeywords   = listOf("status", "state", "what is", "is the", "are the", "check")
    private val helpKeywords    = listOf("help", "what can you do", "commands", "usage")
    private val explainKeywords = listOf("how do you work", "how does it work", "explain yourself",
                                         "what are you", "who are you", "what is omni", "about you")
    private val dimKeywords     = listOf("dim", "lower", "decrease", "reduce", "darken")
    private val brightenKeywords = listOf("brighten", "increase", "boost", "raise brightness")

    // ─────────────────────────────────────────────────────────────────────────
    //  Entity keywords → canonical entity ID map
    // ─────────────────────────────────────────────────────────────────────────

    private val entityMap: List<Pair<List<String>, EntityType>> = listOf(
        listOf("front door", "door lock", "main door", "door", "lock") to EntityType.LOCK,
        listOf("ceiling fan", "fan")                  to EntityType.FAN,
        listOf("light", "lamp", "bulb", "lights")     to EntityType.LIGHT,
        listOf("air conditioner", "air con", "ac", "air conditioning", "cooler") to EntityType.AC,
        listOf("plug", "socket", "outlet", "power strip") to EntityType.PLUG,
        listOf("everything", "all devices", "all lights", "whole room") to EntityType.ALL,
    )

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses a raw natural-language utterance into a [ParsedIntent].
     *
     * @param utterance The transcribed speech or typed text (already lowercased internally).
     * @return A [ParsedIntent] with extracted intent, entity, attributes, confirmation text,
     *         and a list of [DeviceCommand]s ready to forward to HA or [VirtualDeviceRegistry].
     */
    fun parse(utterance: String): ParsedIntent {
        val text = utterance.lowercase().trim()
        Timber.d("NlpParser.parse: '$text'")

        // ── Priority 1: Explain intent ────────────────────────────────────────
        if (explainKeywords.any { text.contains(it) }) {
            return ParsedIntent(
                intent       = Intent.EXPLAIN,
                entity       = EntityType.NONE,
                confirmation = EXPLAIN_RESPONSE,
                commands     = emptyList(),
            )
        }

        // ── Priority 2: Help intent ───────────────────────────────────────────
        if (helpKeywords.any { text.contains(it) }) {
            return ParsedIntent(
                intent       = Intent.HELP,
                entity       = EntityType.NONE,
                confirmation = HELP_RESPONSE,
                commands     = emptyList(),
            )
        }

        // ── Detect entity ─────────────────────────────────────────────────────
        val entity = detectEntity(text)

        // ── Detect brightness attribute ───────────────────────────────────────
        val brightness = extractBrightness(text)
        val temperature = extractTemperature(text)
        val speed = extractFanSpeed(text)

        // ── Priority 3: TURN_OFF ──────────────────────────────────────────────
        if (turnOffKeywords.any { text.contains(it) }) {
            val commands = buildCommands(entity, "turn_off", brightness, temperature, speed)
            return ParsedIntent(
                intent       = Intent.TURN_OFF,
                entity       = entity,
                confirmation = buildConfirmation(Intent.TURN_OFF, entity),
                commands     = commands,
            )
        }

        // ── Priority 4: TURN_ON ───────────────────────────────────────────────
        if (turnOnKeywords.any { text.contains(it) } ||
            dimKeywords.any { text.contains(it) } ||
            brightenKeywords.any { text.contains(it) } ||
            brightness != null) {
            val commands = buildCommands(entity, "turn_on", brightness, temperature, speed)
            return ParsedIntent(
                intent       = Intent.TURN_ON,
                entity       = entity,
                confirmation = buildConfirmation(Intent.TURN_ON, entity, brightness, temperature, speed),
                commands     = commands,
            )
        }

        // ── Priority 5: QUERY_STATUS ──────────────────────────────────────────
        if (queryKeywords.any { text.contains(it) }) {
            return ParsedIntent(
                intent       = Intent.QUERY_STATUS,
                entity       = entity,
                confirmation = "", // Caller fills in live state
                commands     = emptyList(),
            )
        }

        // ── Fallback: unknown intent — treat as TURN_ON for safety ────────────
        Timber.w("NlpParser: could not determine intent for '$text' — defaulting to TURN_ON")
        val commands = buildCommands(entity, "turn_on", brightness, temperature, speed)
        return ParsedIntent(
            intent       = Intent.TURN_ON,
            entity       = entity,
            confirmation = buildConfirmation(Intent.TURN_ON, entity),
            commands     = commands,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Entity detection
    // ─────────────────────────────────────────────────────────────────────────

    private fun detectEntity(text: String): EntityType {
        // Walk the entity map in order (most specific first — ceiling fan before fan)
        for ((keywords, entityType) in entityMap) {
            if (keywords.any { text.contains(it) }) return entityType
        }
        return EntityType.LIGHT // default if no entity keyword found
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Attribute extraction
    // ─────────────────────────────────────────────────────────────────────────

    /** Extracts brightness percentage (e.g. "50 percent", "to 70%"). Returns null if not found. */
    private fun extractBrightness(text: String): Int? {
        val pctRegex = Regex("""(\d{1,3})\s*(?:percent|%|pct)""")
        val match = pctRegex.find(text) ?: return null
        return match.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
    }

    /** Extracts temperature in °C (e.g. "22 degrees", "to 24°C"). Returns null if not found. */
    private fun extractTemperature(text: String): Int? {
        val tempRegex = Regex("""(\d{2})\s*(?:degrees?|°c|celsius)""")
        val match = tempRegex.find(text) ?: return null
        return match.groupValues[1].toIntOrNull()?.coerceIn(16, 30)
    }

    /** Extracts fan speed keyword ("low", "medium", "high"). Returns null if not found. */
    private fun extractFanSpeed(text: String): String? = when {
        text.contains("low speed") || text.contains("slow") -> "low"
        text.contains("high speed") || text.contains("fast") || text.contains("full") -> "high"
        text.contains("medium") || text.contains("mid") -> "medium"
        else -> null
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Command building
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildCommands(
        entity: EntityType,
        service: String,
        brightness: Int?,
        temperature: Int?,
        speed: String?,
    ): List<DeviceCommand> {
        val entities = entityToIds(entity)
        return entities.map { entityId ->
            val serviceData = mutableMapOf<String, Any>("entity_id" to entityId)
            if (service == "turn_on") {
                brightness?.let { serviceData["brightness_pct"] = it }
                temperature?.let { serviceData["temperature"] = it }
                speed?.let { serviceData["speed"] = it }
            }
            val (domain, resolvedService) = resolveDomainService(entityId, service)
            DeviceCommand(
                entityId    = entityId,
                domain      = domain,
                service     = resolvedService,
                serviceData = serviceData,
            )
        }
    }

    private fun entityToIds(entity: EntityType): List<String> = when (entity) {
        EntityType.LIGHT -> listOf("living_room_light")
        EntityType.FAN   -> listOf("ceiling_fan_1")
        EntityType.AC    -> listOf("air_conditioner")
        EntityType.PLUG  -> listOf("plug_tv_01")
        EntityType.LOCK  -> listOf("front_door_lock")
        EntityType.ALL   -> listOf("living_room_light", "ceiling_fan_1", "air_conditioner")
        EntityType.NONE  -> emptyList()
    }

    private fun resolveDomainService(entityId: String, service: String): Pair<String, String> {
        val domain = when {
            entityId.contains("lock")  -> return "lock" to if (service == "turn_on") "lock" else "unlock"
            entityId.contains("light") -> "light"
            entityId.contains("fan")   -> "fan"
            entityId.contains("air") || entityId.contains("ac") -> "climate"
            entityId.contains("plug")  -> "switch"
            else -> "homeassistant"
        }
        return domain to service
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Confirmation string builder
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildConfirmation(
        intent: Intent,
        entity: EntityType,
        brightness: Int? = null,
        temperature: Int? = null,
        speed: String? = null,
    ): String {
        val entityName = when (entity) {
            EntityType.LIGHT -> "the living room light"
            EntityType.FAN   -> "the ceiling fan"
            EntityType.AC    -> "the air conditioner"
            EntityType.PLUG  -> "the plug"
            EntityType.LOCK  -> "the front door"
            EntityType.ALL   -> "all devices"
            EntityType.NONE  -> "the device"
        }
        return when (intent) {
            Intent.TURN_OFF -> if (entity == EntityType.LOCK) "Unlocking the front door now." else "Turning off $entityName now."
            Intent.TURN_ON  -> if (entity == EntityType.LOCK) "Locking the front door now." else buildString {
                append("Turning on $entityName")
                when {
                    brightness != null -> append(" at $brightness percent brightness")
                    temperature != null -> append(" and setting temperature to $temperature degrees")
                    speed != null -> append(" at $speed speed")
                }
                append(".")
            }
            Intent.QUERY_STATUS -> "Checking the status of $entityName."
            Intent.HELP    -> HELP_RESPONSE
            Intent.EXPLAIN -> EXPLAIN_RESPONSE
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Canned response strings
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val EXPLAIN_RESPONSE =
            "I am OmniNode, an edge-compute smart home coordinator running on your device. " +
            "I orchestrate your local appliances offline without relying on cloud servers."

        const val HELP_RESPONSE =
            "I can control your lights, ceiling fan, air conditioner, plugs, and door locks. " +
            "Try saying: turn off the fan, lock the front door, dim the lights to 40 percent, or set AC to 22 degrees."
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Result types
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fully parsed user intent ready for action and TTS confirmation.
     *
     * @param intent       The classified intent type.
     * @param entity       The target device category.
     * @param confirmation Human-readable English confirmation string for TTS.
     * @param commands     Zero or more [DeviceCommand]s to execute.
     */
    data class ParsedIntent(
        val intent       : Intent,
        val entity       : EntityType,
        val confirmation : String,
        val commands     : List<DeviceCommand>,
    )

    /**
     * A concrete service call ready to be forwarded to HA or [VirtualDeviceRegistry].
     */
    data class DeviceCommand(
        val entityId    : String,
        val domain      : String,
        val service     : String,
        val serviceData : Map<String, Any>,
    )

    enum class Intent {
        TURN_ON,
        TURN_OFF,
        QUERY_STATUS,
        HELP,
        EXPLAIN,
    }

    enum class EntityType {
        LIGHT,
        FAN,
        AC,
        PLUG,
        LOCK,
        ALL,
        NONE,
    }
}
