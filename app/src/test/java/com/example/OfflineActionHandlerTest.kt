package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.UserMemoryManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineActionHandlerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        UserMemoryManager.init(context)
        UserMemoryManager.setMemoryEnabled(true)
        UserMemoryManager.clearAllMemories()
    }

    @Test
    fun testTimeQueryOffline() = runTest {
        val result = OfflineActionHandler.handleOfflineCommand(context, "what time is it")
        assertTrue(result.handled)
        assertTrue(result.spokenResponse.contains("time is", ignoreCase = true))
    }

    @Test
    fun testBatteryQueryOffline() = runTest {
        val result = OfflineActionHandler.handleOfflineCommand(context, "check battery level")
        assertTrue(result.handled)
        assertTrue(result.spokenResponse.contains("Battery"))
    }

    @Test
    fun testMemoryQueryOffline() = runTest {
        UserMemoryManager.rememberPreference("I like coffee in the morning", allowSensitiveIfConsented = false)
        val result = OfflineActionHandler.handleOfflineCommand(context, "what do you remember")
        assertTrue(result.handled)
        assertTrue(result.spokenResponse.contains("coffee in the morning"))
    }

    @Test
    fun testOpenAppOffline() = runTest {
        val result = OfflineActionHandler.handleOfflineCommand(context, "open chrome")
        assertTrue(result.handled)
        assertEquals("OPEN_APP", result.actionTaken)
    }

    @Test
    fun testAlarmOffline() = runTest {
        val result = OfflineActionHandler.handleOfflineCommand(context, "set alarm for 7")
        assertTrue(result.handled)
        assertEquals("SET_ALARM", result.actionTaken)
    }

    @Test
    fun testCommandNormalizationAndVariations() = runTest {
        // Conversational prefix + punctuation
        val result1 = OfflineActionHandler.handleOfflineCommand(context, "Can you please open Chrome?")
        assertTrue(result1.handled)
        assertEquals("OPEN_APP", result1.actionTaken)

        // "start" variation
        val result2 = OfflineActionHandler.handleOfflineCommand(context, "Start YouTube!")
        assertTrue(result2.handled)
        assertEquals("OPEN_APP", result2.actionTaken)

        // Bluetooth toggle variation
        val result3 = OfflineActionHandler.handleOfflineCommand(context, "Hey MJ, enable Bluetooth")
        assertTrue(result3.handled)
        assertEquals("TOGGLE_BLUETOOTH", result3.actionTaken)

        // Wi-Fi toggle variation
        val result4 = OfflineActionHandler.handleOfflineCommand(context, "Turn off Wi-Fi")
        assertTrue(result4.handled)
        assertEquals("TOGGLE_WIFI", result4.actionTaken)

        // Flashlight
        val result5 = OfflineActionHandler.handleOfflineCommand(context, "Turn on the flashlight")
        assertTrue(result5.handled)
        assertEquals("FLASHLIGHT", result5.actionTaken)

        // Timer
        val result6 = OfflineActionHandler.handleOfflineCommand(context, "Set timer for 10 minutes")
        assertTrue(result6.handled)
        assertEquals("SET_TIMER", result6.actionTaken)

        // Weather offline
        val result7 = OfflineActionHandler.handleOfflineCommand(context, "What's the weather today?")
        assertTrue(result7.handled)
        assertEquals("WEATHER_OFFLINE", result7.actionTaken)

        // Date
        val result8 = OfflineActionHandler.handleOfflineCommand(context, "What is today's date?")
        assertTrue(result8.handled)
        assertEquals("GET_DATE", result8.actionTaken)

        // Web search
        val result9 = OfflineActionHandler.handleOfflineCommand(context, "Google nearest coffee shop")
        assertTrue(result9.handled)
        assertEquals("SEARCH_WEB", result9.actionTaken)
    }

    @Test
    fun testAppNotFoundProvidesHelpfulFeedback() = runTest {
        val result = OfflineActionHandler.handleOfflineCommand(context, "open non_existent_application_xyz")
        assertTrue(result.handled)
        assertEquals("APP_NOT_FOUND", result.actionTaken)
        assertTrue(result.spokenResponse.contains("couldn't find", ignoreCase = true))
    }

    @Test
    fun testSmsParsingOffline() = runTest {
        val result = OfflineActionHandler.handleOfflineCommand(context, "Text Mom: I am heading home now")
        assertTrue(result.handled)
        assertEquals("SEND_SMS", result.actionTaken)
    }

    @Test
    fun testUnhandledComplexQueryOffline() = runTest {
        val result = OfflineActionHandler.handleOfflineCommand(context, "explain quantum physics to me in simple terms")
        assertFalse(result.handled)
        assertTrue(result.spokenResponse.contains("offline", ignoreCase = true))
    }
}
