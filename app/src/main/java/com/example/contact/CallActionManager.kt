package com.example.contact

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.AssistantLogger

data class CallContactOption(
    val displayName: String,
    val phoneNumber: String,
    val typeLabel: String
)

data class CallDisambiguation(
    val title: String,
    val prompt: String,
    val options: List<CallContactOption>
)

sealed class CallExecutionResult {
    data class Started(val contactName: String) : CallExecutionResult()
    data class DialerOpened(val contactName: String, val reason: String) : CallExecutionResult()
    data class PermissionRequired(val contactName: String, val phoneNumber: String) : CallExecutionResult()
    data class Failed(val reason: String) : CallExecutionResult()
}

object CallActionManager {
    private const val TAG = "CallActionManager"

    /**
     * Parses a user text/speech input to detect a CALL action.
     * Returns the extracted contact name or phone number string, or null if this is not a call command.
     */
    fun parseCallCommand(rawInput: String): String? {
        val trimmed = rawInput.trim().trimEnd('.', '?', '!')
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase()

        // Conversational leading phrases to strip
        val conversationalPrefixes = listOf(
            "hey mj,", "hey mj", "ok mj,", "ok mj", "mj,", "mj",
            "please", "can you please", "could you please", "can you", "could you",
            "would you please", "would you", "i want to", "i need to", "help me"
        )

        var cleaned = lower
        var stripped = true
        while (stripped) {
            stripped = false
            for (prefix in conversationalPrefixes) {
                if (cleaned.startsWith("$prefix ")) {
                    cleaned = cleaned.removePrefix("$prefix ").trim()
                    stripped = true
                    break
                }
            }
        }

        // Action verbs: "call", "phone", "dial", "place a call to", "make a call to", "ring"
        val callVerbs = listOf(
            "place a call to ",
            "make a call to ",
            "place a call ",
            "make a call ",
            "call ",
            "phone ",
            "dial ",
            "ring "
        )

        val matchingVerb = callVerbs.firstOrNull { cleaned.startsWith(it) } ?: run {
            if (cleaned == "call" || cleaned == "phone" || cleaned == "dial") {
                return ""
            }
            return null
        }

        var target = cleaned.removePrefix(matchingVerb).trim()

        // Strip relationship / filler prefixes: "my friend", "my brother", "my mom", "my", "the"
        val relationshipPrefixes = listOf(
            "my friend ",
            "friend ",
            "my brother ",
            "my sister ",
            "my father ",
            "my mother ",
            "my dad ",
            "my mom ",
            "my ",
            "the "
        )
        for (rel in relationshipPrefixes) {
            if (target.startsWith(rel)) {
                if (rel == "my mom ") {
                    target = "mom"
                } else {
                    target = target.removePrefix(rel).trim()
                }
                break
            }
        }

        return target
    }

    /**
     * Checks whether CALL_PHONE permission is granted.
     */
    fun hasCallPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Initiates the phone call using Android Intent.ACTION_CALL.
     * If CALL_PHONE permission is not granted, returns PermissionRequired.
     * If SecurityException occurs, safely falls back to ACTION_DIAL.
     */
    fun executeCall(context: Context, contactDisplayName: String, phoneNumber: String): CallExecutionResult {
        val cleanNumber = phoneNumber.trim()
        if (cleanNumber.isBlank()) {
            return CallExecutionResult.Failed("Phone number is empty.")
        }

        if (!hasCallPermission(context)) {
            AssistantLogger.w(TAG, "CALL_PHONE permission is not granted. Request needed.")
            return CallExecutionResult.PermissionRequired(contactDisplayName, cleanNumber)
        }

        return try {
            val callUri = Uri.parse("tel:${Uri.encode(cleanNumber)}")
            val callIntent = Intent(Intent.ACTION_CALL, callUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
            AssistantLogger.i(TAG, "ACTION_CALL dispatched")
            CallExecutionResult.Started(contactDisplayName)
        } catch (e: SecurityException) {
            AssistantLogger.e(TAG, "SecurityException during ACTION_CALL. Falling back to dialer.", e)
            openDialerFallback(context, cleanNumber)
            CallExecutionResult.DialerOpened(contactDisplayName, "Permission restricted; opened dialer.")
        } catch (e: ActivityNotFoundException) {
            AssistantLogger.e(TAG, "ActivityNotFoundException: No dialer/call activity found.", e)
            CallExecutionResult.Failed("No phone application is available on this device.")
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "Unexpected error during ACTION_CALL", e)
            CallExecutionResult.Failed(e.message ?: "Unable to place call.")
        }
    }

    /**
     * Safely opens the system dialer with the phone number pre-filled.
     */
    fun openDialerFallback(context: Context, phoneNumber: String): Boolean {
        return try {
            val dialUri = Uri.parse("tel:${Uri.encode(phoneNumber.trim())}")
            val dialIntent = Intent(Intent.ACTION_DIAL, dialUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            true
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "Failed to launch ACTION_DIAL fallback", e)
            false
        }
    }
}
