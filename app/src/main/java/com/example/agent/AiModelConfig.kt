package com.example.agent

/** Central catalog for Gemini models used now and at planned modality extension points. */
object AiModelConfig {
    const val PRIMARY_PLANNER = "gemini-3.8-flash"
    const val REASONING_FALLBACK = "gemini-3.1-pro-preview"
    const val REALTIME_VOICE = "gemini-3.8-live"
    const val SPEECH_TRANSCRIPTION = "gemini-3.5-transcribe"
    const val SPEECH_SYNTHESIS = "gemini-3.8-flash-tts"
}

data class TranscriptionResult(val text: String, val language: String?)
data class SynthesizedSpeech(val mimeType: String, val audio: ByteArray)

/** Extension point for a future realtime provider; no live microphone behavior is enabled here. */
interface RealtimeVoiceProvider {
    val modelId: String
    suspend fun openSession(): Result<RealtimeVoiceSession>
}

interface RealtimeVoiceSession {
    suspend fun sendText(text: String)
    suspend fun cancel()
}

/** Extension points only. Existing on-device speech recognition and Android TTS remain in use. */
interface SpeechTranscriptionProvider {
    val modelId: String
    suspend fun transcribe(audio: ByteArray, mimeType: String, languageHint: String?): Result<TranscriptionResult>
}

interface SpeechSynthesisProvider {
    val modelId: String
    suspend fun synthesize(text: String, language: String): Result<SynthesizedSpeech>
}
