package com.omninode.hub.ai

import com.omninode.hub.data.model.AgentCommand
import com.omninode.hub.data.model.listAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GemmaEngine — On-device Gemma-4-2B inference engine via LiteRT + QNN delegate.
 *
 * ═════════════════════════════════════════════════════════════════════════════
 *  Model Specs (Snapdragon 8 Elite Gen 5):
 *    • Parameters : 2 billion
 *    • Quantization: INT8 weights / INT16 activations
 *    • Peak memory : ~2.2 GB (fits in 8–16 GB LPDDR5X)
 *    • Prefill speed: — tokens/s (via Hexagon NPU)
 *    • Decode speed : — tokens/s
 *
 *  Primary use cases in OmniNode:
 *    1. Natural language → structured JSON automation command mapping
 *    2. Context-aware multi-device scene orchestration
 *    3. Generating human-readable summaries from FastVLM scene analysis
 *    4. Office Kit alert formatting (pushed to PC clipboard)
 *
 *  Model file: gemma4_2b_sm8850.litertlm
 *    • Pre-compiled via Qualcomm AI Hub Workbench targeting SM8850
 *    • Pushed to device via ADB: adb push gemma4_2b_sm8850.litertlm /sdcard/
 *    • Copied to context.filesDir/models/ at first launch
 * ═════════════════════════════════════════════════════════════════════════════
 */
@Singleton
class GemmaEngine @Inject constructor(
    private val liteRtManager: LiteRtManager,
    private val moshi: Moshi,
    private val nlpParser: NlpParser,
) {
    private val commandAdapter = moshi.adapter(AgentCommand::class.java)
    private var isModelLoaded: Boolean = false

    // ── System prompt — instructs Gemma to output structured IoT JSON ─────────
    private val systemPrompt = """
        You are OmniNode, an on-device Smart Living AI assistant running on iQOO Snapdragon 8 Elite.
        Your role is to interpret natural language commands and environmental context, then output
        a JSON array of device control actions.
        
        Output format (strict JSON — no prose, no markdown):
        [
          {
            "device_id": "<id>",
            "trait": "<OnOff|Brightness|ColorTemperature|Volume>",
            "value": <value>,
            "confidence": <0.0–1.0>,
            "reasoning": "<one sentence>"
          }
        ]
        
        Rules:
        - Brightness is always 0–100 (percentage)
        - ColorTemperature is in Kelvin (2700–6500)
        - OnOff value is true or false
        - Only control devices listed in the context
        - Output ONLY valid JSON. No prose.
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Streams token output from Gemma-4-2B for the given user query.
     * Emits one String per generated token for real-time UI streaming.
     *
     * @param userQuery    Natural language input (e.g. "Make the room cozy for reading")
     * @param deviceContext JSON description of available devices in the current room
     */
    fun streamTokens(userQuery: String, deviceContext: String): Flow<String> = flow {
        if (!liteRtManager.isReady()) {
            Timber.w("GemmaEngine: LiteRT not initialised — cannot run inference")
            emit("[Error: NPU runtime not ready]")
            return@flow
        }

        val fullPrompt = buildPrompt(userQuery, deviceContext)
        Timber.d("Gemma inference starting for query: '$userQuery'")
        val startMs = System.currentTimeMillis()

        // ── TODO: Replace with real GenieX / LiteRT token streaming ───────────
        // The GenieX runtime provides high-level Kotlin bindings:
        //
        // val session = GenieSession.create(modelPath, options)
        // session.generate(fullPrompt).collect { token ->
        //     emit(token)
        // }
        //
        // For build-time scaffold, emit NLP-driven simulated streaming output:
        // ──────────────────────────────────────────────────────────────────────
        simulateStreamingInference(userQuery).collect { token ->
            emit(token)
        }

        val elapsedMs = System.currentTimeMillis() - startMs
        Timber.i("Gemma inference completed in ${elapsedMs}ms delegate=${liteRtManager.getActiveDelegate()}")

    }.flowOn(Dispatchers.Default)

    /**
     * Runs a single inference pass and returns a list of parsed [AgentCommand]s.
     * Blocks until the full response is generated.
     */
    suspend fun generateCommands(userQuery: String, deviceContext: String): List<AgentCommand> {
        val sb = StringBuilder()
        streamTokens(userQuery, deviceContext).collect { token -> sb.append(token) }
        return parseCommandJson(sb.toString())
    }

    /**
     * Returns a plain-English TTS confirmation string for [userQuery] using [NlpParser].
     * This is fast (< 1 ms, no inference) and safe to call from the audio pipeline.
     *
     * @param userQuery  The transcribed utterance or typed command.
     * @return A human-readable sentence, e.g. "Turning off the ceiling fan now."
     */
    fun generateConfirmation(userQuery: String): String {
        val parsed = nlpParser.parse(userQuery)
        return parsed.confirmation
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Prompt construction
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildPrompt(userQuery: String, deviceContext: String): String = """
        <system>
        $systemPrompt
        </system>
        <context>
        Available devices:
        $deviceContext
        </context>
        <user>
        $userQuery
        </user>
        <assistant>
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  JSON parsing
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseCommandJson(rawJson: String): List<AgentCommand> {
        return try {
            val cleaned = rawJson.substringAfter("[").substringBeforeLast("]").let { "[$it]" }
            moshi.listAdapter(AgentCommand::class.java).fromJson(cleaned) ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse Gemma command JSON: $rawJson")
            emptyList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Simulation — NLP-driven scaffold (no hardcoded device IDs)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Simulates Gemma token streaming using [NlpParser] for dynamic command generation.
     * Replace with real GenieX session streaming once the SDK is integrated.
     *
     * All responses are derived from the parsed intent — there are NO hardcoded
     * device IDs or ceiling-light-only fallbacks in this path.
     */
    private fun simulateStreamingInference(userQuery: String): Flow<String> = flow {
        val response = buildDynamicResponse(userQuery)
        response.split(" ").forEach { word ->
            emit("$word ")
            kotlinx.coroutines.delay(30)
        }
    }

    /**
     * Converts an NLP-parsed intent into a JSON response string compatible with
     * the [AgentCommand] schema. Dynamically resolves entity IDs and traits from
     * [NlpParser.ParsedIntent] — never falls back to a hardcoded device.
     */
    private fun buildDynamicResponse(query: String): String {
        val parsed = nlpParser.parse(query)

        // HELP / EXPLAIN / QUERY_STATUS intents carry no device commands
        if (parsed.commands.isEmpty()) return "[]"

        val commandJsonParts = parsed.commands.mapNotNull { cmd ->
            val brightness   = cmd.serviceData["brightness_pct"]
            val colorTemp    = cmd.serviceData["color_temp_kelvin"]
            val temperature  = cmd.serviceData["temperature"]
            val isOn         = cmd.service == "turn_on"

            when {
                brightness != null ->
                    """{"device_id":"${cmd.entityId}","trait":"Brightness","value":$brightness,"confidence":0.93,"reasoning":"User requested brightness adjustment"}"""
                colorTemp != null ->
                    """{"device_id":"${cmd.entityId}","trait":"ColorTemperature","value":$colorTemp,"confidence":0.91,"reasoning":"User requested color temperature change"}"""
                temperature != null ->
                    """{"device_id":"${cmd.entityId}","trait":"Temperature","value":$temperature,"confidence":0.94,"reasoning":"User set target temperature"}"""
                else ->
                    """{"device_id":"${cmd.entityId}","trait":"OnOff","value":$isOn,"confidence":0.95,"reasoning":"${parsed.intent.name} command for ${cmd.entityId}"}"""
            }
        }

        return "[${commandJsonParts.joinToString(",")}]"
    }
}
