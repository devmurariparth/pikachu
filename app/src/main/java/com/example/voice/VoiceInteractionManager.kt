package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.AssistantLogger
import com.example.data.AppSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

sealed class VoiceState {
    object Idle : VoiceState()
    data class Listening(val isWakeWordActive: Boolean = false) : VoiceState()
    object Thinking : VoiceState()
    data class Speaking(val text: String) : VoiceState()
    data class Clarifying(val question: String) : VoiceState()
    data class Error(val message: String, val isPermissionError: Boolean = false) : VoiceState()
}

class VoiceInteractionManager(
    private val context: Context,
    private val onCommandRecognized: (command: String, wasWakeWordTriggered: Boolean) -> Unit
) : RecognitionListener, TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VoiceInteractionManager"
        private const val UTTERANCE_ID_RESPONSE = "mj_tts_response"
        private const val UTTERANCE_ID_CLARIFICATION = "mj_tts_clarification"
        private const val WAKE_PHRASE_REGEX = "(?i)\\b(hey\\s+mj|ok\\s+mj|okay\\s+mj|hi\\s+mj|mj)\\b"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

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

    private var isContinuousListeningActive = false
    private var pendingAfterTtsAction: (() -> Unit)? = null
    private var isCurrentlyListening = false

    init {
        initSpeechRecognizer()
        initTextToSpeech()
    }

    private fun initSpeechRecognizer() {
        mainHandler.post {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    speechRecognizer?.destroy()
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(this@VoiceInteractionManager)
                    }
                    AssistantLogger.i(TAG, "SpeechRecognizer initialized successfully")
                } else {
                    AssistantLogger.w(TAG, "Speech recognition is not available on this device")
                }
            } catch (e: Exception) {
                AssistantLogger.e(TAG, "Failed to initialize SpeechRecognizer", e)
            }
        }
    }

    private fun initTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "Failed to initialize TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            textToSpeech?.let { tts ->
                tts.language = Locale.getDefault()
                tts.setSpeechRate(1.02f) // slightly brisk and conversational
                tts.setPitch(1.0f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        mainHandler.post {
                            _isTtsSpeaking.value = true
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            _isTtsSpeaking.value = false
                            val afterAction = pendingAfterTtsAction
                            pendingAfterTtsAction = null

                            if (afterAction != null) {
                                afterAction.invoke()
                            } else if (AppSettingsManager.isContinuousConversation.value && isContinuousListeningActive) {
                                // Natural conversational delay before re-opening listening
                                scope.launch {
                                    delay(350L)
                                    if (_voiceState.value !is VoiceState.Speaking) {
                                        startListeningInternal(isWakeWordMode = false)
                                    }
                                }
                            } else {
                                if (_voiceState.value is VoiceState.Speaking) {
                                    _voiceState.value = VoiceState.Idle
                                }
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            _isTtsSpeaking.value = false
                            if (_voiceState.value is VoiceState.Speaking) {
                                _voiceState.value = VoiceState.Idle
                            }
                        }
                    }
                })
            }
            AssistantLogger.i(TAG, "TextToSpeech initialized successfully")
        } else {
            isTtsReady = false
            AssistantLogger.w(TAG, "TextToSpeech initialization failed with status: $status")
        }
    }

    /**
     * Start listening for voice input.
     * @param isWakeWordMode if true, expects wake phrase "Hey MJ"
     */
    fun startListening(isWakeWordMode: Boolean = false) {
        // Interruption: if MJ is speaking, stop speaking immediately
        interrupt()
        if (isWakeWordMode && com.example.data.BatteryOptimizationManager.isLowBatteryActive.value) {
            AssistantLogger.i(TAG, "Low Battery Mode active: Wake word loop suspended to conserve battery.")
            _voiceState.value = VoiceState.Idle
            return
        }
        isContinuousListeningActive = AppSettingsManager.isContinuousConversation.value && !com.example.data.BatteryOptimizationManager.isLowBatteryActive.value
        startListeningInternal(isWakeWordMode)
    }

    private fun startListeningInternal(isWakeWordMode: Boolean) {
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    initSpeechRecognizer()
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    if (AppSettingsManager.isAutoLanguageEnabled.value) {
                        // Request multilingual detection or user locale
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
                    }
                }

                speechRecognizer?.startListening(intent)
                isCurrentlyListening = true
                _liveTranscription.value = ""
                _voiceState.value = VoiceState.Listening(isWakeWordActive = isWakeWordMode)
                AssistantLogger.i(TAG, "Started listening (wakeWordMode=$isWakeWordMode)")
            } catch (e: Exception) {
                AssistantLogger.e(TAG, "Error starting speech recognition", e)
                _voiceState.value = VoiceState.Error("Unable to open microphone. Please try again.")
            }
        }
    }

    /**
     * Stop listening.
     */
    fun stopListening() {
        isContinuousListeningActive = false
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                AssistantLogger.e(TAG, "Error stopping SpeechRecognizer", e)
            } finally {
                isCurrentlyListening = false
                if (_voiceState.value is VoiceState.Listening) {
                    _voiceState.value = VoiceState.Idle
                }
            }
        }
    }

    /**
     * Immediately interrupts MJ (barge-in): stops TextToSpeech with sub-50ms latency.
     */
    fun interrupt() {
        try {
            if (_isTtsSpeaking.value || textToSpeech?.isSpeaking == true) {
                textToSpeech?.stop()
                _isTtsSpeaking.value = false
                AssistantLogger.i(TAG, "Interrupted active TTS playback")
            }
            pendingAfterTtsAction = null
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "Error during interruption", e)
        }
    }

    /**
     * Speaks the assistant's response aloud using dynamic language matching.
     */
    fun speak(text: String, languageCode: String = "en", onDone: (() -> Unit)? = null) {
        if (!AppSettingsManager.isTtsEnabled.value) {
            onDone?.invoke()
            return
        }

        interrupt()
        _voiceState.value = VoiceState.Speaking(text)
        pendingAfterTtsAction = onDone
        _detectedLanguage.value = languageCode

        mainHandler.post {
            if (!isTtsReady || textToSpeech == null) {
                AssistantLogger.w(TAG, "TTS not ready, skipping speech output")
                _voiceState.value = VoiceState.Idle
                onDone?.invoke()
                return@post
            }

            try {
                // Adapt TTS locale to detected language
                if (AppSettingsManager.isAutoLanguageEnabled.value && languageCode.isNotBlank()) {
                    val targetLocale = Locale.forLanguageTag(languageCode)
                    val availability = textToSpeech?.isLanguageAvailable(targetLocale)
                    if (availability != null && availability >= TextToSpeech.LANG_AVAILABLE) {
                        textToSpeech?.language = targetLocale
                        AssistantLogger.d(TAG, "TTS language set to: $targetLocale")
                    } else {
                        // Fallback to default locale
                        textToSpeech?.language = Locale.getDefault()
                    }
                } else {
                    textToSpeech?.language = Locale.getDefault()
                }

                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID_RESPONSE)
                }

                val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID_RESPONSE)
                if (result != TextToSpeech.SUCCESS) {
                    AssistantLogger.w(TAG, "TTS speak failed with result: $result")
                    _voiceState.value = VoiceState.Idle
                    onDone?.invoke()
                }
            } catch (e: Exception) {
                AssistantLogger.e(TAG, "Exception during TTS speak", e)
                _voiceState.value = VoiceState.Idle
                onDone?.invoke()
            }
        }
    }

    /**
     * Prompts the user naturally for clarification when their speech was unclear.
     */
    fun askClarification(prompt: String = "I didn't quite catch that. Could you please repeat?") {
        _voiceState.value = VoiceState.Clarifying(prompt)
        speak(prompt) {
            // Re-open listening after clarification prompt
            startListeningInternal(isWakeWordMode = false)
        }
    }

    fun setThinkingState() {
        _voiceState.value = VoiceState.Thinking
    }

    // ==========================================
    // RecognitionListener Callbacks
    // ==========================================

    override fun onReadyForSpeech(params: Bundle?) {
        AssistantLogger.d(TAG, "onReadyForSpeech")
    }

    override fun onBeginningOfSpeech() {
        // User started speaking: ensure any remaining TTS is interrupted immediately
        interrupt()
        AssistantLogger.d(TAG, "onBeginningOfSpeech (User speaking)")
    }

    override fun onRmsChanged(rmsdB: Float) {
        if (com.example.data.BatteryOptimizationManager.isLowBatteryActive.value) {
            _audioRmsLevel.value = 0f
            return
        }
        // Normalize dB (typically -2 to 10) to 0.0 .. 1.0 for UI visualizer
        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        _audioRmsLevel.value = normalized
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        AssistantLogger.d(TAG, "onEndOfSpeech")
        isCurrentlyListening = false
        _audioRmsLevel.value = 0f
        if (_voiceState.value is VoiceState.Listening) {
            _voiceState.value = VoiceState.Thinking
        }
    }

    override fun onError(error: Int) {
        isCurrentlyListening = false
        _audioRmsLevel.value = 0f
        val errorDescription = getSpeechErrorString(error)
        AssistantLogger.w(TAG, "SpeechRecognizer error: $errorDescription ($error)")

        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                // If user mumbled or nothing was heard
                if (isContinuousListeningActive) {
                    askClarification("I didn't hear anything. What can I help you with?")
                } else {
                    _voiceState.value = VoiceState.Idle
                }
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                _voiceState.value = VoiceState.Error(
                    "Microphone permission is required to listen to commands.",
                    isPermissionError = true
                )
            }
            SpeechRecognizer.ERROR_CLIENT -> {
                // Recognizer busy or cancelled
                if (_voiceState.value is VoiceState.Listening) {
                    _voiceState.value = VoiceState.Idle
                }
            }
            else -> {
                _voiceState.value = VoiceState.Error("Voice recognition error: $errorDescription")
            }
        }
    }

    override fun onResults(results: Bundle?) {
        isCurrentlyListening = false
        _audioRmsLevel.value = 0f
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val spokenText = matches?.firstOrNull()?.trim()

        if (spokenText.isNullOrBlank()) {
            askClarification("I didn't quite catch that. Could you please repeat?")
            return
        }

        AssistantLogger.i(TAG, "Recognized speech: '$spokenText'")
        _liveTranscription.value = spokenText

        val isWakeWordActive = AppSettingsManager.isWakeWordEnabled.value
        val wakeRegex = Regex(WAKE_PHRASE_REGEX)

        if (isWakeWordActive && wakeRegex.containsMatchIn(spokenText)) {
            // Extract clean command after the wake phrase
            val cleanedCommand = spokenText.replace(wakeRegex, "").trim().trim(',', '.', '!', '?')
            if (cleanedCommand.isBlank()) {
                // User only said "Hey MJ"
                speak("I'm here! What can I do for you?") {
                    startListeningInternal(isWakeWordMode = false)
                }
            } else {
                _voiceState.value = VoiceState.Thinking
                onCommandRecognized.invoke(cleanedCommand, true)
            }
        } else {
            // Normal voice command
            _voiceState.value = VoiceState.Thinking
            onCommandRecognized.invoke(spokenText, false)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = partials?.firstOrNull()
        if (!text.isNullOrBlank()) {
            _liveTranscription.value = text
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun getSpeechErrorString(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown speech error"
        }
    }

    fun pause() {
        stopListening()
        interrupt()
    }

    fun destroy() {
        stopListening()
        interrupt()
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
                textToSpeech?.stop()
                textToSpeech?.shutdown()
                textToSpeech = null
            } catch (e: Exception) {
                AssistantLogger.e(TAG, "Error destroying VoiceInteractionManager", e)
            }
        }
    }
}
