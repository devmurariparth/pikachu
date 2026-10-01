package com.example.voice

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceInteractionLifecycleTest {
    @Test fun service_ready_and_shutdown_are_restart_safe_and_do_not_start_listening() {
        val controller = Robolectric.buildService(MjVoiceInteractionService::class.java).create()
        val service = controller.get()

        assertFalse(service.isSystemReadyForTest())
        service.onReady()
        assertTrue(service.isSystemReadyForTest())
        service.onShutdown()
        assertFalse(service.isSystemReadyForTest())

        val restarted = Robolectric.buildService(MjVoiceInteractionService::class.java).create().get()
        assertFalse(restarted.isSystemReadyForTest())
        controller.destroy()
    }

    @Test fun system_session_service_creates_a_fresh_session_only_when_requested() {
        val service = Robolectric.buildService(MjVoiceInteractionSessionService::class.java)
            .create()
            .get()

        assertNotNull(service.onNewSession(Bundle()))
    }

    @Test fun invocation_contract_targets_existing_chat_activity_and_marks_system_entry() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = SystemAssistantInvocation.createActivityIntent(context)

        assertEquals(SystemAssistantInvocation.ACTION_START_VOICE_ASSISTANT, intent.action)
        assertTrue(SystemAssistantInvocation.isAssistantInvocation(intent))
        assertEquals("android_voice_interaction", intent.getStringExtra(SystemAssistantInvocation.EXTRA_INVOCATION_SOURCE))
    }
}
