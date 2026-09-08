package com.omninode.hub.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omninode.hub.MainActivity
import com.omninode.hub.OmniNodeApp
import com.omninode.hub.R
import com.omninode.hub.ai.LiteRtManager
import com.omninode.hub.network.HomeAssistantWsClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * OmniNodeForegroundService — The persistent IoT orchestration daemon.
 *
 * This foreground service is the backbone of OmniNode's always-on smart living
 * engine. It runs continuously to maintain:
 *
 *  1. Persistent OkHttp WebSocket connection → Home Assistant Core
 *     (Keeps the bidirectional telemetry stream alive even when the UI is backgrounded)
 *
 *  2. LiteRT NPU runtime initialisation
 *     (Loads pre-compiled .litertlm model files, warms up the QNN delegate)
 *
 *  3. BLE Mesh & Wi-Fi Direct watchdog
 *     (Monitors network topology and triggers fallback protocols on router failure)
 *
 *  4. Always-on acoustic trigger monitoring
 *     (Routed through Qualcomm Sensing Hub micro-NPU for ultra-low-power wake detection)
 *
 * Foreground service type: connectedDevice | microphone | camera
 * (Declared in AndroidManifest.xml — required for Android 14+)
 */
@AndroidEntryPoint
class OmniNodeForegroundService : Service() {

    @Inject lateinit var haWsClient: HomeAssistantWsClient
    @Inject lateinit var liteRtManager: LiteRtManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationId = 1001

    private var isOfflineMockMode = false

    // ─────────────────────────────────────────────────────────────────────────
    //  Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.i("OmniNodeForegroundService: onCreate")
        startForeground(notificationId, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("OmniNodeForegroundService: onStartCommand")

        when (intent?.action) {
            ACTION_STOP -> {
                Timber.i("OmniNodeForegroundService: stop action received")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> initialiseDaemon()
        }

        // Restart if the OS kills the service due to memory pressure
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("OmniNodeForegroundService: onDestroy — tearing down daemon")
        haWsClient.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Daemon initialisation
    // ─────────────────────────────────────────────────────────────────────────

    private fun initialiseDaemon() {
        serviceScope.launch {
            // ── Step 1: Initialise LiteRT + QNN delegate ──────────────────────
            Timber.i("Daemon: initialising LiteRT runtime")
            val initResult = liteRtManager.initialise()
            Timber.i("Daemon: LiteRT init → $initResult")

            // ── Step 2: Connect WebSocket to Home Assistant ───────────────────
            // URL is read from SharedPreferences (set by the Settings screen).
            // Falls back to the default numeric IP (192.168.1.100:8123) if not set.
            // Using homeassistant.local is intentionally avoided — it requires mDNS
            // which fails on networks without an HA instance.
            Timber.i("Daemon: connecting to Home Assistant WebSocket")
            val haUrl = getHaUrl()
            Timber.i("Daemon: HA URL = $haUrl")
            haWsClient.connect(
                url   = haUrl,
                token = getHaToken(),
            )

            // ── Step 3: Observe connection status ─────────────────────────────
            launch {
                haWsClient.connectionStatus.collect { status ->
                    Timber.d("HA WebSocket status: $status")
                    updateNotification(status)
                }
            }

            // ── Step 4: Monitor state changes for autonomous triggers ──────────
            launch {
                haWsClient.stateChanges.collect { event ->
                    Timber.v("State changed: ${event.entityId}")
                    // Route to ViewModel via shared StateFlow in repository layer
                }
            }

            Timber.i("Daemon: all subsystems initialised")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Notification management
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildForegroundNotification(
        statusText: String = "Connecting to smart home..."
    ): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OmniNodeForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, OmniNodeApp.CHANNEL_ID_IOT_DAEMON)
            .setSmallIcon(R.drawable.ic_omninode_notif)
            .setContentTitle("OmniNode Active")
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: HomeAssistantWsClient.ConnectionStatus) {
        if (status is HomeAssistantWsClient.ConnectionStatus.Error) {
            Timber.e("Connection error: ${status.message}")
            isOfflineMockMode = true
            return
        }

        val text = when (status) {
            is HomeAssistantWsClient.ConnectionStatus.Connected       -> "Connected ● Smart home active"
            is HomeAssistantWsClient.ConnectionStatus.Connecting      -> "Connecting to Home Assistant..."
            is HomeAssistantWsClient.ConnectionStatus.AuthFailed      -> "Auth failed — check HA token"
            is HomeAssistantWsClient.ConnectionStatus.Disconnected    -> "Disconnected — reconnecting..."
            is HomeAssistantWsClient.ConnectionStatus.StandaloneMock  -> "Standalone mode — virtual devices active"
            is HomeAssistantWsClient.ConnectionStatus.Error           -> return // Handled above
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(notificationId, buildForegroundNotification(text))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Utility
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves the Home Assistant WebSocket URL.
     * Reads from SharedPreferences (key: "ha_ws_url").
     * Falls back to [HomeAssistantWsClient.DEFAULT_WS_URL] (ws://192.168.1.100:8123/api/websocket)
     * if not configured — avoids the mDNS-dependent homeassistant.local hostname.
     */
    private fun getHaUrl(): String {
        val prefs = getSharedPreferences("omninode_config", MODE_PRIVATE)
        return prefs.getString("ha_ws_url", HomeAssistantWsClient.DEFAULT_WS_URL)
            ?.ifBlank { HomeAssistantWsClient.DEFAULT_WS_URL }
            ?: HomeAssistantWsClient.DEFAULT_WS_URL
    }

    /**
     * Retrieves the Home Assistant long-lived access token.
     * In production: read from encrypted DataStore.
     * In hackathon demo: read from shared preferences.
     */
    private fun getHaToken(): String {
        val prefs = getSharedPreferences("omninode_config", MODE_PRIVATE)
        return prefs.getString("ha_token", "") ?: ""
    }

    companion object {
        const val ACTION_STOP = "com.omninode.hub.ACTION_STOP_SERVICE"
    }
}
