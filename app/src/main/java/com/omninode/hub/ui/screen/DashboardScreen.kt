package com.omninode.hub.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.omninode.hub.data.model.AutomationPreset
import com.omninode.hub.data.model.ConnectivityMode
import com.omninode.hub.data.model.SmartDevice
import com.omninode.hub.data.model.DeviceType
import com.omninode.hub.data.model.TraitType
import com.omninode.hub.ui.navigation.Screen
import com.omninode.hub.ui.theme.*
import com.omninode.hub.viewmodel.DashboardViewModel

/**
 * DashboardScreen — The primary home screen of OmniNode.
 *
 * Layout sections:
 *  1. Top bar      — App title, connectivity badge, NPU status indicator
 *  2. Hero section — Current connectivity mode + quick stats row
 *  3. Automation presets — Horizontal scrollable quick-action cards
 *  4. Active devices — Room-grouped device tiles with live state
 *  5. Bottom nav bar — Navigation to all 5 screens
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val structure     by viewModel.structure.collectAsState()
    val connectivity  by viewModel.connectivityMode.collectAsState()
    val wsStatus      by viewModel.wsConnectionStatus.collectAsState()
    val npuDelegate   by viewModel.npuDelegate.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = Background,
        bottomBar = { OmniBottomBar(navController) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // ── Top Bar ───────────────────────────────────────────────────────
            item {
                DashboardTopBar(
                    connectivityMode = connectivity,
                    wsConnected      = wsStatus,
                    npuDelegate      = npuDelegate,
                    onSettingsTap    = { navController.navigate(Screen.Settings.route) },
                )
            }

            // ── Hero Stats Row ────────────────────────────────────────────────
            item {
                HeroStatsRow(
                    deviceCount = structure?.rooms?.sumOf { it.devices.size } ?: 0,
                    onlineCount = structure?.rooms?.sumOf { r ->
                        r.devices.count { it.isOnline }
                    } ?: 0,
                    roomCount   = structure?.rooms?.size ?: 0,
                )
            }

            // ── Voice Assistant Hero Card ────────────────────────────────────
            item { Spacer(Modifier.height(4.dp)) }
            item {
                VoiceAssistantHeroCard(
                    onClick = {
                        val intent = Intent(context, com.omninode.hub.AssistantOverlayActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    }
                )
            }

            // ── Automation Presets ────────────────────────────────────────────
            item {
                SectionHeader(title = "Quick Automations", icon = Icons.Default.AutoAwesome)
            }
            item {
                AutomationPresetRow(
                    onPresetSelected = { preset -> viewModel.triggerPreset(preset) }
                )
            }

            // ── AI Quick Actions ──────────────────────────────────────────────
            item { Spacer(Modifier.height(8.dp)) }
            item {
                AiQuickActions(
                    onAgentTap  = { navController.navigate(Screen.Agent.route) },
                    onVisionTap = { navController.navigate(Screen.Vision.route) },
                )
            }

            // ── Devices by Room ───────────────────────────────────────────────
            item {
                SectionHeader(title = "Rooms & Devices", icon = Icons.Default.MeetingRoom)
            }

            val rooms = structure?.rooms ?: emptyList()
            items(rooms) { room ->
                RoomCard(
                    roomName = room.name,
                    devices  = room.devices,
                    onDeviceToggle = { device, on ->
                        viewModel.toggleDevice(device, on)
                    },
                )
                Spacer(Modifier.height(12.dp))
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Top Bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DashboardTopBar(
    connectivityMode: ConnectivityMode,
    wsConnected: Boolean,
    npuDelegate: String,
    onSettingsTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(BackgroundVariant, Background)
                )
            )
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text  = "OmniNode",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            brush = Brush.linearGradient(GradientCyanViolet),
                            fontWeight = FontWeight.ExtraBold,
                        ),
                    )
                    Text(
                        text  = "Smart Living Hub",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // NPU delegate badge
                    NpuBadge(delegate = npuDelegate)
                    IconButton(onClick = onSettingsTap) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Connectivity strip
            ConnectivityStrip(mode = connectivityMode, wsConnected = wsConnected)
        }
    }
}

@Composable
private fun NpuBadge(delegate: String) {
    val (color, label) = when (delegate) {
        "QNN_NPU"    -> NpuActiveColor to "NPU"
        "GPU_ADRENO" -> GpuActiveColor to "GPU"
        else         -> CpuActiveColor to "CPU"
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun ConnectivityStrip(mode: ConnectivityMode, wsConnected: Boolean) {
    val (modeColor, modeLabel, modeIcon) = when (mode) {
        ConnectivityMode.FULL_WIFI    -> Triple(OnlineGreen, "Full Wi-Fi", Icons.Default.Wifi)
        ConnectivityMode.LOCAL_ONLY   -> Triple(WarnAmber,   "Local Only", Icons.Default.WifiFind)
        ConnectivityMode.WIFI_DIRECT  -> Triple(Tertiary,    "Wi-Fi Direct P2P", Icons.Default.DeviceHub)
        ConnectivityMode.BLE_MESH     -> Triple(Secondary,   "BLE Mesh Fallback", Icons.Default.Bluetooth)
        ConnectivityMode.OFFLINE      -> Triple(AlertRed,    "Offline", Icons.Default.WifiOff)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = modeColor.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(1.dp, modeColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = modeIcon,
                    contentDescription = null,
                    tint = modeColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text  = modeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = modeColor,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (wsConnected) OnlineGreen else OfflineGray)
                )
                Text(
                    text  = if (wsConnected) "HA WebSocket" else "HA Disconnected",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (wsConnected) OnlineGreen else OfflineGray,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Hero Stats Row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeroStatsRow(deviceCount: Int, onlineCount: Int, roomCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(value = "$roomCount",       label = "Rooms",   color = Primary,    modifier = Modifier.weight(1f))
        StatCard(value = "$deviceCount",     label = "Devices", color = Secondary,  modifier = Modifier.weight(1f))
        StatCard(value = "$onlineCount",     label = "Online",  color = OnlineGreen,modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(14.dp),
        color    = SurfaceGlass,
        border   = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.2f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text  = value,
                style = MaterialTheme.typography.displaySmall.copy(
                    color = color,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 28.sp,
                ),
            )
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Automation Presets
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AutomationPresetRow(onPresetSelected: (AutomationPreset) -> Unit) {
    LazyRow(
        contentPadding  = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(AutomationPreset.entries.filter { it != AutomationPreset.CUSTOM }) { preset ->
            PresetChip(preset = preset, onClick = { onPresetSelected(preset) })
        }
    }
}

@Composable
private fun PresetChip(preset: AutomationPreset, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "chip_scale",
    )

    val gradient = when (preset) {
        AutomationPreset.READING_MODE  -> GradientCyan
        AutomationPreset.DEEP_FOCUS    -> GradientViolet
        AutomationPreset.CINEMA_MODE   -> listOf(Color(0xFF1A1A2E), Color(0xFF16213E))
        AutomationPreset.GOOD_MORNING  -> listOf(Color(0xFFFF9800), Color(0xFFFF5722))
        AutomationPreset.AWAY_MODE     -> listOf(Color(0xFF546E7A), Color(0xFF37474F))
        else                           -> GradientCyanViolet
    }

    Surface(
        modifier = Modifier
            .width(140.dp)
            .scale(scale)
            .clickable {
                pressed = true
                onClick()
            },
        shape  = RoundedCornerShape(16.dp),
        color  = SurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, gradient.first().copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(gradient)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text  = preset.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = preset.description.substringBefore("•").trim(),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 2,
            )
        }
    }

    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(150)
            pressed = false
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Voice Assistant Hero Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun VoiceAssistantHeroCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = SurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, Primary.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(GradientCyanViolet)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column {
                    Text(
                        text = "Voice Assistant",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                    )
                    Text(
                        text = "Say \"Hey Omni\" or tap to speak",
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Primary.copy(alpha = 0.15f),
            ) {
                Text(
                    text = "Listening ●",
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  AI Quick Actions
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AiQuickActions(onAgentTap: () -> Unit, onVisionTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AiActionCard(
            title    = "Ask Gemma",
            subtitle = "Natural language control",
            icon     = Icons.Default.SmartToy,
            gradient = GradientViolet,
            onClick  = onAgentTap,
            modifier = Modifier.weight(1f),
        )
        AiActionCard(
            title    = "Scene Vision",
            subtitle = "FastVLM camera analysis",
            icon     = Icons.Default.Visibility,
            gradient = GradientCyan,
            onClick  = onVisionTap,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AiActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .height(90.dp)
            .clickable(onClick = onClick),
        shape  = RoundedCornerShape(16.dp),
        color  = SurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, gradient.first().copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(gradient)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column {
                Text(
                    text  = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                )
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 2,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Room Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RoomCard(
    roomName: String,
    devices: List<SmartDevice>,
    onDeviceToggle: (SmartDevice, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape  = RoundedCornerShape(18.dp),
        color  = SurfaceGlass,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3550)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Room header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.MeetingRoom,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text  = roomName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                    )
                    Text(
                        text  = "${devices.size} devices",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = TextSecondary,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter   = fadeIn() + slideInVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    devices.forEach { device ->
                        DeviceTile(
                            device    = device,
                            onToggle  = { on -> onDeviceToggle(device, on) },
                        )
                        if (device != devices.last()) {
                            HorizontalDivider(
                                color     = Color(0xFF1A2035),
                                thickness = 1.dp,
                                modifier  = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceTile(
    device: SmartDevice,
    onToggle: (Boolean) -> Unit,
) {
    val isOn = device.traits[TraitType.ON_OFF] as? Boolean ?: false
    val brightness = device.traits[TraitType.BRIGHTNESS] as? Int

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Device type icon in colored circle
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (isOn) Primary.copy(alpha = 0.15f) else Color(0xFF1A2035)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = deviceIcon(device.type),
                    contentDescription = null,
                    tint = if (isOn) Primary else OfflineGray,
                    modifier = Modifier.size(18.dp),
                )
            }

            Column {
                Text(
                    text  = device.name,
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                )
                Text(
                    text  = buildDeviceSubtitle(device, isOn, brightness),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (device.isOnline) TextSecondary else OfflineGray,
                )
            }
        }

        if (device.type.isToggleable()) {
            Switch(
                checked  = isOn,
                onCheckedChange = onToggle,
                colors   = SwitchDefaults.colors(
                    checkedThumbColor  = Primary,
                    checkedTrackColor  = PrimaryDim,
                    uncheckedThumbColor = OfflineGray,
                    uncheckedTrackColor = Color(0xFF1A2035),
                ),
            )
        } else {
            // For sensors — show state as chip
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (device.isOnline) OnlineGreen.copy(0.1f) else OfflineGray.copy(0.1f),
            ) {
                Text(
                    text     = if (device.isOnline) "Online" else "Offline",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = if (device.isOnline) OnlineGreen else OfflineGray,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Section Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text  = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Bottom Navigation Bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun OmniBottomBar(navController: NavController) {
    val items = listOf(
        Triple(Screen.Dashboard, Icons.Default.Home,       "Home"),
        Triple(Screen.Devices,   Icons.Default.Devices,    "Devices"),
        Triple(Screen.Agent,     Icons.Default.SmartToy,   "Agent"),
        Triple(Screen.Vision,    Icons.Default.Visibility, "Vision"),
        Triple(Screen.Settings,  Icons.Default.Settings,   "Settings"),
    )

    val backstackEntry = navController.currentBackStackEntryAsState()
    val currentRoute   = backstackEntry.value?.destination?.route

    NavigationBar(
        containerColor = BackgroundVariant,
        tonalElevation = 0.dp,
        modifier = Modifier.border(
            width = 1.dp,
            color = Color(0xFF1A2035),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ),
    ) {
        items.forEach { (screen, icon, label) ->
            val selected = currentRoute == screen.route
            NavigationBarItem(
                selected = selected,
                onClick  = {
                    navController.navigate(screen.route) {
                        popUpTo(Screen.Dashboard.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) Primary.copy(alpha = 0.15f) else Color.Transparent
                            )
                            .padding(8.dp),
                    ) {
                        Icon(
                            imageVector     = icon,
                            contentDescription = label,
                            modifier        = Modifier.size(22.dp),
                        )
                    }
                },
                label = {
                    Text(
                        text  = label,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor   = Primary,
                    selectedTextColor   = Primary,
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary,
                    indicatorColor      = Color.Transparent,
                ),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Utility helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun deviceIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.DIMMABLE_LIGHT,
    DeviceType.COLOR_TEMPERATURE_LIGHT,
    DeviceType.FULL_COLOR_LIGHT      -> Icons.Default.LightMode
    DeviceType.ON_OFF_PLUG           -> Icons.Default.Power
    DeviceType.CONTACT_SENSOR        -> Icons.Default.Sensors
    DeviceType.OCCUPANCY_SENSOR      -> Icons.Default.PersonSearch
    DeviceType.THERMOSTAT            -> Icons.Default.Thermostat
    DeviceType.MEDIA_PLAYER          -> Icons.Default.Tv
    DeviceType.SMART_LOCK            -> Icons.Default.Lock
    DeviceType.CAMERA                -> Icons.Default.Camera
    DeviceType.ROBOTIC_VACUUM        -> Icons.Default.CleaningServices
    DeviceType.UNKNOWN               -> Icons.Default.DeviceUnknown
}

private fun DeviceType.isToggleable(): Boolean = this in setOf(
    DeviceType.DIMMABLE_LIGHT,
    DeviceType.COLOR_TEMPERATURE_LIGHT,
    DeviceType.FULL_COLOR_LIGHT,
    DeviceType.ON_OFF_PLUG,
    DeviceType.MEDIA_PLAYER,
    DeviceType.SMART_LOCK,
    DeviceType.ROBOTIC_VACUUM,
)

private fun buildDeviceSubtitle(device: SmartDevice, isOn: Boolean, brightness: Int?): String {
    if (!device.isOnline) return "Offline"
    return buildString {
        append(if (isOn) "On" else "Off")
        if (isOn && brightness != null) append(" · $brightness%")
        val kelvin = device.traits[TraitType.COLOR_TEMPERATURE] as? Int
        if (isOn && kelvin != null) append(" · ${kelvin}K")
    }
}
