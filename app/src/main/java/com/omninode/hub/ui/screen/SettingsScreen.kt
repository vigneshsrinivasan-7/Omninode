package com.omninode.hub.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.omninode.hub.ui.theme.*
import com.omninode.hub.viewmodel.SettingsViewModel

/**
 * SettingsScreen — Home Assistant connection config, NPU settings, and Office Kit bridge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val haUrl       by viewModel.haUrl.collectAsState()
    val haToken     by viewModel.haToken.collectAsState()
    val npuEnabled  by viewModel.npuEnabled.collectAsState()
    val officeKit   by viewModel.officeKitEnabled.collectAsState()
    val npuDelegate by viewModel.activeDelegate.collectAsState()
    var tokenVisible by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge, color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundVariant),
            )
        },
        bottomBar = { OmniBottomBar(navController) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Home Assistant Config ─────────────────────────────────────────
            SettingsSection(title = "Home Assistant", icon = Icons.Default.Hub) {
                OutlinedTextField(
                    value         = haUrl,
                    onValueChange = { viewModel.setHaUrl(it) },
                    label         = { Text("WebSocket URL") },
                    placeholder   = { Text("ws://homeassistant.local:8123/api/websocket") },
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = RoundedCornerShape(12.dp),
                    colors        = omniTextFieldColors(),
                    leadingIcon   = { Icon(Icons.Default.Link, null, tint = Primary) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value              = haToken,
                    onValueChange      = { viewModel.setHaToken(it) },
                    label              = { Text("Long-Lived Access Token") },
                    modifier           = Modifier.fillMaxWidth(),
                    shape              = RoundedCornerShape(12.dp),
                    colors             = omniTextFieldColors(),
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions    = KeyboardOptions(keyboardType = KeyboardType.Password),
                    leadingIcon        = { Icon(Icons.Default.Key, null, tint = Primary) },
                    trailingIcon       = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null, tint = TextSecondary,
                            )
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                
                val picovoiceKey by viewModel.picovoiceKey.collectAsState()
                OutlinedTextField(
                    value         = picovoiceKey,
                    onValueChange = { viewModel.setPicovoiceKey(it) },
                    label         = { Text("Picovoice Access Key") },
                    placeholder   = { Text("Required for wake word") },
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = RoundedCornerShape(12.dp),
                    colors        = omniTextFieldColors(),
                    leadingIcon   = { Icon(Icons.Default.Mic, null, tint = Primary) },
                )
                Spacer(Modifier.height(8.dp))
                
                Button(
                    onClick = { viewModel.saveAndReconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Background),
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save & Reconnect")
                }
            }

            // ── NPU / AI Config ───────────────────────────────────────────────
            SettingsSection(title = "On-Device AI (QNN / LiteRT)", icon = Icons.Default.Memory) {
                SettingsToggleRow(
                    title    = "Enable Hexagon NPU",
                    subtitle = "Use QNN delegate for Gemma & FastVLM",
                    checked  = npuEnabled,
                    onToggle = { viewModel.setNpuEnabled(it) },
                )
                HorizontalDivider(color = Color(0xFF1A2035), modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Active Delegate", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("Current inference backend", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    val (color, label) = when (npuDelegate) {
                        "QNN_NPU"    -> NpuActiveColor to "Hexagon NPU"
                        "GPU_ADRENO" -> GpuActiveColor to "Adreno 840 GPU"
                        else         -> CpuActiveColor to "Oryon CPU"
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = color.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text     = label,
                            style    = MaterialTheme.typography.labelLarge,
                            color    = color,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // ── Office Kit Bridge ─────────────────────────────────────────────
            SettingsSection(title = "Origin OS 6 Office Kit", icon = Icons.Default.Computer) {
                SettingsToggleRow(
                    title    = "Office Kit Bridge",
                    subtitle = "Sync alerts to PC clipboard · Screen mirroring",
                    checked  = officeKit,
                    onToggle = { viewModel.setOfficeKitEnabled(it) },
                )
                HorizontalDivider(color = Color(0xFF1A2035), modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text  = "When Office Kit is active, OmniNode detects desktop activity and automatically triggers Deep Focus mode: 5000K lighting, muted media, and alerts redirected to PC.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }

            // ── Connectivity ──────────────────────────────────────────────────
            SettingsSection(title = "Resilient Connectivity", icon = Icons.Default.NetworkCheck) {
                SettingsInfoRow("Primary", "Wi-Fi LAN (Matter + WebSocket)", OnlineGreen)
                SettingsInfoRow("Fallback 1", "Wi-Fi Direct (P2P)", Tertiary)
                SettingsInfoRow("Fallback 2", "BLE Mesh (Snapdragon FC 7900)", Secondary)
            }

            // ── About ─────────────────────────────────────────────────────────
            SettingsSection(title = "About OmniNode", icon = Icons.Default.Info) {
                SettingsInfoRow("Version",  "1.0.0 (iQOO Reskilll Hackathon)", TextSecondary)
                SettingsInfoRow("Target SoC", "Snapdragon 8 Elite Gen 5 (SM8850)", Primary)
                SettingsInfoRow("AI Stack", "LiteRT 1.0.1 + QNN Delegate + GenieX", Secondary)
                SettingsInfoRow("Protocol", "Matter 1.3 · HA WebSocket · BLE Mesh", Tertiary)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SurfaceGlass,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3550)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 14.dp),
            ) {
                Icon(icon, null, tint = Primary, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Primary, checkedTrackColor = PrimaryDim,
            ),
        )
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

@Composable
private fun omniTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor    = Primary,
    unfocusedBorderColor  = Color(0xFF2A3550),
    focusedContainerColor = SurfaceGlass,
    unfocusedContainerColor = SurfaceGlass,
    focusedTextColor      = TextPrimary,
    unfocusedTextColor    = TextPrimary,
    focusedLabelColor     = Primary,
    unfocusedLabelColor   = TextSecondary,
    cursorColor           = Primary,
)
