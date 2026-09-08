package com.omninode.hub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.home.matter.Matter
import com.google.android.gms.home.matter.commissioning.CommissioningRequest
import com.omninode.hub.service.OmniBackgroundService
import com.omninode.hub.service.OmniNodeForegroundService
import com.omninode.hub.ui.navigation.OmniNodeNavHost
import com.omninode.hub.ui.theme.Background
import com.omninode.hub.ui.theme.OmniNodeTheme
import com.omninode.hub.ui.theme.Primary
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import android.Manifest

/**
 * MainActivity — Single Activity hosting the entire Jetpack Compose UI.
 *
 * Responsibilities:
 *  • Installs the splash screen.
 *  • Requests all runtime permissions.
 *  • Starts the OmniNodeForegroundService IoT daemon.
 *  • Hosts the Compose NavHost.
 *  • Owns the [commissioningLauncher] ActivityResultLauncher so the OS can
 *    deliver the Matter commissioning result back to the correct Activity lifecycle.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // ─── Matter Commissioning Result Launcher ────────────────────────────────
    //
    // Google Home SDK commissioning flow:
    //   1. Call Matter.getCommissioningClient(activity).commissionDevice(request)
    //      which returns a Task<IntentSender>.
    //   2. Wrap the IntentSender in an IntentSenderRequest and hand it to this
    //      launcher — the Google Home app (or its embedded SDK activity) takes
    //      over the pairing UX.
    //   3. This callback is invoked with RESULT_OK on success or RESULT_CANCELED
    //      (or an error extras bundle) on failure.
    //
    // The launcher must be registered here (Activity scope) because it uses
    // StartIntentSenderForResult which requires a live Activity.
    private val commissioningLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result: ActivityResult ->
            handleCommissioningResult(result)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ── Request System Alert Window (Overlay) Permission for Assistant Overlay ──
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        setContent {
            OmniNodeTheme {
                PermissionGate {
                    MatterCommissioningHost(
                        onPairDeviceClick = { launchCommissioning() },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()

        // ── 1. IoT Orchestration daemon (WebSocket + Matter + LiteRT) ─────────
        try {
            val iotIntent = Intent(this, OmniNodeForegroundService::class.java)
            startForegroundService(iotIntent)
            Timber.d("OmniNodeForegroundService start requested")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start OmniNodeForegroundService")
        }

        // ── 2. Ambient Listening service ("Omni is listening" notification) ───
        try {
            startForegroundService(OmniBackgroundService.startIntent(this))
            Timber.d("OmniBackgroundService start requested")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start OmniBackgroundService")
        }
    }

    // ─── Matter Commissioning ────────────────────────────────────────────────

    /**
     * Builds a [CommissioningRequest] and calls the Google Home SDK commissioning
     * client. On success the SDK returns an [android.content.IntentSender] which
     * we dispatch to [commissioningLauncher] to start the system pairing UX.
     *
     * [CommissioningRequest.builder().build()] with no arguments targets the
     * Google Home ecosystem (Thread/Matter/Wi-Fi commissioning window).
     * Pass a custom onboardingPayload string for QR-code or manual-code flows.
     */
    private fun launchCommissioning() {
        Timber.i("Matter commissioning requested")

        val request = CommissioningRequest.builder()
            // Uncomment and set a real 11-digit Matter manual pairing code or
            // QR-code payload string when pairing a specific device:
            // .setOnboardingPayload("MT:Y.K90SO527JA0648G00")
            .build()

        Matter.getCommissioningClient(this)
            .commissionDevice(request)
            .addOnSuccessListener { intentSender ->
                Timber.d("CommissioningClient returned IntentSender — launching pairing UI")
                commissioningLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener { exception ->
                Timber.e(exception, "CommissioningClient.commissionDevice() failed")
            }
    }

    /**
     * Called by [commissioningLauncher] when the Matter pairing Activity finishes.
     */
    private fun handleCommissioningResult(result: ActivityResult) {
        when (result.resultCode) {
            RESULT_OK -> {
                Timber.i("Matter commissioning succeeded — device paired")
                // The new device will appear automatically via MatterController.observeStructure()
                // which listens to the Google Home SDK structure updates.
            }
            RESULT_CANCELED -> {
                Timber.w("Matter commissioning cancelled by user")
            }
            else -> {
                Timber.e("Matter commissioning failed with resultCode=${result.resultCode}")
            }
        }
    }
}

// ─── Root Compose Host ───────────────────────────────────────────────────────

/**
 * Root host composable that layers the main nav graph with a floating
 * "Pair Device" button that always accessible regardless of current screen.
 *
 * The button triggers Matter device commissioning via the Google Home SDK.
 */
@Composable
private fun MatterCommissioningHost(onPairDeviceClick: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { _ ->
        // ── Main navigation graph ─────────────────────────────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            OmniNodeNavHost()

            // ── "Pair Device" FAB-style button ────────────────────────────
            // Pinned to the top-end corner so it doesn't interfere with the
            // existing bottom navigation bar from OmniBottomBar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 56.dp)
                    .align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.End,
            ) {
                PairDeviceButton(
                    onClick = {
                        onPairDeviceClick()
                        scope.launch {
                            snackbarHostState.showSnackbar("Opening Matter pairing…")
                        }
                    }
                )
            }
        }
    }
}

/**
 * "Pair Device" button styled to match the OmniNode Neon Edge design system.
 *
 * Tapping this button calls [Matter.getCommissioningClient] in [MainActivity]
 * and launches the system-level Matter commissioning flow via [CommissioningRequest].
 */
@Composable
fun PairDeviceButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = Background,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 8.dp,
            pressedElevation = 2.dp,
        ),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Pair Matter device",
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Pair Device",
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

// ─── Permission Gate ─────────────────────────────────────────────────────────

/**
 * Requests all runtime permissions required by OmniNode before rendering content.
 * Uses Accompanist Permissions for Compose-idiomatic permission flow.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    )

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    // Render the main UI regardless; individual screens handle graceful degradation
    content()
}
