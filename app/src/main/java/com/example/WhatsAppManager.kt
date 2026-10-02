package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.example.contact.ContactLookupOutcome
import com.example.contact.ContactsManager

data class WhatsAppResult(
    val success: Boolean,
    val spokenMessage: String,
    val contact: String,
    val messageText: String? = null
)

object WhatsAppManager {
    private const val TAG = "WhatsAppManager"
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    /**
     * Checks whether WhatsApp or WhatsApp Business is installed on the device.
     */
    fun isWhatsAppInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return isPackageInstalled(pm, WHATSAPP_PACKAGE) || isPackageInstalled(pm, WHATSAPP_BUSINESS_PACKAGE)
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Triggers the native WhatsApp share/send intent.
     * When the user says "Send a WhatsApp to [contact]", this triggers the native share intent
     * targeting WhatsApp so the user can immediately dispatch or choose the recipient.
     */
    fun sendWhatsApp(context: Context, contact: String, messageText: String? = null): WhatsAppResult {
        AssistantLogger.i(TAG, "Preparing WhatsApp conversation")
        val cleanContact = contact.trim()
        val textToSend = messageText?.trim().orEmpty()
        if (cleanContact.isBlank()) {
            return WhatsAppResult(false, "Please tell me which WhatsApp contact to message.", cleanContact, textToSend)
        }

        val targetPackage = when {
            isPackageInstalled(context.packageManager, WHATSAPP_PACKAGE) -> WHATSAPP_PACKAGE
            isPackageInstalled(context.packageManager, WHATSAPP_BUSINESS_PACKAGE) -> WHATSAPP_BUSINESS_PACKAGE
            else -> null
        } ?: return WhatsAppResult(false, "WhatsApp is not installed on this device.", cleanContact, textToSend)

        var phoneNumber: String? = cleanContact.takeIf { it.matches("^\\\\+?[0-9]{7,15}$".toRegex()) }
        var resolvedName = cleanContact

        if (phoneNumber == null) {
            when (val outcome = ContactsManager.lookupContact(context, cleanContact)) {
                is ContactLookupOutcome.SingleMatch -> {
                    phoneNumber = outcome.entry.phoneNumber
                    resolvedName = outcome.entry.displayName
                }
                is ContactLookupOutcome.MultipleContacts ->
                    return WhatsAppResult(false, "I found multiple contacts named " + cleanContact + ". Please specify which one.", cleanContact, textToSend)
                is ContactLookupOutcome.MultipleNumbersForContact ->
                    return WhatsAppResult(false, "That contact has multiple phone numbers. Please specify the number.", cleanContact, textToSend)
                is ContactLookupOutcome.PermissionRequired ->
                    return WhatsAppResult(false, outcome.message, cleanContact, textToSend)
                is ContactLookupOutcome.ContactHasNoNumber ->
                    return WhatsAppResult(false, outcome.contactName + " does not have a phone number.", cleanContact, textToSend)
                is ContactLookupOutcome.DirectNumber -> phoneNumber = outcome.phoneNumber
                is ContactLookupOutcome.NotFound ->
                    return WhatsAppResult(false, "I couldn't find WhatsApp contact " + cleanContact + ".", cleanContact, textToSend)
                is ContactLookupOutcome.Error ->
                    return WhatsAppResult(false, outcome.message, cleanContact, textToSend)
            }
        }

        val normalizedPhone = phoneNumber?.filter { it.isDigit() }.takeIf { it != null && it.length in 7..15 }
            ?: return WhatsAppResult(false, "I couldn't resolve a valid WhatsApp number for " + resolvedName + ".", cleanContact, textToSend)

        return try {
            val uri = Uri.parse("https://wa.me/" + normalizedPhone).buildUpon().apply {
                if (textToSend.isNotBlank()) appendQueryParameter("text", textToSend)
            }.build()
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val spoken = if (textToSend.isNotBlank()) {
                "Opening WhatsApp chat with " + resolvedName + " with your message ready. Tap Send to send it."
            } else {
                "Opening WhatsApp chat with " + resolvedName + "."
            }
            WhatsAppResult(true, spoken, resolvedName, textToSend)
        } catch (e: Exception) {
            AssistantLogger.w(TAG, "WhatsApp conversation intent failed")
            WhatsAppResult(false, "Could not open WhatsApp chat with " + resolvedName + ".", resolvedName, textToSend)
        }
    }

    /**
     * Parses voice commands for WhatsApp expressions like:
     * - "Send a WhatsApp to Mom"
     * - "Send a WhatsApp to John saying I'm running late"
     * - "WhatsApp Sarah: Are you free today?"
     * - "Send WhatsApp message to David"
     */
    fun parseWhatsAppVoiceCommand(query: String): Pair<String, String?>? {
        val trimmed = query.trim()
        val lower = trimmed.lowercase()

        val patterns = listOf(
            // "send a whatsapp to [contact] saying [message]"
            "(?i)send\\s+(?:a\\s+)?whatsapp\\s+(?:message\\s+)?to\\s+([^:]+?)\\s+saying\\s+(.+)",
            // "send a whatsapp to [contact]: [message]"
            "(?i)send\\s+(?:a\\s+)?whatsapp\\s+(?:message\\s+)?to\\s+([^:]+?):\\s*(.+)",
            // "send a whatsapp to [contact]"
            "(?i)send\\s+(?:a\\s+)?whatsapp\\s+(?:message\\s+)?to\\s+(.+)",
            // "whatsapp [contact] saying [message]"
            "(?i)whatsapp\\s+([^:]+?)\\s+saying\\s+(.+)",
            // "whatsapp [contact]: [message]"
            "(?i)whatsapp\\s+([^:]+?):\\s*(.+)",
            // "whatsapp [contact]"
            "(?i)whatsapp\\s+(.+)"
        )

        for (patternStr in patterns) {
            val matcher = java.util.regex.Pattern.compile(patternStr).matcher(trimmed)
            if (matcher.matches()) {
                val contact = matcher.group(1)?.trim() ?: return null
                val message = if (matcher.groupCount() >= 2) matcher.group(2)?.trim() else null
                return Pair(contact, message)
            }
        }

        return null
    }
}
