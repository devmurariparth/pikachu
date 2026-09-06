package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.UserMemoryManager
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
    fun testTimeQueryOffline() {
        val result = OfflineActionHandler.handleOfflineCommand(context, "what time is it")
        assertTrue(result.handled)
        assertTrue(result.spokenResponse.contains("time is", ignoreCase = true))
    }

    @Test
    fun testBatteryQueryOffline() {
        val result = OfflineActionHandler.handleOfflineCommand(context, "check battery level")
        assertTrue(result.handled)
        assertTrue(result.spokenResponse.contains("Battery"))
    }

    @Test
    fun testMemoryQueryOffline() {
        UserMemoryManager.rememberPreference("I like coffee in the morning", allowSensitiveIfConsented = false)
        val result = OfflineActionHandler.handleOfflineCommand(context, "what do you remember")
        assertTrue(result.handled)
        assertTrue(result.spokenResponse.contains("coffee in the morning"))
    }

    @Test
    fun testOpenAppOffline() {
        val result = OfflineActionHandler.handleOfflineCommand(context, "open chrome")
        assertTrue(result.handled)
        assertEquals("OPEN_APP", result.actionTaken)
    }

    @Test
    fun testAlarmOffline() {
        val result = OfflineActionHandler.handleOfflineCommand(context, "set alarm for 7")
        assertTrue(result.handled)
        assertEquals("SET_ALARM", result.actionTaken)
    }

    @Test
    fun testUnhandledComplexQueryOffline() {
        val result = OfflineActionHandler.handleOfflineCommand(context, "explain quantum physics to me in simple terms")
        assertFalse(result.handled)
        assertTrue(result.spokenResponse.contains("offline", ignoreCase = true))
    }
}
