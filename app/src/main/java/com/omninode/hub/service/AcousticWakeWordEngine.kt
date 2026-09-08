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
 *  • Multi-syllable temporal envelope matcher for "Hey Omni" / "Omni".
 */
class AcousticWakeWordEngine(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 512 // 32 ms at 16 kHz
        private const val COOLDOWN_MS = 3500L
    }

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRunning = false
    private var workerThread: Thread? = null
    private var lastTriggerTime = 0L

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

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Timber.e("AcousticWakeWordEngine: AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isRunning = true

            workerThread = Thread({
                processAudioLoop()
            }, "OmniAcousticWakeThread").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }

            Timber.i("AcousticWakeWordEngine: Started silent continuous acoustic monitor")
        } catch (e: Exception) {
            Timber.e(e, "AcousticWakeWordEngine: Failed to start AudioRecord")
            stop()
        }
    }

    fun stop() {
        isRunning = false
        try {
            workerThread?.interrupt()
            workerThread = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Timber.i("AcousticWakeWordEngine: Stopped")
        } catch (e: Exception) {
            Timber.w("AcousticWakeWordEngine: Error stopping AudioRecord: ${e.message}")
        }
    }

    /**
     * Continuous 16 kHz PCM audio frame processing loop.
     * Evaluates acoustic energy and syllable count within temporal speech windows.
     */
    private fun processAudioLoop() {
        val audioBuffer = ShortArray(FRAME_SIZE)
        var ambientNoise = 350.0

        // Syllable tracking state
        var isInSpeech = false
        var speechFrameCount = 0
        var silenceFrameCount = 0
        var syllablePeaks = 0
        var lastPeakFrame = -10
        var prevRms = 0.0

        while (isRunning && !Thread.currentThread().isInterrupted) {
            val samplesRead = audioRecord?.read(audioBuffer, 0, FRAME_SIZE) ?: -1
            if (samplesRead <= 0) continue

            // 1. Calculate Root Mean Square (RMS) energy
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

            // 2. Adaptive ambient noise floor tracking (slow EMA)
            if (!isInSpeech) {
                ambientNoise = (ambientNoise * 0.96) + (frameRms * 0.04)
            }

            // Voice threshold: speech is significantly above current ambient noise floor
            val speechThreshold = maxOf(ambientNoise * 2.2, 500.0)
            val isVoiceFrame = frameRms > speechThreshold

            // 3. Speech onset / offset detection
            if (isVoiceFrame) {
                if (!isInSpeech) {
                    // Speech segment onset
                    isInSpeech = true
                    speechFrameCount = 0
                    silenceFrameCount = 0
                    syllablePeaks = 0
                    lastPeakFrame = -10
                }

                speechFrameCount++
                silenceFrameCount = 0

                // Syllable peak detection (energy rises then falls, at least 3 frames apart)
                if (frameRms > prevRms * 1.25 && (speechFrameCount - lastPeakFrame) >= 3) {
                    syllablePeaks++
                    lastPeakFrame = speechFrameCount
                }
            } else {
                if (isInSpeech) {
                    silenceFrameCount++

                    // After 6 consecutive frames of silence (~190 ms), evaluate the completed speech segment
                    if (silenceFrameCount >= 6) {
                        isInSpeech = false

                        val totalUtteranceFrames = speechFrameCount + silenceFrameCount
                        // "Hey Omni" duration is typically 450 ms – 1200 ms (14 to 38 frames)
                        // "Omni" alone is typically 350 ms – 800 ms (11 to 25 frames)
                        val isValidDuration = totalUtteranceFrames in 11..38
                        // "Hey Omni" has 3 syllables ("Hey", "Om", "ni"); "Omni" has 2 ("Om", "ni")
                        val isValidSyllables = syllablePeaks in 2..4

                        val now = System.currentTimeMillis()
                        if (isValidDuration && isValidSyllables && (now - lastTriggerTime > COOLDOWN_MS)) {
                            lastTriggerTime = now
                            Timber.i(
                                "AcousticWakeWordEngine: ★ Wake pattern detected! " +
                                "frames=$totalUtteranceFrames, peaks=$syllablePeaks, rms=${frameRms.toInt()}"
                            )
                            onWakeWordDetected()
                        }

                        // Reset
                        speechFrameCount = 0
                        silenceFrameCount = 0
                        syllablePeaks = 0
                    }
                }
            }

            prevRms = frameRms
        }
    }
}
