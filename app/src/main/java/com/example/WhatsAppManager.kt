package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

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
        AssistantLogger.i(TAG, "Triggering WhatsApp intent for contact: '$contact', message: '$messageText'")
        val cleanContact = contact.trim()
        val textToSend = messageText?.trim() ?: ""

        // Check if contact looks like a phone number (digits with optional leading +)
        val isPhoneNumber = cleanContact.matches("^\\+?[0-9]{7,15}$".toRegex())
        val targetPackage = if (isPackageInstalled(context.packageManager, WHATSAPP_PACKAGE)) {
            WHATSAPP_PACKAGE
        } else if (isPackageInstalled(context.packageManager, WHATSAPP_BUSINESS_PACKAGE)) {
            WHATSAPP_BUSINESS_PACKAGE
        } else {
            null
        }

        return try {
            if (isPhoneNumber && targetPackage != null) {
                // Direct WhatsApp conversation URL
                val cleanPhone = cleanContact.replace("+", "")
                val uriString = "https://api.whatsapp.com/send?phone=$cleanPhone" +
                        if (textToSend.isNotEmpty()) "&text=${Uri.encode(textToSend)}" else ""
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                    setPackage(targetPackage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                WhatsAppResult(
                    success = true,
                    spokenMessage = "Opening WhatsApp to send message to $cleanContact.",
                    contact = cleanContact,
                    messageText = textToSend
                )
            } else {
                // Native share intent targeting WhatsApp
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    if (targetPackage != null) {
                        setPackage(targetPackage)
                    }
                    if (textToSend.isNotEmpty()) {
                        putExtra(Intent.EXTRA_TEXT, textToSend)
                    }
                    putExtra("android.intent.extra.TEXT", textToSend)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (targetPackage != null) {
                    context.startActivity(shareIntent)
                    val spoken = if (textToSend.isNotEmpty()) {
                        "Opening WhatsApp to send \"$textToSend\" to $cleanContact."
                    } else {
                        "Opening WhatsApp for $cleanContact."
                    }
                    WhatsAppResult(
                        success = true,
                        spokenMessage = spoken,
                        contact = cleanContact,
                        messageText = textToSend
                    )
                } else {
                    // Fallback to system share chooser if WhatsApp isn't directly resolved
                    val chooser = Intent.createChooser(shareIntent, "Send WhatsApp to $cleanContact").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                    WhatsAppResult(
                        success = true,
                        spokenMessage = "WhatsApp isn't directly installed. Opening share options for $cleanContact.",
                        contact = cleanContact,
                        messageText = textToSend
                    )
                }
            }
        } catch (e: Exception) {
            AssistantLogger.w(TAG, "WhatsApp intent failed: ${e.message}")
            WhatsAppResult(
                success = false,
                spokenMessage = "Could not open WhatsApp for $cleanContact.",
                contact = cleanContact,
                messageText = textToSend
            )
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
                val contact = matcher.group(1).trim()
                val message = if (matcher.groupCount() >= 2) matcher.group(2).trim() else null
                return Pair(contact, message)
            }
        }

        return null
    }
}
