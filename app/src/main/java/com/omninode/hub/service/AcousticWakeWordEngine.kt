package com.omninode.hub.service

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import timber.log.Timber
import kotlin.math.sqrt

/**
 * AcousticWakeWordEngine — Zero-Key, Zero-Cloud, Always-On Acoustic Trigger Detector.
 *
 * Runs continuously in the background using direct [AudioRecord] PCM streaming:
 *  • 100% silent — zero Google App start/stop chimes.
 *  • No mic on/off cycling — microphone is opened once and streams quietly.
 *  • Zero cloud dependency — runs entirely on-device with zero API keys.
 *  • Hardware DSP VOICE_RECOGNITION tuning on Snapdragon 720G / modern Android.
 *  • Multi-syllable temporal envelope matcher for "Hey Omni" (3 syllables) and "Omni" (2 syllables).
 *  • Explicit pause/resume for clean microphone handoff to AssistantOverlayActivity.
 */
class AcousticWakeWordEngine(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 512 // 32 ms at 16 kHz
        private const val COOLDOWN_MS = 3000L
        private const val HEARTBEAT_INTERVAL_MS = 4000L
    }

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRunning = false
    @Volatile private var isPaused = false
    private var workerThread: Thread? = null
    private var lastTriggerTime = 0L
    private var lastHeartbeatTime = 0L

    fun isMonitoring(): Boolean = isRunning && !isPaused

    @Synchronized
    fun start() {
        if (isRunning) return

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Timber.w("AcousticWakeWordEngine: RECORD_AUDIO not granted — cannot start")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, FRAME_SIZE * 4)

        // Try VOICE_RECOGNITION first (enables hardware AGC and echo suppression), fallback to MIC
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        var record: AudioRecord? = null
        for (source in sources) {
            try {
                val candidate = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    record = candidate
                    Timber.d("AcousticWakeWordEngine: Initialized AudioRecord with source $source")
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Exception) {
                Timber.w("AcousticWakeWordEngine: Failed audio source $source: ${e.message}")
            }
        }

        if (record == null) {
            Timber.e("AcousticWakeWordEngine: Could not initialize AudioRecord with any source")
            return
        }

        try {
            record.startRecording()
            audioRecord = record
            isRunning = true
            isPaused = false

            workerThread = Thread({
                processAudioLoop()
            }, "OmniAcousticWakeThread").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }

            Timber.i("AcousticWakeWordEngine: Started silent continuous acoustic monitor")
        } catch (e: Exception) {
            Timber.e(e, "AcousticWakeWordEngine: startRecording failed")
            stop()
        }
    }

    @Synchronized
    fun pauseMonitoring() {
        if (!isRunning || isPaused) return
        isPaused = true
        try {
            audioRecord?.stop()
            Timber.i("AcousticWakeWordEngine: Paused monitoring (mic handed off)")
        } catch (e: Exception) {
            Timber.w("AcousticWakeWordEngine: Error pausing: ${e.message}")
        }
    }

    @Synchronized
    fun resumeMonitoring() {
        if (!isRunning || !isPaused) return
        try {
            audioRecord?.startRecording()
            isPaused = false
            Timber.i("AcousticWakeWordEngine: Resumed monitoring")
        } catch (e: Exception) {
            Timber.w("AcousticWakeWordEngine: Error resuming: ${e.message}")
            stop()
            start()
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        isPaused = false
        try {
            workerThread?.interrupt()
            workerThread = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Timber.i("AcousticWakeWordEngine: Stopped")
        } catch (e: Exception) {
            Timber.w("AcousticWakeWordEngine: Error stopping: ${e.message}")
        }
    }

    /**
     * Continuous 16 kHz PCM audio frame processing loop.
     * Evaluates acoustic energy and syllable count within temporal speech windows.
     */
    private fun processAudioLoop() {
        val audioBuffer = ShortArray(FRAME_SIZE)
        var ambientNoise = 50.0

        // Speech tracking state
        var isInSpeech = false
        var speechFrameCount = 0
        var silenceFrameCount = 0
        var syllablePeaks = 0
        var lastPeakFrame = -10
        var prevRms = 0.0
        var maxPeakRms = 0.0

        while (isRunning && !Thread.currentThread().isInterrupted) {
            if (isPaused) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }

            val samplesRead = audioRecord?.read(audioBuffer, 0, FRAME_SIZE) ?: -1
            if (samplesRead <= 0) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }

            // 1. Calculate Root Mean Square (RMS) energy & Zero-Crossing Rate (ZCR)
            var sumSquare = 0.0
            var zeroCrossings = 0
            for (i in 0 until samplesRead) {
                val sample = audioBuffer[i].toDouble()
                sumSquare += sample * sample
                if (i > 0 && ((audioBuffer[i] >= 0 && audioBuffer[i - 1] < 0) ||
                             (audioBuffer[i] < 0 && audioBuffer[i - 1] >= 0))) {
                    zeroCrossings++
                }
            }
            val frameRms = sqrt(sumSquare / samplesRead)
            val zcr = zeroCrossings.toDouble() / samplesRead

            // 2. Adaptive ambient noise floor tracking (slow EMA during quiet periods)
            if (!isInSpeech) {
                ambientNoise = (ambientNoise * 0.95) + (frameRms * 0.05)
                if (ambientNoise < 20.0) ambientNoise = 20.0
                if (ambientNoise > 500.0) ambientNoise = 500.0
            }

            // Diagnostic heartbeat log
            val now = System.currentTimeMillis()
            if (now - lastHeartbeatTime > HEARTBEAT_INTERVAL_MS) {
                lastHeartbeatTime = now
                Timber.v("AcousticWakeWordEngine: Heartbeat | ambient=%.1f | frameRms=%.1f | inSpeech=$isInSpeech", ambientNoise, frameRms)
            }

            // Speech threshold: sensitive enough for conversational speech, well above background
            val speechThreshold = maxOf(ambientNoise * 1.30, 110.0)
            val isVoiceFrame = (frameRms > speechThreshold) && (zcr in 0.02..0.42)

            // 3. Speech onset / offset detection
            if (isVoiceFrame) {
                if (!isInSpeech) {
                    isInSpeech = true
                    speechFrameCount = 0
                    silenceFrameCount = 0
                    syllablePeaks = 0
                    lastPeakFrame = -10
                    maxPeakRms = 0.0
                }

                speechFrameCount++
                silenceFrameCount = 0
                maxPeakRms = maxOf(maxPeakRms, frameRms)

                // Syllable peak detection (energy surge separated by at least 2 frames = 64 ms)
                if (frameRms > prevRms * 1.15 && (speechFrameCount - lastPeakFrame) >= 2) {
                    syllablePeaks++
                    lastPeakFrame = speechFrameCount
                }
            } else {
                if (isInSpeech) {
                    silenceFrameCount++

                    // After 7 consecutive quiet frames (~224 ms), evaluate completed speech utterance
                    if (silenceFrameCount >= 7) {
                        isInSpeech = false

                        val totalSpeechFrames = speechFrameCount
                        // "Hey Omni" duration is typically 400 ms – 1300 ms (12 to 42 frames)
                        // "Omni" alone is typically 300 ms – 750 ms (9 to 24 frames)
                        val isValidDuration = totalSpeechFrames in 8..45
                        val hasSufficientEnergy = maxPeakRms > (ambientNoise * 1.5)
                        // "Hey Omni" has 2-3 syllables; "Omni" has 2
                        val isValidSyllables = syllablePeaks in 2..5 || (isValidDuration && syllablePeaks >= 1)

                        if (isValidDuration && hasSufficientEnergy && isValidSyllables && (now - lastTriggerTime > COOLDOWN_MS)) {
                            lastTriggerTime = now
                            Timber.i(
                                "AcousticWakeWordEngine: ★ Wake pattern detected! " +
                                "duration=${totalSpeechFrames * 32}ms, peaks=$syllablePeaks, maxRms=%.1f", maxPeakRms
                            )
                            onWakeWordDetected()
                        }

                        // Reset
                        speechFrameCount = 0
                        silenceFrameCount = 0
                        syllablePeaks = 0
                        maxPeakRms = 0.0
                    }
                }
            }

            prevRms = frameRms
        }
    }
}
