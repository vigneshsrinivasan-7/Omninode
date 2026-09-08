package com.omninode.hub.service

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                           result != TextToSpeech.LANG_NOT_SUPPORTED
                if (ttsReady) {
                    Timber.i("TtsManager: TextToSpeech engine ready")
                } else {
                    Timber.w("TtsManager: TTS language not supported on this device")
                }
            } else {
                Timber.e("TtsManager: TextToSpeech initialization failed (status=$status)")
            }
        }
    }

    /**
     * Speaks [text] through the device speaker using the TTS engine.
     * Safe to call from any thread. No-op if TTS is not ready.
     */
    fun speak(text: String) {
        if (!ttsReady) {
            Timber.w("TtsManager: TTS not ready — skipping speech: '$text'")
            return
        }
        Timber.d("TtsManager: TTS speaking: '$text'")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "omni_utterance_${System.currentTimeMillis()}")
    }

    fun shutdown() {
        tts?.shutdown()
        ttsReady = false
    }
}
