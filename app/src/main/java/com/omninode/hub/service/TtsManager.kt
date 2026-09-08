package com.omninode.hub.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Configure audio attributes to play through media speaker at normal volume
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)

                // Try Locale.US -> default -> Locale.ENGLISH
                var langResult = tts?.setLanguage(Locale.US)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    langResult = tts?.setLanguage(Locale.getDefault())
                }
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    langResult = tts?.setLanguage(Locale.ENGLISH)
                }

                ttsReady = langResult != TextToSpeech.LANG_MISSING_DATA &&
                           langResult != TextToSpeech.LANG_NOT_SUPPORTED

                Timber.i("TtsManager: TextToSpeech initialized (ready=$ttsReady, defaultEngine=${tts?.defaultEngine})")
            } else {
                Timber.e("TtsManager: TextToSpeech initialization failed (status=$status)")
            }
        }
    }

    /**
     * Speaks [text] through the device speaker using the TTS engine.
     * Safe to call from any thread.
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) return

        val engine = tts
        if (engine == null) {
            Timber.w("TtsManager: TTS engine instance is null — reinitializing")
            initTts()
            return
        }

        val utteranceId = "omni_utterance_${System.currentTimeMillis()}"

        if (onComplete != null) {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) onComplete()
                }
                override fun onError(id: String?) {
                    if (id == utteranceId) onComplete()
                }
            })
        }

        Timber.i("TtsManager: Speaking: '$text'")
        val params = Bundle()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
