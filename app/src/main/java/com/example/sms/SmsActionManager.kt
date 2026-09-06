package com.example.sms

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.AssistantLogger
import com.example.contact.ContactsManager

data class SmsResult(
    val success: Boolean,
    val spokenMessage: String
)

object SmsActionManager {
    private const val TAG = "SmsActionManager"

    fun parseSmsCommand(raw: String): Pair<String, String?>? {
        val trimmed = raw.trim()
        val patterns = listOf(
            // "send sms to [contact] saying [message]"
            "(?i)^send\\s+(?:an?\\s+)?(?:sms|text(?:\\s+message)?)\\s+to\\s+([^:,]+?)(?:\\s+(?:that|saying|with|message|msg)\\s+|:\\s*)(.+)$",
            // "text [contact] [message]"
            "(?i)^text\\s+([^:,]+?)(?:\\s+(?:that|saying|with|message|msg)\\s+|:\\s*)(.+)$",
            // "send sms to [contact]"
            "(?i)^send\\s+(?:an?\\s+)?(?:sms|text(?:\\s+message)?)\\s+to\\s+([^:,]+)$",
            // "text [contact]"
            "(?i)^text\\s+([^:,]+)$"
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

    fun sendSms(context: Context, recipientQuery: String, message: String?): SmsResult {
        return try {
            val lookup = ContactsManager.findContactPhoneNumber(context, recipientQuery)
            val number = lookup.phoneNumber ?: recipientQuery.filter { it.isDigit() || it == '+' }
            val recipientLabel = lookup.contactName ?: recipientQuery

            val uri = if (number.isNotBlank()) Uri.parse("smsto:$number") else Uri.parse("smsto:")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                if (!message.isNullOrBlank()) {
                    putExtra("sms_body", message)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            val spoken = if (!message.isNullOrBlank()) {
                "Opening messages to text $recipientLabel: \"$message\"."
            } else {
                "Opening messages for $recipientLabel."
            }
            SmsResult(true, spoken)
        } catch (e: ActivityNotFoundException) {
            AssistantLogger.w(TAG, "No SMS app found")
            SmsResult(false, "No messaging application is installed on this device.")
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "Failed to launch SMS intent", e)
            SmsResult(false, "Unable to open messaging app: ${e.message}")
        }
    }
}
