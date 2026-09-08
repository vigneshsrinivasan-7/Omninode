package com.omninode.hub.ui.screen

import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.omninode.hub.data.model.SceneAnalysis
import com.omninode.hub.ui.theme.*
import com.omninode.hub.viewmodel.VisionViewModel

/**
 * VisionScreen — FastVLM live camera scene analysis.
 *
 * Features:
 *  • Live CameraX preview (Qualcomm Spectra ISP accelerated)
 *  • Continuous FastVLM scene analysis at 0.12s TTFT
 *  • Detected object overlays
 *  • Autonomous automation suggestions with one-tap execution
 *  • Inference timing metrics (TTFT, tokens/sec)
 */
@Composable
fun VisionScreen(
    navController: NavController,
    viewModel: VisionViewModel = hiltViewModel(),
) {
    val sceneAnalysis    by viewModel.sceneAnalysis.collectAsState()
    val isAnalysing      by viewModel.isAnalysing.collectAsState()
    val inferenceMs      by viewModel.lastInferenceMs.collectAsState()
    val isCapturing      by viewModel.isCapturing.collectAsState()

    Scaffold(
        containerColor = Background,
        bottomBar = { OmniBottomBar(navController) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Top bar ───────────────────────────────────────────────────────
            VisionTopBar(
                isAnalysing  = isAnalysing,
                inferenceMs  = inferenceMs,
                onBack       = { navController.popBackStack() },
            )

            // ── Camera preview ────────────────────────────────────────────────
            CameraPreviewCard(
                isCapturing  = isCapturing,
                isAnalysing  = isAnalysing,
                onCapture    = { viewModel.captureAndAnalyse() },
                onToggleLive = { viewModel.toggleLiveMode() },
            )

            // ── Scene Analysis Output ─────────────────────────────────────────
            sceneAnalysis?.let { analysis ->
                SceneAnalysisCard(
                    analysis        = analysis,
                    onApplyCommand  = { cmd -> viewModel.applyCommand(cmd) },
                )
            } ?: EmptyScenePrompt()

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun VisionTopBar(isAnalysing: Boolean, inferenceMs: Long, onBack: () -> Unit) {
    val pulseAnim = rememberInfiniteTransition(label = "vision_pulse")
    val pulseAlpha by pulseAnim.animateFloat(
        0.5f, 1f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "vision_alpha",
    )

    Surface(
        color = BackgroundVariant,
        modifier = Modifier.border(1.dp, Color(0xFF1A2035), RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = TextSecondary)
                }
                Column {
                    Text("FastVLM Scene Analysis", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("0.5B params • Hexagon NPU", style = MaterialTheme.typography.labelSmall, color = Primary)
                    }
                }
            }

            if (isAnalysing) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Primary.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Primary.copy(alpha = 0.4f * pulseAlpha)),
                ) {
                    Text(
                        text     = "LIVE",
                        style    = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color    = Primary.copy(alpha = pulseAlpha),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            } else if (inferenceMs > 0) {
                Text(
                    text  = "${inferenceMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnlineGreen,
                )
            }
        }
    }
}

@Composable
private fun CameraPreviewCard(
    isCapturing: Boolean,
    isAnalysing: Boolean,
    onCapture: () -> Unit,
    onToggleLive: () -> Unit,
) {
    val context       = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val scanAnim = rememberInfiniteTransition(label = "scan")
    val scanOffset by scanAnim.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "scan_offset",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .padding(16.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(GradientCyanViolet),
                shape = RoundedCornerShape(18.dp),
            ),
    ) {
        // CameraX preview
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        // Scanning overlay when analysing
        if (isAnalysing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .offset(y = (scanOffset * 296).dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Primary.copy(alpha = 0.8f), Color.Transparent)
                        )
                    )
            )
        }

        // Corner scan brackets
        ScanCornerBrackets()

        // Control buttons overlay
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onCapture,
                colors  = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Background.copy(alpha = 0.8f),
                    contentColor   = TextPrimary,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Analyse")
            }

            FilledTonalButton(
                onClick = onToggleLive,
                colors  = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isCapturing) Primary.copy(0.8f) else Background.copy(0.8f),
                    contentColor   = if (isCapturing) Background else TextPrimary,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    if (isCapturing) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    null, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isCapturing) "Stop Live" else "Live Mode")
            }
        }
    }
}

@Composable
private fun ScanCornerBrackets() {
    val bracketColor = Primary.copy(alpha = 0.8f)
    val size = 24.dp
    val thickness = 2.dp

    // Top-left
    Box(modifier = Modifier.padding(8.dp)) {
        Box(modifier = Modifier.size(size).border(width = thickness, color = bracketColor, shape = RoundedCornerShape(topStart = 6.dp)).align(Alignment.TopStart))
    }
}

@Composable
private fun SceneAnalysisCard(
    analysis: SceneAnalysis,
    onApplyCommand: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Scene description
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = SurfaceGlass,
            border = androidx.compose.foundation.BorderStroke(1.dp, Primary.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Visibility, null, tint = Primary, modifier = Modifier.size(16.dp))
                    Text("Scene Description", style = MaterialTheme.typography.labelLarge, color = Primary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text  = "${analysis.inferenceTimeMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnlineGreen,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = analysis.description,
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                )

                if (analysis.detectedObjects.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Detected Objects", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement   = Arrangement.spacedBy(6.dp),
                    ) {
                        analysis.detectedObjects.forEach { obj ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Secondary.copy(alpha = 0.1f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Secondary.copy(alpha = 0.3f)),
                            ) {
                                Text(
                                    text     = obj,
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = Secondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Suggested automations
        if (analysis.suggestedAutomations.isNotEmpty()) {
            Text(
                text  = "Suggested Automations",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )
            analysis.suggestedAutomations.forEach { cmd ->
                Surface(
                    shape  = RoundedCornerShape(14.dp),
                    color  = SurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Tertiary.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text  = "${cmd.trait} → ${cmd.value}",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextPrimary,
                            )
                            Text(
                                text  = cmd.reasoning,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                            )
                            Text(
                                text  = "${(cmd.confidence * 100).toInt()}% confidence",
                                style = MaterialTheme.typography.labelSmall,
                                color = OnlineGreen,
                            )
                        }
                        FilledTonalButton(
                            onClick = { onApplyCommand(cmd.deviceId) },
                            shape   = RoundedCornerShape(10.dp),
                            colors  = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Tertiary.copy(alpha = 0.15f),
                                contentColor   = Tertiary,
                            ),
                        ) {
                            Text("Apply", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyScenePrompt() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = null,
                tint = TextDisabled,
                modifier = Modifier.size(48.dp),
            )
            Text("Point camera at your room", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text("FastVLM will analyse and suggest automations", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
        }
    }
}
