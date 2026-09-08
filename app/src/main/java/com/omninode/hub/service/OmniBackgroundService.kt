package com.omninode.hub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import com.omninode.hub.AssistantOverlayActivity
import com.omninode.hub.MainActivity
import com.omninode.hub.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * OmniBackgroundService — Ambient Intelligence Foreground Service.
 *
 * Responsibilities:
 *  • Always-on, 100% silent acoustic wake-word detection for "Hey Omni" / "Omni".
 *  • Zero Google chime loops and zero mic on/off toggling (uses direct AudioRecord).
 *  • Correct FOREGROUND_SERVICE_TYPE_MICROPHONE declaration for Android 14+ / targetSdk 35.
 *  • Displays persistent status notification and high-priority heads-up wake overlay.
 *  • Clean microphone handoff to [AssistantOverlayActivity].
 */
@AndroidEntryPoint
class OmniBackgroundService : Service() {

    @Inject lateinit var ttsManager: TtsManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var porcupineManager: PorcupineManager? = null
    private var acousticEngine: AcousticWakeWordEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.i("OmniBackgroundService: onCreate — initializing ambient presence")

        ensureNotificationChannels()

        // Declare FOREGROUND_SERVICE_TYPE_MICROPHONE for Android 10+ / 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildListeningNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildListeningNotification())
        }

        acquireWakeLock()

        // 1. Try Porcupine if key is configured
        initPorcupine()

        // 2. Fallback to native silent continuous AudioRecord engine (Zero Key, Always-On)
        if (porcupineManager == null) {
            initAcousticEngine()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("OmniBackgroundService: onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                Timber.i("OmniBackgroundService: stop requested")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_VOICE -> {
                Timber.i("OmniBackgroundService: voice trigger requested")
                launchAssistantOverlay()
                return START_STICKY
            }
            ACTION_RESUME_LISTENING -> {
                Timber.i("OmniBackgroundService: resume acoustic monitoring requested")
                acousticEngine?.resumeMonitoring()
                return START_STICKY
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Omni is listening for 'Hey Omni'"
                updateNotificationText(status)
                return START_STICKY
            }
        }

        // On any general start (e.g. after permissions granted), ensure acoustic engine is actively running
        if (porcupineManager == null) {
            if (acousticEngine == null) {
                initAcousticEngine()
            } else if (!acousticEngine!!.isMonitoring()) {
                acousticEngine?.start()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("OmniBackgroundService: onDestroy — tearing down")
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
            porcupineManager = null
        } catch (e: Exception) { /* ignore */ }

        try {
            acousticEngine?.stop()
            acousticEngine = null
        } catch (e: Exception) { /* ignore */ }

        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Acoustic Wake Word Engine (Silent AudioRecord, Zero-Key, No Google Chimes)
    // ─────────────────────────────────────────────────────────────────────────

    private fun initAcousticEngine() {
        acousticEngine?.stop()
        acousticEngine = AcousticWakeWordEngine(this) {
            Timber.i("OmniBackgroundService: ★ 'Hey Omni' detected by acoustic engine!")
            launchAssistantOverlay()
        }
        acousticEngine?.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Picovoice Porcupine (Used if configured)
    // ─────────────────────────────────────────────────────────────────────────

    private fun initPorcupine() {
        val accessKey = getSharedPreferences("omninode_config", MODE_PRIVATE)
            .getString("picovoice_key", "") ?: ""

        if (accessKey.isBlank() || accessKey.contains("PLACEHOLDER")) {
            Timber.i("OmniBackgroundService: Picovoice key not set — using on-device AcousticWakeWordEngine")
            porcupineManager = null
            return
        }

        val callback = PorcupineManagerCallback { keywordIndex ->
            Timber.i("OmniBackgroundService: Porcupine wake word detected! Index: $keywordIndex")
            launchAssistantOverlay()
        }

        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath("hey_omni.ppn")
                .setSensitivity(0.85f)
                .build(applicationContext, callback)
            porcupineManager?.start()
            Timber.i("OmniBackgroundService: Porcupine started with custom hey_omni.ppn keyword")
            return
        } catch (e: Exception) {
            Timber.w(e, "OmniBackgroundService: Custom keyword hey_omni.ppn not found — falling back to PORCUPINE")
        }

        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                .setSensitivity(0.85f)
                .build(applicationContext, callback)
            porcupineManager?.start()
            Timber.i("OmniBackgroundService: Porcupine started with built-in PORCUPINE keyword")
        } catch (e: Exception) {
            Timber.e(e, "OmniBackgroundService: Porcupine built-in keyword failed")
            porcupineManager = null
        }
    }

    /**
     * Wakes up Omni:
     *  1. Immediately pauses background AudioRecord to yield microphone hardware cleanly.
     *  2. Plays distinct, crisp wake beep (120 ms).
     *  3. Fires high-priority Full-Screen Intent + startActivity so MIUI pops up the overlay over any app.
     */
    private fun launchAssistantOverlay() {
        // 1. Pause background acoustic monitor so SpeechRecognizer has exclusive microphone access
        acousticEngine?.pauseMonitoring()

        // 2. Play pleasant, crisp wake tone
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 90)
                .startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Exception) { /* ignore */ }

        // 3. Build full-screen intent so MIUI pops up the overlay over any app or lock screen
        val overlayIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            2005,
            overlayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val headsUpNotif = NotificationCompat.Builder(this, CHANNEL_HEADS_UP_ID)
            .setSmallIcon(R.drawable.ic_omninode_notif)
            .setContentTitle("OmniNode Assistant")
            .setContentText("Listening for your command...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID + 1, headsUpNotif)

        // 4. Also call direct startActivity
        try {
            startActivity(overlayIntent)
        } catch (e: Exception) {
            Timber.e(e, "OmniBackgroundService: startActivity failed — fullScreenIntent will handle popup")
        }

        // 5. Watchdog: Auto-resume acoustic monitoring after 6 seconds if overlay is blocked or dismissed
        serviceScope.launch {
            kotlinx.coroutines.delay(6000)
            if (acousticEngine != null && !acousticEngine!!.isMonitoring()) {
                Timber.w("OmniBackgroundService: Watchdog auto-resuming acoustic monitoring")
                acousticEngine?.resumeMonitoring()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Notification Management
    // ─────────────────────────────────────────────────────────────────────────

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // 1. Low-priority ambient listening channel (silent, unobtrusive)
            val ambientChannel = NotificationChannel(
                CHANNEL_ID,
                "OmniNode Ambient Presence",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "OmniNode hands-free acoustic trigger and voice assistant"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm?.createNotificationChannel(ambientChannel)

            // 2. High-priority heads-up channel for wake popup
            val headsUpChannel = NotificationChannel(
                CHANNEL_HEADS_UP_ID,
                "OmniNode Voice Trigger Popup",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Pops up assistant overlay when 'Hey Omni' is spoken"
                setShowBadge(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm?.createNotificationChannel(headsUpChannel)
        }
    }

    private fun buildListeningNotification(
        contentText: String = "Omni is listening for 'Hey Omni'",
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // "Stop" action → terminates background service
        val stopIntent = Intent(this, OmniBackgroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_omninode_notif)
            .setContentTitle("OmniNode Hub")
            .setContentText(contentText)
            .setSubText("Hands-Free Voice")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                R.drawable.ic_omninode_notif,
                "Stop Service",
                stopPendingIntent,
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotificationText(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildListeningNotification(contentText = text))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Wake lock
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OmniNode:AmbientListeningWakeLock",
        ).also { wl ->
            wl.acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "omninode_ambient_listening_v3"
        const val CHANNEL_HEADS_UP_ID = "omninode_heads_up_voice_v3"

        const val ACTION_STOP = "com.omninode.hub.action.STOP_BACKGROUND_SERVICE"
        const val ACTION_UPDATE_STATUS = "com.omninode.hub.action.UPDATE_LISTENING_STATUS"
        const val ACTION_TRIGGER_VOICE = "com.omninode.hub.action.TRIGGER_VOICE"
        const val ACTION_RESUME_LISTENING = "com.omninode.hub.action.RESUME_LISTENING"
        const val EXTRA_STATUS = "extra_status_text"

        private const val REQUEST_CODE_OPEN_APP = 200
        private const val REQUEST_CODE_STOP     = 201

        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1_000L

        fun startIntent(context: Context): Intent =
            Intent(context, OmniBackgroundService::class.java)

        fun resumeIntent(context: Context): Intent =
            Intent(context, OmniBackgroundService::class.java).apply {
                action = ACTION_RESUME_LISTENING
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, OmniBackgroundService::class.java).apply {
                action = ACTION_STOP
            }
    }
}
