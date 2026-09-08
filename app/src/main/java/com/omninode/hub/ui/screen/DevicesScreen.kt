package com.omninode.hub.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.omninode.hub.data.model.DeviceType
import com.omninode.hub.data.model.SmartDevice
import com.omninode.hub.ui.theme.*
import com.omninode.hub.viewmodel.DevicesViewModel

/**
 * DevicesScreen — Full device list organized by room with Matter commissioning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    navController: NavController,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val structure by viewModel.structure.collectAsState()

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Devices", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        Text(
                            "${structure?.rooms?.sumOf { it.devices.size } ?: 0} total devices",
                            style = MaterialTheme.typography.labelSmall, color = TextSecondary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = Primary)
                    }
                    IconButton(onClick = { viewModel.startCommissioning() }) {
                        Icon(Icons.Default.Add, "Add device", tint = Primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundVariant),
            )
        },
        bottomBar = { OmniBottomBar(navController) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            structure?.rooms?.forEach { room ->
                item {
                    Text(
                        text     = room.name,
                        style    = MaterialTheme.typography.titleMedium,
                        color    = TextPrimary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                items(room.devices) { device ->
                    DeviceDetailCard(device = device, onToggle = { viewModel.toggleDevice(device, it) })
                }
            }

            item {
                CommissionNewDeviceCard(onClick = { viewModel.startCommissioning() })
            }
        }
    }
}

@Composable
private fun DeviceDetailCard(device: SmartDevice, onToggle: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SurfaceGlass,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A3550)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(deviceIcon(device.type), null, tint = Primary, modifier = Modifier.size(24.dp))
                Column {
                    Text(device.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    Text(
                        "${device.type.name.replace("_", " ")} · ${device.protocolSource.name}",
                        style = MaterialTheme.typography.labelSmall, color = TextSecondary,
                    )
                }
            }
            Switch(
                checked = device.traits[com.omninode.hub.data.model.TraitType.ON_OFF] as? Boolean ?: false,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Primary, checkedTrackColor = PrimaryDim,
                ),
            )
        }
    }
}

@Composable
private fun CommissionNewDeviceCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape   = RoundedCornerShape(14.dp),
        color   = PrimaryDim,
        border  = androidx.compose.foundation.BorderStroke(1.dp, Primary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Add, null, tint = Primary, modifier = Modifier.size(22.dp))
            Column {
                Text("Commission New Device", style = MaterialTheme.typography.titleSmall, color = Primary)
                Text("Matter QR code or NFC pairing", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}

private fun deviceIcon(type: DeviceType) = when (type) {
    DeviceType.DIMMABLE_LIGHT,
    DeviceType.COLOR_TEMPERATURE_LIGHT,
    DeviceType.FULL_COLOR_LIGHT       -> Icons.Default.LightMode
    DeviceType.ON_OFF_PLUG            -> Icons.Default.Power
    DeviceType.CONTACT_SENSOR         -> Icons.Default.Sensors
    DeviceType.OCCUPANCY_SENSOR       -> Icons.Default.PersonSearch
    DeviceType.THERMOSTAT             -> Icons.Default.Thermostat
    DeviceType.MEDIA_PLAYER           -> Icons.Default.Tv
    DeviceType.SMART_LOCK             -> Icons.Default.Lock
    DeviceType.CAMERA                 -> Icons.Default.Camera
    DeviceType.ROBOTIC_VACUUM         -> Icons.Default.CleaningServices
    DeviceType.UNKNOWN                -> Icons.Default.DeviceUnknown
}
