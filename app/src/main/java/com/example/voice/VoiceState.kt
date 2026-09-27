package com.example.voice

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partialText: String = "") : VoiceState
    data object Processing : VoiceState
    data object Executing : VoiceState
    data class Speaking(val text: String) : VoiceState
    data class Error(val message: String, val retryable: Boolean = true, val permissionRequired: Boolean = false) : VoiceState
    data object Cancelled : VoiceState
}
