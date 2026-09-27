package com.example.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.AssistantLogger
import java.util.Locale

interface TtsEngine {
    fun speak(text: String, language: String, onDone: () -> Unit = {}, onError: () -> Unit = {})
    fun stop()
    fun shutdown()
    fun isReady(): Boolean
}

class AndroidTtsEngine(context: Context) : TtsEngine, TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var onDone: () -> Unit = {}
    private var onError: () -> Unit = {}

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        tts?.setSpeechRate(1.02f)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = onDone()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onError()
        })
    }

    override fun speak(text: String, language: String, onDone: () -> Unit, onError: () -> Unit) {
        this.onDone = onDone
        this.onError = onError
        if (!ready || text.isBlank()) { onError(); return }
        try {
            val requested = when (language.lowercase()) {
                "gu", "gu-in" -> Locale("gu", "IN")
                "hi", "hi-in" -> Locale("hi", "IN")
                else -> Locale.ENGLISH
            }
            val availability = tts?.isLanguageAvailable(requested) ?: TextToSpeech.LANG_NOT_SUPPORTED
            val selected = if (availability >= TextToSpeech.LANG_AVAILABLE) requested else Locale.ENGLISH
            tts?.language = selected
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
            }
            if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID) != TextToSpeech.SUCCESS) onError()
        } catch (t: Throwable) {
            AssistantLogger.w(TAG, "TTS failed safely")
            onError()
        }
    }

    override fun stop() { runCatching { tts?.stop() } }
    override fun shutdown() { runCatching { tts?.stop(); tts?.shutdown() }; tts = null; ready = false }
    override fun isReady(): Boolean = ready

    companion object {
        private const val TAG = "TtsEngine"
        private const val UTTERANCE_ID = "mj_tts"
    }
}
