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

    @Test fun assistant_state_machine_rejects_illegal_transitions_and_recovers_to_idle() {
        val machine = AssistantStateMachine()

        assertTrue(machine.current == AssistantState.IDLE)
        assertTrue(!machine.transitionTo(AssistantState.EXECUTING))
        assertTrue(machine.current == AssistantState.IDLE)
        assertTrue(machine.transitionTo(AssistantState.LISTENING))
        assertTrue(machine.transitionTo(AssistantState.PROCESSING))
        assertTrue(machine.transitionTo(AssistantState.EXECUTING))
        assertTrue(machine.transitionTo(AssistantState.CANCELLED))

        // A recreated process starts idle; in-flight microphone/action state is never restored.
        assertTrue(AssistantStateMachine().current == AssistantState.IDLE)
    }

    @Test fun phase_two_voice_states_map_into_the_shared_assistant_state_machine() {
        assertTrue(VoiceState.Listening().toAssistantState() == AssistantState.LISTENING)
        assertTrue(VoiceState.Processing.toAssistantState() == AssistantState.PROCESSING)
        assertTrue(VoiceState.Executing.toAssistantState() == AssistantState.EXECUTING)
        assertTrue(VoiceState.Speaking("done").toAssistantState() == AssistantState.SPEAKING)
        assertTrue(VoiceState.Cancelled.toAssistantState() == AssistantState.CANCELLED)
    }
}
