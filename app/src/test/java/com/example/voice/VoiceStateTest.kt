package com.example.voice

import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStateTest {
    @Test fun all_phase2_states_are_distinct() {
        val states = listOf(
            VoiceState.Idle,
            VoiceState.Listening(),
            VoiceState.Processing,
            VoiceState.Executing,
            VoiceState.Speaking("hello"),
            VoiceState.Error("failed"),
            VoiceState.Cancelled
        )
        assertTrue(states.distinct().size == 7)
    }
}
