package com.omninode.hub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * OmniNodeApp — Application entry point.
 *
 * Responsibilities:
 *  • Initialises Hilt dependency injection graph.
 *  • Plants Timber logging tree (debug: full stack traces, release: crash-only).
 *  • Creates the persistent notification channels required by the foreground
 *    IoT orchestration daemon (OmniNodeForegroundService).
 */
@HiltAndroidApp
class OmniNodeApp : Application() {

    companion object {
        const val CHANNEL_ID_IOT_DAEMON      = "omninode_iot_daemon"
        const val CHANNEL_ID_ALERTS          = "omninode_alerts"
        const val CHANNEL_ID_AI_INFERENCE    = "omninode_ai_inference"
    }

    override fun onCreate() {
        super.onCreate()

        // ── Logging ──────────────────────────────────────────────────────────
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // ── Notification Channels (required Android 8+) ───────────────────────
        createNotificationChannels()

        Timber.i("OmniNode Hub v${BuildConfig.APP_VERSION} initialised — NPU_ENABLED=${BuildConfig.ENABLE_NPU}")
    }

    /**
     * Creates all notification channels used by OmniNode.
     * Channels are idempotent — safe to call on every cold start.
     */
    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return

        listOf(
            NotificationChannel(
                CHANNEL_ID_IOT_DAEMON,
                "IoT Orchestration",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent OmniNode foreground service for smart-home control"
                setShowBadge(false)
            },

            NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Smart Living Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Security events, anomaly detections, and agentic automation alerts"
                enableVibration(true)
            },

            NotificationChannel(
                CHANNEL_ID_AI_INFERENCE,
                "On-Device AI",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "NPU / LiteRT inference status (debug channel)"
                setShowBadge(false)
            }
        ).forEach { channel -> nm.createNotificationChannel(channel) }
    }
}
