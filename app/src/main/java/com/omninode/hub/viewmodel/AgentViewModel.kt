package com.omninode.hub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omninode.hub.ai.GemmaEngine
import com.omninode.hub.ai.LiteRtManager
import com.omninode.hub.ai.NlpParser
import com.omninode.hub.data.model.AgentCommand
import com.omninode.hub.network.HomeAssistantWsClient
import com.omninode.hub.service.TtsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class AgentViewModel @Inject constructor(
    private val gemmaEngine: GemmaEngine,
    private val haWsClient: HomeAssistantWsClient,
    private val liteRtManager: LiteRtManager,
    private val nlpParser: NlpParser,
    private val ttsManager: TtsManager,
) : ViewModel() {

    // ── Messages: List<Pair<role, content>> ──────────────────────────────────
    private val _messages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val messages: StateFlow<List<Pair<String, String>>> = _messages.asStateFlow()

    private val _isInferring         = MutableStateFlow(false)
    val isInferring: StateFlow<Boolean> = _isInferring.asStateFlow()

    private val _currentStreamToken  = MutableStateFlow("")
    val currentStreamToken: StateFlow<String> = _currentStreamToken.asStateFlow()

    private val _npuUtilization       = MutableStateFlow(0f)
    val npuUtilization: StateFlow<Float> = _npuUtilization.asStateFlow()

    fun sendMessage(userText: String) {
        viewModelScope.launch {
            // Add user message
            _messages.value = _messages.value + ("user" to userText)
            _isInferring.value   = true
            _currentStreamToken.value = ""
            _npuUtilization.value = 0.2f

            try {
                // Determine reply using rule-based NLP parser first to ensure fast, conversational response
                val textLower = userText.lowercase()
                val greetingKeywords = listOf("hi", "hello", "hey")
                
                val replyText: String
                
                if (greetingKeywords.any { textLower.startsWith(it) || textLower == it }) {
                    replyText = "Hello! I'm Omni. What can I do for you today?"
                } else {
                    val parsed = nlpParser.parse(userText)
                    
                    replyText = when (parsed.intent) {
                        NlpParser.Intent.EXPLAIN -> NlpParser.EXPLAIN_RESPONSE
                        NlpParser.Intent.HELP -> NlpParser.HELP_RESPONSE
                        NlpParser.Intent.QUERY_STATUS -> {
                            if (haWsClient.isInMockMode()) {
                                "I am running in standalone mode. No live Home Assistant is connected."
                            } else {
                                "I am connected to your Home Assistant instance."
                            }
                        }
                        NlpParser.Intent.TURN_ON, NlpParser.Intent.TURN_OFF -> {
                            // Execute the hardware commands silently
                            parsed.commands.forEach { cmd ->
                                try {
                                    haWsClient.callService(cmd.domain, cmd.service, cmd.serviceData)
                                    Timber.i("Agent executed: [${cmd.domain}.${cmd.service} on ${cmd.entityId}]")
                                } catch (e: Exception) {
                                    Timber.e(e, "Failed to execute agent command")
                                }
                            }
                            
                            // Determine confirmation response
                            if (parsed.entity == NlpParser.EntityType.NONE) {
                                "I heard you, but I'm not sure which appliance to control. You can ask me to toggle lights, adjust the fan, or check device status."
                            } else {
                                parsed.confirmation
                            }
                        }
                    }
                }
                
                // Speak the reply
                ttsManager.speak(replyText)
                
                // Show in chat
                _messages.value = _messages.value + ("assistant" to replyText)

            } catch (e: Exception) {
                Timber.e(e, "Agent processing failed")
                _messages.value = _messages.value + ("assistant" to "[Error: ${e.message}]")
            } finally {
                _currentStreamToken.value = ""
                _isInferring.value = false
                _npuUtilization.value = 0f
            }
        }
    }
}
