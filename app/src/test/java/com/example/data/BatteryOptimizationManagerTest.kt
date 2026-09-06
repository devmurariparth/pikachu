package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class BatteryOptimizationManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        BatteryOptimizationManager.init(context)
    }

    @Test
    fun testModePreferenceAlwaysOn() {
        BatteryOptimizationManager.setModePreference(BatteryModePreference.ALWAYS_ON)
        assertEquals(BatteryModePreference.ALWAYS_ON, BatteryOptimizationManager.modePreference.value)
        assertTrue(BatteryOptimizationManager.isLowBatteryActive.value)
    }

    @Test
    fun testModePreferenceOff() {
        BatteryOptimizationManager.setModePreference(BatteryModePreference.OFF)
        assertEquals(BatteryModePreference.OFF, BatteryOptimizationManager.modePreference.value)
        assertFalse(BatteryOptimizationManager.isLowBatteryActive.value)
    }

    @Test
    fun testModePreferenceAutomatic() {
        BatteryOptimizationManager.setModePreference(BatteryModePreference.AUTOMATIC)
        assertEquals(BatteryModePreference.AUTOMATIC, BatteryOptimizationManager.modePreference.value)
    }
}
