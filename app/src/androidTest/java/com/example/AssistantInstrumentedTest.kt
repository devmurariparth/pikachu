package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ComponentName
import android.content.pm.PackageManager
import android.service.voice.VoiceInteractionService
import com.example.contact.CallActionManager
import com.example.music.MusicActionManager
import com.example.music.MusicCommand
import com.example.music.MusicPlatform
import com.example.voice.AssistantCapabilityDetector
import com.example.voice.CapabilityState
import com.example.voice.MjVoiceInteractionService
import com.example.voice.MjVoiceInteractionSessionService
import com.example.voice.SystemAssistantInvocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test executing on an Android device/emulator.
 * Verifies MJ core assistant local routing and package context
 * without requiring real external network services, contacts, or installed media apps.
 */
@RunWith(AndroidJUnit4::class)
class AssistantInstrumentedTest {

    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(appContext)
        assertEquals("com.aistudio.mjassistant.abxyzt", appContext.packageName)
    }

    @Test
    fun testLocalMusicCommandParsingOnDevice() {
        val cmd = MusicActionManager.parseMusicCommand("Play Believer on Spotify")
        assertNotNull(cmd)
        assertEquals("Believer", cmd?.song)
        assertEquals(MusicPlatform.SPOTIFY, cmd?.platform)

        val ytCmd = MusicActionManager.parseMusicCommand("Play Shape of You on YouTube")
        assertNotNull(ytCmd)
        assertEquals("Shape of You", ytCmd?.song)
        assertEquals(MusicPlatform.YOUTUBE, ytCmd?.platform)
    }

    @Test
    fun testLocalCallCommandParsingOnDevice() {
        val parsed = CallActionManager.parseCallCommand("Call Rahul")
        assertEquals("rahul", parsed)

        val parsedMom = CallActionManager.parseCallCommand("Call my Mom")
        assertEquals("mom", parsedMom)
    }

    @Test
    fun testMusicCommandIntentGenerationWithoutExternalApps() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cmd = MusicCommand(song = "Believer", platform = MusicPlatform.YOUTUBE)
        val result = MusicActionManager.executeMusicCommand(context, cmd)
        // Should produce a valid intent execution result (fallback browser or YouTube intent) without crashing
        assertNotNull(result)
        assertTrue(result is com.example.music.MusicExecutionResult.Success)
    }

    @Test
    fun voice_interaction_services_are_declared_with_system_binding_and_metadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val voiceInfo = manager.getServiceInfo(
            ComponentName(context, MjVoiceInteractionService::class.java),
            PackageManager.GET_META_DATA
        )
        val sessionInfo = manager.getServiceInfo(
            ComponentName(context, MjVoiceInteractionSessionService::class.java),
            0
        )

        assertTrue(voiceInfo.exported)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", voiceInfo.permission)
        assertEquals(
            com.example.R.xml.voice_interaction_service,
            voiceInfo.metaData?.getInt(VoiceInteractionService.SERVICE_META_DATA)
        )
        assertEquals("android.permission.BIND_VOICE_INTERACTION", sessionInfo.permission)
        assertTrue(sessionInfo.processName.endsWith(":voice_session"))
    }

    @Test
    fun capability_detection_and_system_invocation_are_available_on_supported_devices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capabilities = AssistantCapabilityDetector.detect(context)
        val intent = SystemAssistantInvocation.createActivityIntent(context)

        assertEquals(CapabilityState.AVAILABLE, capabilities.voiceInteraction.state)
        assertEquals(context.packageName, intent.component?.packageName)
        assertTrue(SystemAssistantInvocation.isAssistantInvocation(intent))
    }
}
