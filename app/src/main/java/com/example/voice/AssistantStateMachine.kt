package com.example.voice

enum class AssistantState {
    IDLE, LISTENING, PROCESSING, EXECUTING, SPEAKING, ERROR, CANCELLED
}

/** Guards the shared Phase 2 voice state against impossible lifecycle jumps. */
class AssistantStateMachine(initialState: AssistantState = AssistantState.IDLE) {
    var current: AssistantState = initialState
        private set

    fun canTransitionTo(next: AssistantState): Boolean =
        next == current || next in transitions.getValue(current)

    fun transitionTo(next: AssistantState): Boolean {
        if (!canTransitionTo(next)) return false
        current = next
        return true
    }

    companion object {
        private val transitions = mapOf(
            AssistantState.IDLE to setOf(AssistantState.LISTENING, AssistantState.PROCESSING, AssistantState.SPEAKING, AssistantState.ERROR, AssistantState.CANCELLED),
            AssistantState.LISTENING to setOf(AssistantState.IDLE, AssistantState.PROCESSING, AssistantState.SPEAKING, AssistantState.ERROR, AssistantState.CANCELLED),
            AssistantState.PROCESSING to setOf(AssistantState.IDLE, AssistantState.LISTENING, AssistantState.EXECUTING, AssistantState.SPEAKING, AssistantState.ERROR, AssistantState.CANCELLED),
            AssistantState.EXECUTING to setOf(AssistantState.IDLE, AssistantState.PROCESSING, AssistantState.SPEAKING, AssistantState.ERROR, AssistantState.CANCELLED),
            AssistantState.SPEAKING to setOf(AssistantState.IDLE, AssistantState.LISTENING, AssistantState.PROCESSING, AssistantState.ERROR, AssistantState.CANCELLED),
            AssistantState.ERROR to setOf(AssistantState.IDLE, AssistantState.LISTENING, AssistantState.PROCESSING, AssistantState.CANCELLED),
            AssistantState.CANCELLED to setOf(AssistantState.IDLE, AssistantState.LISTENING, AssistantState.PROCESSING, AssistantState.SPEAKING, AssistantState.ERROR)
        )
    }
}

internal fun VoiceState.toAssistantState(): AssistantState = when (this) {
    VoiceState.Idle -> AssistantState.IDLE
    is VoiceState.Listening -> AssistantState.LISTENING
    VoiceState.Processing, VoiceState.Thinking, is VoiceState.Clarifying -> AssistantState.PROCESSING
    VoiceState.Executing -> AssistantState.EXECUTING
    is VoiceState.Speaking -> AssistantState.SPEAKING
    is VoiceState.Error -> AssistantState.ERROR
    VoiceState.Cancelled -> AssistantState.CANCELLED
}
