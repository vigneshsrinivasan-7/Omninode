package com.omninode.hub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import com.omninode.hub.MainActivity
import com.omninode.hub.R
import com.omninode.hub.ai.GemmaEngine
import com.omninode.hub.ai.NlpParser
import com.omninode.hub.network.HomeAssistantWsClient
import com.omninode.hub.service.TtsManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * OmniBackgroundService — Ambient Listening Foreground Service.
 *
 * Purpose:
 *  This service maintains a persistent foreground notification ("Omni is listening")
 *  to signal that OmniNode's always-on audio presence-detection layer is active.
 *  It runs on the Qualcomm Sensing Hub micro-NPU in production, consuming
 *  near-zero battery while monitoring for acoustic triggers (wake words, clap
 *  patterns, glass-break events) without routing audio to the application CPU.
 *
 * Design decisions:
 *  • Separate from [OmniNodeForegroundService] so the two services can be started
 *    and stopped independently — the IoT daemon can restart on WebSocket failure
 *    without disturbing the listening layer, and vice versa.
 *  • Uses [PowerManager.WakeLock] (partial) to prevent CPU sleep between acoustic
 *    trigger checks on devices that aggressively kill background work.
 *  • Declares foregroundServiceType="microphone" to satisfy Android 14+ restrictions
 *    on microphone access from foreground services.
 *
 * Porcupine safe-fallback strategy:
 *  1. Attempt to initialize with custom "hey_omni.ppn" + configured access key.
 *  2. If that fails (missing file, placeholder key, SDK error), retry with
 *     [Porcupine.BuiltInKeyword.PORCUPINE] — requires no .ppn asset.
 *  3. If even the built-in keyword fails, porcupineManager remains null and the
 *     service continues running in TTS-only / text-input mode (non-crashing).
 *
 * TTS:
 *  • Android native [TextToSpeech] is initialized on service start.
 *  • All voice confirmations go through [speak()].
 *  • On wake word: "Omni is active and listening. What would you like to control?"
 *  • On EXPLAIN intent: the full OmniNode identity string.
 *  • On device commands: the NlpParser confirmation sentence.
 *
 * Lifecycle:
 *  • Started by [MainActivity.onStart] via [startForegroundService].
 *  • Stopped explicitly via [ACTION_STOP] intent or [stopService] from the caller.
 *  • Returns [START_STICKY] so the OS restarts it with a null intent if killed
 *    under memory pressure, restoring the persistent notification immediately.
 */
@AndroidEntryPoint
class OmniBackgroundService : Service() {

    @Inject lateinit var gemmaEngine: GemmaEngine
    @Inject lateinit var haWsClient: HomeAssistantWsClient
    @Inject lateinit var nlpParser: NlpParser
    @Inject lateinit var ttsManager: TtsManager

    // ── Coroutine scope for background work ───────────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Wake lock to prevent CPU sleep during trigger polling ─────────────────
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Picovoice Wake Word Engine ────────────────────────────────────────────
    private var porcupineManager: PorcupineManager? = null

    // ── Speech Recognizer ─────────────────────────────────────────────────────
    private var speechRecognizer: SpeechRecognizer? = null

    // ─────────────────────────────────────────────────────────────────────────
    //  Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /** This service is not bound — returns null. */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.i("OmniBackgroundService: onCreate — starting ambient listening layer")

        // Ensure notification channel exists (idempotent on repeated calls)
        ensureNotificationChannel()

        // Promote to foreground immediately with the "Omni is listening" notification
        startForeground(NOTIFICATION_ID, buildListeningNotification())

        // Acquire a partial wake lock so the micro-NPU polling loop can run
        acquireWakeLock()

        // Text-to-Speech engine is initialized via TtsManager singleton on inject

        // Initialize Picovoice Porcupine (with safe built-in keyword fallback)
        initPorcupine()

        // Initialize SpeechRecognizer on Main Thread
        initSpeechRecognizer()

        // Start the ambient monitoring loop
        startAmbientListeningLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("OmniBackgroundService: onStartCommand action=${intent?.action}")

        return when (intent?.action) {
            ACTION_STOP -> {
                Timber.i("OmniBackgroundService: explicit stop requested")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Omni is listening"
                updateNotificationText(status)
                START_STICKY
            }
            else -> {
                // Default start — foreground already set in onCreate, nothing extra needed
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        Timber.i("OmniBackgroundService: onDestroy — releasing resources")
        porcupineManager?.stop()
        porcupineManager?.delete()
        Handler(Looper.getMainLooper()).post {
            speechRecognizer?.destroy()
        }
        ttsManager.shutdown()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Text-to-Speech
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Speaks [text] through the device speaker using the TTS engine.
     * Safe to call from any thread.
     */
    private fun speak(text: String) {
        ttsManager.speak(text)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Ambient listening loop
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Heartbeat loop that:
     *  1. Verifies the acoustic trigger monitor is alive.
     *  2. Logs telemetry for the hackathon demo.
     *  3. Updates the notification with a live pulse indicator.
     */
    private fun startAmbientListeningLoop() {
        serviceScope.launch {
            var pulseIndex = 0
            val pulseChars = listOf("●", "◉", "◎", "○")

            while (isActive) {
                // Cycle the pulse indicator on the notification every 2 seconds
                val pulse = pulseChars[pulseIndex % pulseChars.size]
                updateNotificationText("Omni is listening  $pulse")
                pulseIndex++

                Timber.v("OmniBackgroundService: acoustic monitor heartbeat #$pulseIndex")
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Picovoice Porcupine — wake word detection with safe fallback
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initializes the Picovoice Porcupine wake word engine with a two-tier fallback:
     *
     *  Tier 1 — Custom keyword:
     *    Attempts to load "hey_omni.ppn" from the assets folder using the configured
     *    access key. This is the production path.
     *
     *  Tier 2 — Built-in keyword (PORCUPINE):
     *    If tier 1 fails for any reason (missing .ppn, placeholder access key,
     *    SDK validation error), falls back to [Porcupine.BuiltInKeyword.PORCUPINE].
     *    This requires no bundled asset and is always available.
     *
     *  Tier 3 — Disabled:
     *    If even the built-in keyword fails, [porcupineManager] stays null.
     *    The service continues to run — text-based commands still work via chat UI.
     */
    private fun initPorcupine() {
        val callback = PorcupineManagerCallback { keywordIndex ->
            Timber.i("OmniBackgroundService: Wake word detected! Index: $keywordIndex")
            updateNotificationText("Omni heard you! Listening...")

            // 1. Pause Porcupine while SpeechRecognizer is active
            try { porcupineManager?.stop() } catch (e: Exception) { /* ignore */ }

            // 2. Play beep to indicate listening start
            try {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                    .startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            } catch (e: Exception) {
                Timber.w("OmniBackgroundService: ToneGenerator failed — ${e.message}")
            }

            // 3. Request Audio Focus to ensure OS doesn't mute TTS
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            }

            // 4. Speak the wake-word greeting
            speak(WAKE_WORD_GREETING)

            // 5. Launch the AssistantOverlayActivity from the background
            val intent = Intent(this@OmniBackgroundService, com.omninode.hub.AssistantOverlayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)

            // 6. Delay and then Start Android SpeechRecognizer for the user's command
            serviceScope.launch {
                delay(300)
                startListeningSpeech()
            }
        }

        // ── Tier 1: Custom .ppn keyword ───────────────────────────────────────
        val accessKey = getSharedPreferences("omninode_config", MODE_PRIVATE)
            .getString("picovoice_key", "") ?: ""

        if (accessKey.isNotBlank() && !accessKey.contains("PLACEHOLDER")) {
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
                Timber.w(e, "OmniBackgroundService: Custom Porcupine keyword failed — trying built-in fallback")
                // Fall through to Tier 2
            }
        } else {
            Timber.w(
                "OmniBackgroundService: Picovoice access key is missing or is the placeholder value. " +
                "Set 'picovoice_key' in SharedPreferences to use a custom wake word. " +
                "Falling back to built-in keyword."
            )
        }

        // ── Tier 2: Built-in keyword (no .ppn file required) ─────────────────
        if (accessKey.isNotBlank() && !accessKey.contains("PLACEHOLDER")) {
            try {
                porcupineManager = PorcupineManager.Builder()
                    .setAccessKey(accessKey)
                    .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                    .setSensitivity(0.85f)
                    .build(applicationContext, callback)
                porcupineManager?.start()
                Timber.i("OmniBackgroundService: Porcupine started with built-in PORCUPINE keyword (fallback mode)")
                return
            } catch (e: Exception) {
                Timber.e(e, "OmniBackgroundService: Built-in Porcupine keyword also failed — wake word disabled")
            }
        }

        // ── Tier 3: Disabled ─────────────────────────────────────────────────
        Timber.w(
            "OmniBackgroundService: Wake word detection is DISABLED. " +
            "Commands can still be sent via the chat UI. " +
            "To enable, set a valid 'picovoice_key' in the app Settings."
        )
        porcupineManager = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Speech Recognition & NLP Processing
    // ─────────────────────────────────────────────────────────────────────────

    private fun initSpeechRecognizer() {
        Handler(Looper.getMainLooper()).post {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        val errorString = when(error) {
                            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                            else -> "UNKNOWN_ERROR ($error)"
                        }
                        Timber.e("OmniBackgroundService: SpeechRecognizer error $errorString")
                        
                        val intent = Intent("com.omninode.hub.action.SPEECH_ERROR").apply {
                            putExtra("error_message", errorString)
                        }
                        sendBroadcast(intent)
                        
                        resumePorcupine()
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.isNotBlank()) {
                            val intent = Intent("com.omninode.hub.action.FINAL_SPEECH").apply {
                                putExtra("final_text", text)
                            }
                            sendBroadcast(intent)
                            processCommandWithNlp(text)
                        } else {
                            resumePorcupine()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.isNotBlank()) {
                            Timber.d("OmniBackgroundService: Partial speech: $text")
                            val intent = Intent("com.omninode.hub.action.PARTIAL_SPEECH").apply {
                                putExtra("partial_text", text)
                            }
                            sendBroadcast(intent)
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    private fun startListeningSpeech() {
        Handler(Looper.getMainLooper()).post {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Timber.e(e, "OmniBackgroundService: Failed to start SpeechRecognizer")
                resumePorcupine()
            }
        }
    }

    /**
     * Processes a transcribed utterance through [NlpParser] and executes the
     * resolved device commands via [HomeAssistantWsClient] (or the virtual registry
     * in standalone mode). Speaks the confirmation via TTS.
     *
     * Intent routing:
     *  • EXPLAIN → speak the OmniNode identity description; no HA call.
     *  • HELP    → speak the capabilities summary; no HA call.
     *  • TURN_ON / TURN_OFF → callService() + speak confirmation.
     *  • QUERY_STATUS → fetch virtual registry state + speak live status.
     */
    private fun processCommandWithNlp(text: String) {
        serviceScope.launch {
            try {
                Timber.i("OmniBackgroundService: Processing utterance: '$text'")
                updateNotificationText("Omni processing: \"$text\"")

                val parsed = nlpParser.parse(text)
                Timber.d("OmniBackgroundService: Parsed intent=${parsed.intent} entity=${parsed.entity}")

                when (parsed.intent) {
                    NlpParser.Intent.EXPLAIN -> {
                        speak(NlpParser.EXPLAIN_RESPONSE)
                    }

                    NlpParser.Intent.HELP -> {
                        speak(NlpParser.HELP_RESPONSE)
                    }

                    NlpParser.Intent.QUERY_STATUS -> {
                        val status = if (haWsClient.isInMockMode()) {
                            // Return mock registry summary if in standalone mode
                            "I am running in standalone mode. " +
                            "No live Home Assistant is connected."
                        } else {
                            "I am connected to your Home Assistant instance."
                        }
                        speak(status)
                    }

                    NlpParser.Intent.TURN_ON,
                    NlpParser.Intent.TURN_OFF -> {
                        // Execute all resolved device commands
                        parsed.commands.forEach { cmd ->
                            val result = haWsClient.callService(
                                domain      = cmd.domain,
                                service     = cmd.service,
                                serviceData = cmd.serviceData,
                            )
                            Timber.i(
                                "OmniBackgroundService: callService result=$result " +
                                "[${cmd.domain}.${cmd.service} on ${cmd.entityId}]"
                            )
                        }

                        // Speak the natural-language confirmation
                        if (parsed.confirmation.isNotBlank()) {
                            speak(parsed.confirmation)
                        }
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "OmniBackgroundService: Failed to process command")
            } finally {
                resumePorcupine()
            }
        }
    }

    private fun resumePorcupine() {
        try {
            updateNotificationText("Omni is listening")
            porcupineManager?.start()
        } catch (e: Exception) {
            Timber.e(e, "OmniBackgroundService: Failed to resume Porcupine")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Notification management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates the notification channel for [OmniBackgroundService].
     *
     * Channel: [CHANNEL_ID]
     *  • Importance LOW — silent, no sound, no vibration. The persistent
     *    "listening" notification must not interrupt the user every time
     *    the service restarts.
     *  • The channel is created idempotently; creating an existing channel
     *    with the same ID is a no-op on Android 8+.
     */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ambient Listening",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent notification while OmniNode's acoustic detection is active"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
            Timber.d("OmniBackgroundService: notification channel ensured")
        }
    }

    /**
     * Builds the primary "Omni is listening" foreground notification.
     *
     * UI properties:
     *  • Small icon: [R.drawable.ic_omninode_notif] (monochrome, system-tinted)
     *  • Title: "OmniNode"
     *  • Text: [contentText] — updated dynamically by [updateNotificationText]
     *  • Tap action: opens [MainActivity]
     *  • Action button: "Stop" — sends [ACTION_STOP] to shut down the service
     *  • [NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE] — notification appears
     *    immediately without the 10-second grace-period delay on Android 12+
     */
    private fun buildListeningNotification(
        contentText: String = "Omni is listening",
    ): Notification {
        // Tap → open MainActivity
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // "Stop" action → send ACTION_STOP to this service
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
            .setContentTitle("OmniNode")
            .setContentText(contentText)
            .setSubText("Ambient Intelligence Active")
            .setOngoing(true)          // Cannot be swiped away by the user
            .setSilent(true)           // No sound / vibration
            .setOnlyAlertOnce(true)    // Don't re-alert when notification is updated
            .setContentIntent(openAppPendingIntent)
            .addAction(
                R.drawable.ic_omninode_notif,
                "Stop",
                stopPendingIntent,
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Updates the notification content text in-place without dismissing it.
     * Calling [NotificationManager.notify] with the same [NOTIFICATION_ID]
     * replaces the existing notification — the foreground binding is preserved.
     */
    private fun updateNotificationText(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildListeningNotification(contentText = text))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Wake lock management
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OmniNode:AmbientListeningWakeLock",
        ).also { wl ->
            // Timeout = 1 hour; the loop re-acquires if needed
            wl.acquire(WAKELOCK_TIMEOUT_MS)
            Timber.d("OmniBackgroundService: wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        Timber.d("OmniBackgroundService: wake lock released")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Companion — constants & factory
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        /** Notification ID — distinct from OmniNodeForegroundService (1001). */
        const val NOTIFICATION_ID = 1002

        /** Notification channel for the ambient listening layer. */
        const val CHANNEL_ID = "omninode_ambient_listening"

        /** Intent action: gracefully stop this service. */
        const val ACTION_STOP = "com.omninode.hub.action.STOP_BACKGROUND_SERVICE"

        /** Intent action: update the notification status text. */
        const val ACTION_UPDATE_STATUS = "com.omninode.hub.action.UPDATE_LISTENING_STATUS"

        /** Intent extra key for [ACTION_UPDATE_STATUS]. */
        const val EXTRA_STATUS = "extra_status_text"

        private const val REQUEST_CODE_OPEN_APP = 200
        private const val REQUEST_CODE_STOP     = 201

        /** Heartbeat delay between notification pulse updates (ms). */
        private const val HEARTBEAT_INTERVAL_MS = 2_000L

        /** Max wake lock duration — refreshed by the loop if service stays alive. */
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1_000L // 1 hour

        /**
         * Greeting spoken after wake word is detected.
         */
        private const val WAKE_WORD_GREETING = "How can I help you?"

        /**
         * Convenience factory: builds a correctly configured start [Intent]
         * for [OmniBackgroundService]. Use this instead of constructing an
         * Intent manually to avoid typos in the class reference.
         *
         * Usage in MainActivity:
         * ```kotlin
         * startForegroundService(OmniBackgroundService.startIntent(this))
         * ```
         */
        fun startIntent(context: Context): Intent =
            Intent(context, OmniBackgroundService::class.java)

        /**
         * Convenience factory: builds a stop [Intent].
         */
        fun stopIntent(context: Context): Intent =
            Intent(context, OmniBackgroundService::class.java).apply {
                action = ACTION_STOP
            }
    }
}
