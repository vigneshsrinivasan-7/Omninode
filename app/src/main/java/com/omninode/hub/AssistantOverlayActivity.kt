package com.omninode.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.omninode.hub.ui.theme.OmniNodeTheme
import com.omninode.hub.ui.theme.Primary
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class AssistantOverlayActivity : ComponentActivity() {

    private val speechTextState = mutableStateOf("")
    private val isFinalState = mutableStateOf(false)

    private val speechReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.omninode.hub.action.PARTIAL_SPEECH" -> {
                    val partial = intent.getStringExtra("partial_text") ?: ""
                    if (partial.isNotBlank()) {
                        speechTextState.value = partial
                    }
                }
                "com.omninode.hub.action.FINAL_SPEECH" -> {
                    val finalTxt = intent.getStringExtra("final_text") ?: ""
                    if (finalTxt.isNotBlank()) {
                        speechTextState.value = finalTxt
                        isFinalState.value = true
                    }
                }
                "com.omninode.hub.action.SPEECH_ERROR" -> {
                    val error = intent.getStringExtra("error_message") ?: ""
                    if (speechTextState.value.isBlank()) {
                        speechTextState.value = "Didn't catch that. Tap to retry."
                        isFinalState.value = true
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter().apply {
            addAction("com.omninode.hub.action.PARTIAL_SPEECH")
            addAction("com.omninode.hub.action.FINAL_SPEECH")
            addAction("com.omninode.hub.action.SPEECH_ERROR")
        }
        ContextCompat.registerReceiver(
            this,
            speechReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Ensure OmniBackgroundService is actively listening for the user's command
        try {
            val triggerIntent = Intent(this, com.omninode.hub.service.OmniBackgroundService::class.java).apply {
                action = com.omninode.hub.service.OmniBackgroundService.ACTION_TRIGGER_VOICE
            }
            startService(triggerIntent)
        } catch (e: Exception) {
            // Service already running or restricted
        }

        setContent {
            OmniNodeTheme {
                val speechText by speechTextState
                val isFinal by isFinalState

                LaunchedEffect(isFinal) {
                    if (isFinal) {
                        delay(2200)
                        finish()
                    }
                }

                AssistantOverlayUI(
                    speechText = speechText,
                    isFinal = isFinal,
                    onDismiss = { finish() },
                    onRetry = {
                        speechTextState.value = ""
                        isFinalState.value = false
                        try {
                            val triggerIntent = Intent(this@AssistantOverlayActivity, com.omninode.hub.service.OmniBackgroundService::class.java).apply {
                                action = com.omninode.hub.service.OmniBackgroundService.ACTION_TRIGGER_VOICE
                            }
                            startService(triggerIntent)
                        } catch (e: Exception) { }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(speechReceiver)
        } catch (e: Exception) {
            // Ignored if not registered
        }
    }
}

@Composable
fun AssistantOverlayUI(
    speechText: String,
    isFinal: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = {}
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Primary,
                        strokeWidth = 3.5.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Primary.copy(alpha = 0.15f), RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = if (speechText.isNotBlank()) "\"$speechText\"" else "How can I help you?",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = if (speechText.isNotBlank()) 20.sp else 22.sp,
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
