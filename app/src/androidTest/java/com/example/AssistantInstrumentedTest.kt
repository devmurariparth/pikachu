package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.contact.CallActionManager
import com.example.music.MusicActionManager
import com.example.music.MusicCommand
import com.example.music.MusicPlatform
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
}
