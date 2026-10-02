package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppManagerTest {
    @Test fun send_whatsapp_command_is_parsed_locally_with_recipient_and_message() {
        assertEquals(
            "Mom" to "I'm running late",
            WhatsAppManager.parseWhatsAppVoiceCommand("Send a WhatsApp to Mom saying I'm running late")
        )
    }

    @Test fun whatsapp_without_a_message_is_parsed_locally() {
        assertEquals("David" to null, WhatsAppManager.parseWhatsAppVoiceCommand("Send WhatsApp message to David"))
    }
}
