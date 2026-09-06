package com.example.music

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppSettingsManager
import com.example.data.DefaultMusicApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicActionManagerTest {

    private lateinit var context: Context
    private lateinit var shadowApp: ShadowApplication

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowApp = shadowOf(context as Application)
        AppSettingsManager.init(context)
        AppSettingsManager.setDefaultMusicApp(DefaultMusicApp.AUTO)
    }

    @Test
    fun testParseMusicCommandBasic() {
        val cmd1 = MusicActionManager.parseMusicCommand("Play Believer")
        assertNotNull(cmd1)
        assertEquals("Believer", cmd1?.song)
        assertEquals(MusicPlatform.AUTO, cmd1?.platform)

        val cmd2 = MusicActionManager.parseMusicCommand("Play Shape of You")
        assertNotNull(cmd2)
        assertEquals("Shape of You", cmd2?.song)
        assertEquals(MusicPlatform.AUTO, cmd2?.platform)

        val cmd3 = MusicActionManager.parseMusicCommand("Listen to Starboy")
        assertNotNull(cmd3)
        assertEquals("Starboy", cmd3?.song)
        assertEquals(MusicPlatform.AUTO, cmd3?.platform)
    }

    @Test
    fun testParseMusicCommandWithPlatform() {
        val spotifyCmd = MusicActionManager.parseMusicCommand("Play Shape of You on Spotify")
        assertNotNull(spotifyCmd)
        assertEquals("Shape of You", spotifyCmd?.song)
        assertEquals(MusicPlatform.SPOTIFY, spotifyCmd?.platform)

        val ytCmd = MusicActionManager.parseMusicCommand("Play Believer on YouTube")
        assertNotNull(ytCmd)
        assertEquals("Believer", ytCmd?.song)
        assertEquals(MusicPlatform.YOUTUBE, ytCmd?.platform)

        val ytmCmd = MusicActionManager.parseMusicCommand("Play Blinding Lights on YouTube Music")
        assertNotNull(ytmCmd)
        assertEquals("Blinding Lights", ytmCmd?.song)
        assertEquals(MusicPlatform.YOUTUBE_MUSIC, ytmCmd?.platform)
    }

    @Test
    fun testParseMusicCommandWithArtist() {
        val cmd = MusicActionManager.parseMusicCommand("Play Perfect by Ed Sheeran")
        assertNotNull(cmd)
        assertEquals("Perfect", cmd?.song)
        assertEquals("Ed Sheeran", cmd?.artist)
        assertEquals(MusicPlatform.AUTO, cmd?.platform)

        val cmdWithPlatform = MusicActionManager.parseMusicCommand("Play Perfect by Ed Sheeran on Spotify")
        assertNotNull(cmdWithPlatform)
        assertEquals("Perfect", cmdWithPlatform?.song)
        assertEquals("Ed Sheeran", cmdWithPlatform?.artist)
        assertEquals(MusicPlatform.SPOTIFY, cmdWithPlatform?.platform)
    }

    @Test
    fun testParseMusicCommandPolitePrefixes() {
        val cmd1 = MusicActionManager.parseMusicCommand("Can you please play Believer?")
        assertNotNull(cmd1)
        assertEquals("Believer", cmd1?.song)

        val cmd2 = MusicActionManager.parseMusicCommand("Hey MJ, play the song Shape of You")
        assertNotNull(cmd2)
        assertEquals("Shape of You", cmd2?.song)

        val cmd3 = MusicActionManager.parseMusicCommand("MJ put on some music by Coldplay")
        assertNotNull(cmd3)
        assertEquals("Coldplay", cmd3?.song)
    }

    @Test
    fun testParseMusicCommandEmptySong() {
        val cmd = MusicActionManager.parseMusicCommand("play")
        assertNotNull(cmd)
        assertEquals("", cmd?.song)

        val cmd2 = MusicActionManager.parseMusicCommand("play music")
        assertNotNull(cmd2)
        assertEquals("", cmd2?.song)
    }

    @Test
    fun testParseNonMusicCommands() {
        assertNull(MusicActionManager.parseMusicCommand("What is the weather today?"))
        assertNull(MusicActionManager.parseMusicCommand("Call Mom"))
        assertNull(MusicActionManager.parseMusicCommand("Open Spotify"))
    }

    @Test
    fun testExecuteMusicCommandEmptyPrompts() {
        val result = MusicActionManager.executeMusicCommand(context, MusicCommand(song = ""))
        assertTrue(result is MusicExecutionResult.NeedsSongPrompt)
    }

    @Test
    fun testExecuteMusicCommandYouTube() {
        val cmd = MusicCommand(song = "Believer", platform = MusicPlatform.YOUTUBE)
        val result = MusicActionManager.executeMusicCommand(context, cmd)
        assertTrue(result is MusicExecutionResult.Success)

        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertTrue(startedIntent.action == Intent.ACTION_SEARCH || startedIntent.action == Intent.ACTION_VIEW)
    }

    @Test
    fun testExecuteMusicCommandSpotifyFallback() {
        val cmd = MusicCommand(song = "Shape of You", platform = MusicPlatform.SPOTIFY)
        val result = MusicActionManager.executeMusicCommand(context, cmd)
        assertTrue(result is MusicExecutionResult.Success)

        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertTrue(
            startedIntent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH ||
            startedIntent.action == Intent.ACTION_VIEW
        )
    }

    @Test
    fun testExecuteMusicCommandYouTubeMusic() {
        val cmd = MusicCommand(song = "Starboy", platform = MusicPlatform.YOUTUBE_MUSIC)
        val result = MusicActionManager.executeMusicCommand(context, cmd)
        assertTrue(result is MusicExecutionResult.Success)

        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertTrue(startedIntent.action == Intent.ACTION_VIEW)
    }
}
