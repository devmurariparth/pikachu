package com.example.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.example.AssistantLogger
import com.example.data.AppSettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class VoiceInteractionManager(
    private val context: Context,
    private val onCommandRecognized: (command: String) -> Unit
) : RecognitionListener {

    companion object {
        private const val TAG = "VoiceInteractionManager"
        private const val LISTEN_TIMEOUT_MS = 12_000L
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ttsEngine = AndroidTtsEngine(appContext)

    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    @Volatile
    private var destroyed = false
    private val stateMachine = AssistantStateMachine()

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _liveTranscription = MutableStateFlow("")
    val liveTranscription: StateFlow<String> = _liveTranscription.asStateFlow()

    private val _audioRmsLevel = MutableStateFlow(0f)
    val audioRmsLevel: StateFlow<Float> = _audioRmsLevel.asStateFlow()

    private val _isTtsSpeaking = MutableStateFlow(false)
    val isTtsSpeaking: StateFlow<Boolean> = _isTtsSpeaking.asStateFlow()

    private val _detectedLanguage = MutableStateFlow("en")
    val detectedLanguage: StateFlow<String> = _detectedLanguage.asStateFlow()

    private val timeoutRunnable = Runnable {
        if (listening) {
            AssistantLogger.w(TAG, "Speech recognition timed out")
            stopListeningInternal()
            publishState(VoiceState.Error("Listening timed out. Please try again.", retryable = true))
        }
    }

    init {
        createRecognizerSafely()
    }

    private fun createRecognizerSafely() {
        mainHandler.post {
            if (destroyed) return@post
            createRecognizerOnMainThread()
        }
    }

    private fun createRecognizerOnMainThread() {
        if (destroyed) return
        runCatching {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                publishState(VoiceState.Error("Speech recognition is not available on this device.", retryable = false))
                return
            }
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(this@VoiceInteractionManager)
            }
        }.onFailure {
            AssistantLogger.w(TAG, "Speech recognizer initialization failed safely")
            publishState(VoiceState.Error("Voice input is unavailable right now.", retryable = true))
        }
    }

    fun startListening() {
        if (destroyed) return
        interrupt()
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publishState(VoiceState.Error(
                "Microphone permission is required.",
                retryable = false,
                permissionRequired = true
            ))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            publishState(VoiceState.Error("Speech recognition is not available.", retryable = false))
            return
        }

        mainHandler.post {
            if (destroyed) return@post
            try {
                if (listening) stopListeningInternal()
                if (speechRecognizer == null) createRecognizerOnMainThread()
                val recognizer = speechRecognizer ?: run {
                    publishState(VoiceState.Error("Voice input is unavailable right now.", retryable = true))
                    return@post
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
                    putExtra("android.speech.extra.DICTATION_MODE", true)
                }
                _liveTranscription.value = ""
                _audioRmsLevel.value = 0f
                listening = true
                publishState(VoiceState.Listening())
                recognizer.startListening(intent)
                mainHandler.removeCallbacks(timeoutRunnable)
                mainHandler.postDelayed(timeoutRunnable, LISTEN_TIMEOUT_MS)
            } catch (t: Throwable) {
                listening = false
                publishState(VoiceState.Error("Unable to start voice input. Please try again.", retryable = true))
                AssistantLogger.w(TAG, "Speech start failed safely")
            }
        }
    }

    fun retry() {
        if (_voiceState.value is VoiceState.Error) startListening()
    }

    fun stopListening() {
        mainHandler.post {
            stopListeningInternal()
            if (_voiceState.value is VoiceState.Listening || _voiceState.value is VoiceState.Processing) {
                publishState(VoiceState.Idle)
            }
        }
    }

    fun cancelListening() {
        val cancel = Runnable {
            stopListeningInternal()
            publishState(VoiceState.Cancelled)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cancel.run() else mainHandler.post(cancel)
    }

    private fun stopListeningInternal() {
        mainHandler.removeCallbacks(timeoutRunnable)
        listening = false
        runCatching { speechRecognizer?.cancel() }
        _audioRmsLevel.value = 0f
    }

    fun setProcessingState() {
        publishState(VoiceState.Processing)
    }

    fun setThinkingState() = setProcessingState()

    fun setExecutingState() {
        publishState(VoiceState.Executing)
    }

    fun speak(text: String, languageCode: String = "en", onDone: (() -> Unit)? = null) {
        if (destroyed) return
        if (!AppSettingsManager.isTtsEnabled.value) {
            publishState(VoiceState.Idle)
            onDone?.invoke()
            return
        }
        interrupt()
        _detectedLanguage.value = languageCode
        publishState(VoiceState.Speaking(text))
        _isTtsSpeaking.value = true
        ttsEngine.speak(
            text = text,
            language = languageCode,
            onDone = {
                mainHandler.post {
                    if (destroyed) return@post
                    _isTtsSpeaking.value = false
                    if (_voiceState.value is VoiceState.Speaking) publishState(VoiceState.Idle)
                    onDone?.invoke()
                }
            },
            onError = {
                mainHandler.post {
                    if (destroyed) return@post
                    _isTtsSpeaking.value = false
                    publishState(VoiceState.Error(
                        "Text-to-speech is unavailable for this language on this device.",
                        retryable = false
                    ))
                    onDone?.invoke()
                }
            }
        )
    }

    fun interrupt() {
        runCatching { ttsEngine.stop() }
        _isTtsSpeaking.value = false
    }

    private fun publishState(next: VoiceState) {
        if (!stateMachine.transitionTo(next.toAssistantState())) {
            AssistantLogger.w(TAG, "Ignored illegal assistant state transition")
            return
        }
        _voiceState.value = next
    }

    fun askClarification(prompt: String = "I didn't catch that. Please try again.", language: String = "en") {
        speak(prompt, language) { startListening() }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() {
        if (!listening) return
        publishState(VoiceState.Listening(_liveTranscription.value))
    }

    override fun onRmsChanged(rmsdB: Float) {
        if (destroyed || !listening) return
        _audioRmsLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        if (!listening) return
        listening = false
        mainHandler.removeCallbacks(timeoutRunnable)
        _audioRmsLevel.value = 0f
        publishState(VoiceState.Processing)
    }

    override fun onError(error: Int) {
        if (!listening && error == SpeechRecognizer.ERROR_CLIENT) return
        listening = false
        mainHandler.removeCallbacks(timeoutRunnable)
        _audioRmsLevel.value = 0f
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                publishState(VoiceState.Error("I didn't hear a command. Please try again.", retryable = true))
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                publishState(VoiceState.Error("Microphone permission is required.", retryable = false, permissionRequired = true))
            }
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                publishState(VoiceState.Error("Voice recognition could not reach the speech service.", retryable = true))
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                createRecognizerSafely()
                publishState(VoiceState.Error("Voice input is busy. Please try again.", retryable = true))
            }
            SpeechRecognizer.ERROR_AUDIO -> {
                publishState(VoiceState.Error("Microphone audio failed. Please try again.", retryable = true))
            }
            SpeechRecognizer.ERROR_CLIENT -> {
                publishState(VoiceState.Cancelled)
            }
            else -> {
                publishState(VoiceState.Error("Voice recognition failed. Please try again.", retryable = true))
            }
        }
    }

    override fun onResults(results: Bundle?) {
        if (destroyed || (!listening && _voiceState.value !is VoiceState.Processing)) return
        listening = false
        mainHandler.removeCallbacks(timeoutRunnable)
        _audioRmsLevel.value = 0f
        val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        if (spokenText.isNullOrBlank()) {
            publishState(VoiceState.Error("I didn't catch that. Please try again.", retryable = true))
            return
        }
        _liveTranscription.value = spokenText
        val normalized = LanguageNormalizer.normalize(spokenText)
        _detectedLanguage.value = normalized.language.code
        publishState(VoiceState.Processing)
        onCommandRecognized(spokenText)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (destroyed || !listening) return
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                _liveTranscription.value = it
                publishState(VoiceState.Listening(it))
            }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    fun pause() {
        cancelListening()
        interrupt()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        // Destruction is a lifecycle cleanup event, not a user cancellation.
        // Avoid cancelListening() here because it publishes VoiceState.Cancelled
        // and makes a destroyed manager appear to have an active cancellation.
        mainHandler.removeCallbacksAndMessages(null)
        stopListeningInternal()
        runCatching { ttsEngine.stop() }
        _isTtsSpeaking.value = false
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        ttsEngine.shutdown()
    }
}
