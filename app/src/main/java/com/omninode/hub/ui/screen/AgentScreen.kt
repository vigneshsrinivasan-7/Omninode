package com.omninode.hub.ui.screen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.omninode.hub.ui.theme.*
import com.omninode.hub.viewmodel.AgentViewModel
import kotlinx.coroutines.delay

/**
 * AgentScreen — Conversational Gemma-4-2B AI interface.
 *
 * Features:
 *  • Real-time token streaming from on-device Gemma (NPU accelerated)
 *  • Chat history with user and AI message bubbles
 *  • NPU utilization ring indicator
 *  • Parsed device command confirmation chips
 *  • Suggested prompts for quick access
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    navController: NavController,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val messages       by viewModel.messages.collectAsState()
    val isInferring    by viewModel.isInferring.collectAsState()
    val streamedText   by viewModel.currentStreamToken.collectAsState()
    val npuUtilization by viewModel.npuUtilization.collectAsState()
    val listState      = rememberLazyListState()
    var inputText      by remember { mutableStateOf("") }

    // ── Voice input state ─────────────────────────────────────────────────────
    val voiceInput = rememberVoiceInput(
        onResult = { spokenText -> 
            inputText = spokenText
            if (spokenText.isNotBlank() && !isInferring) {
                viewModel.sendMessage(spokenText.trim())
                inputText = ""
            }
        },
    )

    // Auto-scroll to latest message
    LaunchedEffect(messages.size, streamedText) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            AgentTopBar(
                npuUtilization = npuUtilization,
                isInferring    = isInferring,
                onBack         = { navController.popBackStack() },
            )
        },
        bottomBar = { OmniBottomBar(navController) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Message list ──────────────────────────────────────────────────
            LazyColumn(
                state    = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (messages.isEmpty()) {
                    item { SuggestedPrompts(onSelect = { inputText = it }) }
                }

                items(messages) { msg ->
                    MessageBubble(role = msg.first, content = msg.second)
                }

                if (isInferring && streamedText.isNotEmpty()) {
                    item {
                        MessageBubble(
                            role       = "assistant",
                            content    = streamedText,
                            isStreaming = true,
                        )
                    }
                }

                if (isInferring && streamedText.isEmpty()) {
                    item { ThinkingIndicator() }
                }
            }

            // ── Input bar ─────────────────────────────────────────────────────
            AgentInputBar(
                value          = inputText,
                enabled        = !isInferring,
                isMicListening = voiceInput.isListening,
                onChange       = { inputText = it },
                onSend         = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                onMicClick     = { voiceInput.launch() },
            )
        }
    }
}

@Composable
private fun AgentTopBar(
    npuUtilization: Float,
    isInferring: Boolean,
    onBack: () -> Unit,
) {
    Surface(
        color = BackgroundVariant,
        modifier = Modifier.border(
            width = 1.dp,
            color = Color(0xFF1A2035),
            shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = TextSecondary)
                }
                Column {
                    Text(
                        text  = "Gemma-4-2B",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                    )
                    Text(
                        text  = "On-device • Hexagon NPU",
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary,
                    )
                }
            }

            // NPU utilization ring
            NpuRingIndicator(utilization = npuUtilization, isActive = isInferring)
        }
    }
}

@Composable
private fun NpuRingIndicator(utilization: Float, isActive: Boolean) {
    val animatedUtil by animateFloatAsState(
        targetValue = utilization,
        animationSpec = tween(500),
        label = "npu_util",
    )
    val pulseAnim = rememberInfiniteTransition(label = "npu_pulse")
    val pulseAlpha by pulseAnim.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse_alpha",
    )

    Box(
        modifier = Modifier.size(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        val ringColor = if (isActive) Primary else OfflineGray
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset  = stroke / 2f
            drawCircle(
                color  = ringColor.copy(alpha = 0.15f),
                radius = size.minDimension / 2f - inset,
                style  = Stroke(width = stroke),
            )
            drawArc(
                brush     = Brush.sweepGradient(listOf(Primary, Secondary)),
                startAngle = -90f,
                sweepAngle = 360f * animatedUtil,
                useCenter = false,
                style     = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text  = if (isActive) "${(utilization * 100).toInt()}%" else "NPU",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = if (isActive) Primary.copy(alpha = pulseAlpha) else TextDisabled,
            ),
        )
    }
}

// Need to import Canvas
@Composable
private fun Canvas(modifier: Modifier, onDraw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    androidx.compose.foundation.Canvas(modifier = modifier, onDraw = onDraw)
}

@Composable
private fun MessageBubble(role: String, content: String, isStreaming: Boolean = false) {
    val isUser = role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(GradientViolet)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            shape    = RoundedCornerShape(
                topStart    = if (isUser) 18.dp else 4.dp,
                topEnd      = if (isUser) 4.dp  else 18.dp,
                bottomStart = 18.dp,
                bottomEnd   = 18.dp,
            ),
            color  = if (isUser) PrimaryDim else SurfaceElevated,
            border = if (!isUser) androidx.compose.foundation.BorderStroke(
                1.dp, Secondary.copy(alpha = 0.2f)
            ) else null,
        ) {
            Column(modifier = Modifier.padding(12.dp, 10.dp)) {
                Text(
                    text  = content,
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                )
                if (isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    StreamingCursor()
                }
            }
        }

        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(PrimaryDim),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun StreamingCursor() {
    val anim = rememberInfiniteTransition(label = "cursor")
    val alpha by anim.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "cursor_alpha",
    )
    Box(
        modifier = Modifier
            .width(2.dp)
            .height(14.dp)
            .background(Primary.copy(alpha = alpha))
    )
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.padding(start = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Gemma thinking", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        repeat(3) { idx ->
            val anim = rememberInfiniteTransition(label = "dot_$idx")
            val scale by anim.animateFloat(
                0.5f, 1f,
                infiniteRepeatable(
                    tween(400, delayMillis = idx * 133),
                    RepeatMode.Reverse,
                ),
                label = "dot_scale_$idx",
            )
            Box(
                modifier = Modifier
                    .size((6 * scale).dp)
                    .clip(CircleShape)
                    .background(Secondary.copy(alpha = scale)),
            )
        }
    }
}

@Composable
private fun SuggestedPrompts(onSelect: (String) -> Unit) {
    val prompts = listOf(
        "The ambient light feels too harsh, I need to read",
        "Start Deep Focus mode for working",
        "Turn off everything in the living room",
        "Set a cozy cinema atmosphere",
        "Activate Away mode, I'm leaving",
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text  = "Try asking Gemma:",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        prompts.forEach { prompt ->
            Surface(
                onClick  = { onSelect(prompt) },
                shape    = RoundedCornerShape(12.dp),
                color    = SurfaceGlass,
                border   = androidx.compose.foundation.BorderStroke(1.dp, Secondary.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp, 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = Secondary.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text  = prompt,
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Voice Input Hook
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Encapsulates all voice-input wiring:
 *  1. Checks / requests RECORD_AUDIO permission via Accompanist.
 *  2. Launches [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] via an
 *     [ActivityResultContracts.StartActivityForResult] launcher.
 *  3. Extracts the top hypothesis from [SpeechRecognizer.RESULTS_RECOGNITION]
 *     and calls [onResult] so the caller can inject it into the text field.
 *
 * Returns a [VoiceInputState] with [launch] and [isListening] so the
 * mic button can reflect the current recording state.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberVoiceInput(onResult: (String) -> Unit): VoiceInputState {
    var isListening by remember { mutableStateOf(false) }

    // ── RECORD_AUDIO permission ───────────────────────────────────────────────
    val recordPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    // ── Activity-result launcher for the system speech recognizer UI ──────────
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data
                ?.getStringArrayListExtra(SpeechRecognizer.RESULTS_RECOGNITION)
            val spokenText = matches?.firstOrNull().orEmpty()
            if (spokenText.isNotBlank()) {
                onResult(spokenText)
            }
        }
    }

    // ── Launch function exposed to the caller ─────────────────────────────────
    val launch: () -> Unit = {
        if (recordPermission.status.isGranted) {
            // Permission already granted — start recognition immediately
            isListening = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Gemma what to do…")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Partial results keep the UI responsive for long utterances
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechLauncher.launch(intent)
        } else {
            // Permission not yet granted — request it; user can tap mic again after
            recordPermission.launchPermissionRequest()
        }
    }

    return remember(isListening) {
        VoiceInputState(isListening = isListening, launch = launch)
    }
}

/** Immutable state holder returned by [rememberVoiceInput]. */
class VoiceInputState(
    val isListening: Boolean,
    val launch: () -> Unit,
)

// ─────────────────────────────────────────────────────────────────────────────
//  Agent Input Bar (with mic button)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AgentInputBar(
    value: String,
    enabled: Boolean,
    isMicListening: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
) {
    Surface(
        color = BackgroundVariant,
        modifier = Modifier.border(
            1.dp,
            Color(0xFF1A2035),
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Text field ────────────────────────────────────────────────────
            OutlinedTextField(
                value         = value,
                onValueChange = onChange,
                enabled       = enabled,
                placeholder   = {
                    Text(
                        if (isMicListening) "Listening…" else "Tell Gemma what to do...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isMicListening) Primary.copy(alpha = 0.8f) else TextDisabled,
                    )
                },
                modifier = Modifier.weight(1f),
                shape    = RoundedCornerShape(14.dp),
                colors   = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor      = Primary,
                    unfocusedBorderColor    = if (isMicListening) Primary.copy(0.6f) else Color(0xFF2A3550),
                    focusedContainerColor   = SurfaceGlass,
                    unfocusedContainerColor = SurfaceGlass,
                    focusedTextColor        = TextPrimary,
                    unfocusedTextColor      = TextPrimary,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                maxLines = 3,
            )

            // ── Mic button ────────────────────────────────────────────────────
            MicButton(
                isListening = isMicListening,
                enabled     = enabled,
                onClick     = onMicClick,
            )

            // ── Send FAB ──────────────────────────────────────────────────────
            FloatingActionButton(
                onClick        = onSend,
                modifier       = Modifier.size(50.dp),
                containerColor = if (enabled && value.isNotBlank()) Primary else OfflineGray,
                contentColor   = if (enabled && value.isNotBlank()) Background else TextDisabled,
                shape          = RoundedCornerShape(14.dp),
                elevation      = FloatingActionButtonDefaults.elevation(0.dp),
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send message")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Mic Button — animated pulsing ring when listening
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Microphone [IconButton] that shows:
 *  • **Idle** — muted mic icon with subtle border.
 *  • **Listening** — Quantum Cyan fill + infinite scale-pulse ring to signal
 *    active recording. The icon switches to [Icons.Default.MicOff] to let
 *    the user know the system recognizer is running.
 */
@Composable
fun MicButton(
    isListening: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Pulsing ring animation while listening
    val pulse = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue  = 1.25f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic_scale",
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.6f,
        targetValue  = 0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic_alpha",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(50.dp),
    ) {
        // Pulsing outer ring — only visible when listening
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .scale(pulseScale)
                    .background(
                        color  = Primary.copy(alpha = pulseAlpha),
                        shape  = RoundedCornerShape(14.dp),
                    )
            )
        }

        // Core button
        FilledIconButton(
            onClick  = onClick,
            enabled  = enabled,
            modifier = Modifier.size(50.dp),
            shape    = RoundedCornerShape(14.dp),
            colors   = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isListening) Primary else SurfaceGlass,
                contentColor   = if (isListening) Background else TextSecondary,
                disabledContainerColor = SurfaceGlass.copy(alpha = 0.5f),
                disabledContentColor   = TextDisabled,
            ),
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = if (isListening) "Stop listening" else "Speak to Gemma",
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
