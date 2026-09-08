package com.omninode.hub

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.omninode.hub.ai.NlpParser
import com.omninode.hub.network.HomeAssistantWsClient
import com.omninode.hub.service.TtsManager
import com.omninode.hub.ui.theme.OmniNodeTheme
import com.omninode.hub.ui.theme.Primary
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AssistantOverlayActivity : ComponentActivity() {

    @Inject lateinit var nlpParser: NlpParser
    @Inject lateinit var haWsClient: HomeAssistantWsClient
    @Inject lateinit var ttsManager: TtsManager

    private var speechRecognizer: SpeechRecognizer? = null
    private val speechTextState = mutableStateOf("")
    private val isFinalState = mutableStateOf(false)
    private val isListeningState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Play brief, pleasant wake tone
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 90)
                .startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Exception) {
            Timber.w("ToneGenerator failed: ${e.message}")
        }

        // Initialize SpeechRecognizer directly in this foreground Activity
        initAndStartRecognition()

        setContent {
            OmniNodeTheme {
                val speechText by speechTextState
                val isFinal by isFinalState
                val isListening by isListeningState

                LaunchedEffect(isFinal) {
                    if (isFinal) {
                        delay(2000)
                        finish()
                    }
                }

                AssistantOverlayUI(
                    speechText = speechText,
                    isFinal = isFinal,
                    isListening = isListening,
                    onDismiss = { finish() },
                    onRetry = {
                        speechTextState.value = ""
                        isFinalState.value = false
                        initAndStartRecognition()
                    }
                )
            }
        }
    }

    private fun initAndStartRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speechTextState.value = "Speech recognition unavailable on this device"
            isFinalState.value = true
            return
        }

        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) { /* ignore */ }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningState.value = true
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListeningState.value = false
                }

                override fun onError(error: Int) {
                    isListeningState.value = false
                    val errorString = when(error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
                        SpeechRecognizer.ERROR_NETWORK -> "Network required"
                        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't hear you. Tap to retry."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap to retry."
                        else -> "Tap to try again"
                    }
                    Timber.w("AssistantOverlayActivity speech error ($error): $errorString")
                    if (speechTextState.value.isBlank()) {
                        speechTextState.value = errorString
                        isFinalState.value = true
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListeningState.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val rawText = matches?.firstOrNull()?.trim() ?: ""

                    if (rawText.isBlank()) {
                        speechTextState.value = "Didn't catch that. Tap to retry."
                        isFinalState.value = true
                        return
                    }

                    // Clean utterance
                    var command = rawText.lowercase().trim()
                    val wakePrefixes = listOf("hey omni", "omni", "omini", "hey omini", "hello omni", "hi omni", "ok omni")
                    for (prefix in wakePrefixes) {
                        if (command.startsWith(prefix)) {
                            command = command.removePrefix(prefix).trim().trim('.', ',', '!', '?')
                            break
                        }
                    }

                    if (command.isBlank()) {
                        // User just said the wake word
                        speechTextState.value = "How can I help you?"
                        ttsManager.speak("How can I help you?")
                        // Continue listening for the actual command
                        initAndStartRecognition()
                        return
                    }

                    speechTextState.value = rawText
                    isFinalState.value = true
                    executeCommand(command)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull()?.trim() ?: ""
                    if (partial.isNotBlank()) {
                        speechTextState.value = partial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        try {
            speechRecognizer?.startListening(intent)
            Timber.i("AssistantOverlayActivity: listening started")
        } catch (e: Exception) {
            Timber.e(e, "AssistantOverlayActivity: startListening failed")
            speechTextState.value = "Could not start microphone"
            isFinalState.value = true
        }
    }

    private fun executeCommand(command: String) {
        lifecycleScope.launch {
            try {
                Timber.i("AssistantOverlayActivity executing: '$command'")
                val parsed = nlpParser.parse(command)

                when (parsed.intent) {
                    NlpParser.Intent.EXPLAIN -> {
                        ttsManager.speak(NlpParser.EXPLAIN_RESPONSE)
                    }
                    NlpParser.Intent.HELP -> {
                        ttsManager.speak(NlpParser.HELP_RESPONSE)
                    }
                    NlpParser.Intent.QUERY_STATUS -> {
                        val status = if (haWsClient.isInMockMode()) {
                            "Running in standalone mode. All virtual devices are operational."
                        } else {
                            "Connected to Home Assistant. All devices are active."
                        }
                        ttsManager.speak(status)
                    }
                    NlpParser.Intent.TURN_ON,
                    NlpParser.Intent.TURN_OFF -> {
                        parsed.commands.forEach { cmd ->
                            haWsClient.callService(cmd.domain, cmd.service, cmd.serviceData)
                        }
                        if (parsed.confirmation.isNotBlank()) {
                            ttsManager.speak(parsed.confirmation)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "AssistantOverlayActivity: command execution error")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) { /* ignore */ }
    }
}

@Composable
fun AssistantOverlayUI(
    speechText: String,
    isFinal: Boolean,
    isListening: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { onDismiss() }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 10.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isFinal) Icons.Default.CheckCircle else Icons.Default.Mic,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (isFinal) "Command Processed" else "Omni is listening...",
                        color = Primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (!isFinal) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Primary.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(56.dp),
                            color = Primary,
                            strokeWidth = 3.5.dp
                        )
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Primary.copy(alpha = 0.15f), CircleShape)
                            .clickable { onRetry() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (speechText.contains("try", ignoreCase = true) || speechText.contains("error", ignoreCase = true))
                                Icons.Default.Refresh else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = if (speechText.isNotBlank()) "\"$speechText\"" else "Speak a command (e.g. \"Turn off lights\")",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = if (speechText.isNotBlank()) 20.sp else 17.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Tap anywhere to dismiss",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }
}
