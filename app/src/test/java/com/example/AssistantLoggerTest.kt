package com.example

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
class AssistantLoggerTest {

    @Before
    fun setup() {
        AssistantLogger.clear()
    }

    @Test
    fun testPiiSanitizationForApiKey() {
        val rawMessage = "Testing with API key: AIzaSyD984hfh498fhe8w9fhe8w9hf98we"
        AssistantLogger.i("TestTag", rawMessage)

        val log = AssistantLogger.logs.value.find { it.tag == "TestTag" }
        assertTrue(log != null)
        assertFalse(log!!.message.contains("AIzaSyD984hfh498fhe8w9fhe8w9hf98we"))
        assertTrue(log.message.contains("AIza********************************"))
    }

    @Test
    fun testPiiSanitizationForCreditCard() {
        val rawMessage = "User provided card 4111-2222-3333-4444 for subscription"
        AssistantLogger.w("TestTag", rawMessage)

        val log = AssistantLogger.logs.value.find { it.tag == "TestTag" }
        assertTrue(log != null)
        assertFalse(log!!.message.contains("4111-2222-3333-4444"))
        assertTrue(log.message.contains("****-****-****-4444"))
    }

    @Test
    fun testPiiSanitizationForPassword() {
        val rawMessage = "Attempting auth with password = mySuperSecretKey123"
        AssistantLogger.e("TestTag", rawMessage)

        val log = AssistantLogger.logs.value.find { it.tag == "TestTag" }
        assertTrue(log != null)
        assertFalse(log!!.message.contains("mySuperSecretKey123"))
        assertTrue(log.message.contains("password=[REDACTED]"))
    }

    @Test
    fun testRingBufferCapacity() {
        // Log 300 entries; the ring buffer cap is 250
        for (i in 1..300) {
            AssistantLogger.d("Tag", "Entry $i")
        }

        val logs = AssistantLogger.logs.value
        assertEquals(250, logs.size)
        // Most recent entries should be preserved
        assertTrue(logs.last().message.contains("Entry 300"))
    }

    @Test
    fun throwable_message_is_not_recorded() {
        val secret = "private-user-prompt-or-api-key"
        AssistantLogger.e("TestTag", "request failed", IllegalStateException(secret))

        val log = AssistantLogger.logs.value.last { it.tag == "TestTag" }
        assertFalse(log.message.contains(secret))
        assertFalse(log.throwableMessage.orEmpty().contains(secret))
    }
}
