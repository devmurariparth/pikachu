package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserMemoryManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        UserMemoryManager.init(context)
        UserMemoryManager.setMemoryEnabled(true)
        UserMemoryManager.clearAllMemories()
    }

    @Test
    fun testSaveNormalPreference() {
        val result = UserMemoryManager.rememberPreference("I prefer vegetarian food", allowSensitiveIfConsented = false)
        assertTrue(result is SaveMemoryResult.Success)
        val saved = result as SaveMemoryResult.Success
        assertEquals("I prefer vegetarian food", saved.item.text)
        assertFalse(saved.item.isSensitive)

        val memories = UserMemoryManager.memories.value
        assertEquals(1, memories.size)
        assertEquals("I prefer vegetarian food", memories[0].text)
    }

    @Test
    fun testSensitiveDataConsentRequired() {
        // Credit card should be flagged as sensitive and require explicit user consent
        val result = UserMemoryManager.rememberPreference("My card number is 4111 2222 3333 4444", allowSensitiveIfConsented = false)
        assertTrue(result is SaveMemoryResult.RequiresSensitiveConsent)
        val consentResult = result as SaveMemoryResult.RequiresSensitiveConsent
        assertEquals("Financial / Payment Information", consentResult.sensitiveType)

        // Memory should NOT be stored yet without consent
        assertEquals(0, UserMemoryManager.memories.value.size)

        // Storing with explicit user consent should succeed
        val approvedResult = UserMemoryManager.rememberPreference("My card number is 4111 2222 3333 4444", allowSensitiveIfConsented = true)
        assertTrue(approvedResult is SaveMemoryResult.Success)
        assertTrue((approvedResult as SaveMemoryResult.Success).item.isSensitive)
        assertEquals(1, UserMemoryManager.memories.value.size)
    }

    @Test
    fun testPasswordSensitiveDataConsentRequired() {
        val result = UserMemoryManager.rememberPreference("My password is superSecret123!", allowSensitiveIfConsented = false)
        assertTrue(result is SaveMemoryResult.RequiresSensitiveConsent)
        val consentResult = result as SaveMemoryResult.RequiresSensitiveConsent
        assertEquals("Password or Security Code", consentResult.sensitiveType)
    }

    @Test
    fun testHealthcareSensitiveDataConsentRequired() {
        val result = UserMemoryManager.rememberPreference("I take daily medication for high blood pressure", allowSensitiveIfConsented = false)
        assertTrue(result is SaveMemoryResult.RequiresSensitiveConsent)
        val consentResult = result as SaveMemoryResult.RequiresSensitiveConsent
        assertEquals("Health / Medical Data", consentResult.sensitiveType)
    }

    @Test
    fun testForgetSpecificMemory() {
        UserMemoryManager.rememberPreference("I prefer dark mode", allowSensitiveIfConsented = false)
        UserMemoryManager.rememberPreference("I prefer metric units", allowSensitiveIfConsented = false)
        assertEquals(2, UserMemoryManager.memories.value.size)

        val forgetResult = UserMemoryManager.forgetMemory("dark mode")
        assertTrue(forgetResult is ForgetMemoryResult.ItemRemoved)
        assertEquals(1, UserMemoryManager.memories.value.size)
        assertEquals("I prefer metric units", UserMemoryManager.memories.value[0].text)
    }

    @Test
    fun testWhatDoYouRememberFormatted() {
        UserMemoryManager.rememberPreference("I prefer concise answers", allowSensitiveIfConsented = false)
        val summary = UserMemoryManager.getMemoriesSummaryText()
        assertTrue(summary.contains("I prefer concise answers"))
        assertTrue(summary.contains("Here is what I currently remember"))
    }

    @Test
    fun testDisabledMemoryDoesNotSave() {
        UserMemoryManager.setMemoryEnabled(false)
        val result = UserMemoryManager.rememberPreference("Remember my favorite color is blue", allowSensitiveIfConsented = false)
        assertTrue(result is SaveMemoryResult.Disabled)
        assertEquals(0, UserMemoryManager.memories.value.size)
    }

    @Test
    fun testClearAllMemories() {
        UserMemoryManager.rememberPreference("Pref 1", allowSensitiveIfConsented = false)
        UserMemoryManager.rememberPreference("Pref 2", allowSensitiveIfConsented = false)
        assertEquals(2, UserMemoryManager.memories.value.size)

        UserMemoryManager.clearAllMemories()
        assertEquals(0, UserMemoryManager.memories.value.size)
    }
}
