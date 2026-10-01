package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.AssistantLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Serializable
data class UserMemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val category: String = "Preference",
    val timestamp: Long = System.currentTimeMillis(),
    val isSensitive: Boolean = false,
    val explicitlyConsented: Boolean = true
) {
    fun getFormattedDate(): String {
        return SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(timestamp))
    }
}

enum class MemoryCategory(val displayName: String) {
    PREFERENCE("Preference"),
    FOOD_DIET("Food & Diet"),
    RESPONSE_STYLE("Response Style"),
    PERSONAL_INFO("Personal Info"),
    ROUTINES("Routines & Habits")
}

data class SensitiveDetectionResult(
    val isSensitive: Boolean,
    val detectedType: String? = null,
    val warningMessage: String? = null
)

sealed class SaveMemoryResult {
    data class Success(val item: UserMemoryItem, val message: String) : SaveMemoryResult()
    data class RequiresSensitiveConsent(
        val candidateText: String,
        val sensitiveType: String,
        val warning: String
    ) : SaveMemoryResult()
    data class Disabled(val message: String) : SaveMemoryResult()
    data class AlreadyExists(val existing: UserMemoryItem) : SaveMemoryResult()
    object Empty : SaveMemoryResult()
}

sealed class ForgetMemoryResult {
    data class ItemRemoved(val item: UserMemoryItem, val message: String) : ForgetMemoryResult()
    data class AllCleared(val count: Int, val message: String) : ForgetMemoryResult()
    data class NotFound(val target: String, val message: String) : ForgetMemoryResult()
    object EmptyMemory : ForgetMemoryResult()
}

object UserMemoryManager {
    private const val TAG = "UserMemoryManager"
    private const val PREFS_NAME = "mj_assistant_memory"
    private const val KEY_MEMORY_ENABLED = "memory_enabled"
    private const val KEY_MEMORIES_JSON = "memories_json"

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val _isMemoryEnabled = MutableStateFlow(true)
    val isMemoryEnabled: StateFlow<Boolean> = _isMemoryEnabled.asStateFlow()

    private val _memories = MutableStateFlow<List<UserMemoryItem>>(emptyList())
    val memories: StateFlow<List<UserMemoryItem>> = _memories.asStateFlow()

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadMemories()
        }
    }

    private fun loadMemories() {
        _isMemoryEnabled.value = prefs.getBoolean(KEY_MEMORY_ENABLED, true)
        val jsonStr = prefs.getString(KEY_MEMORIES_JSON, "[]") ?: "[]"
        try {
            val list = json.decodeFromString<List<UserMemoryItem>>(jsonStr)
            _memories.value = list
            AssistantLogger.i(TAG, "Loaded ${list.size} user memories. Memory enabled: ${_isMemoryEnabled.value}")
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "Error decoding saved memories", e)
            _memories.value = emptyList()
        }
    }

    private fun saveToDisk(list: List<UserMemoryItem>) {
        if (!::prefs.isInitialized) return
        try {
            val jsonStr = json.encodeToString(list)
            prefs.edit().putString(KEY_MEMORIES_JSON, jsonStr).apply()
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "Error encoding memories to disk", e)
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        _isMemoryEnabled.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_MEMORY_ENABLED, enabled).apply()
            AssistantLogger.i(TAG, "Memory enabled changed to $enabled")
        }
    }

    /**
     * Inspects text for sensitive information (passwords, payment cards, SSN, bank accounts, confidential credentials).
     */
    fun detectSensitivity(text: String): SensitiveDetectionResult {
        val lower = text.lowercase(Locale.ROOT)

        // Passwords / PINs / Security Codes
        val passwordKeywords = listOf("password", "passcode", "pwd", "secret code", "access code", "security code")
        for (kw in passwordKeywords) {
            if (lower.contains(kw)) {
                return SensitiveDetectionResult(
                    isSensitive = true,
                    detectedType = "Password or Security Code",
                    warningMessage = "This appears to contain a password, passcode, or security credential."
                )
            }
        }

        val pinRegex = Regex("""\b(?:pin|pin code)\s*(?:is|:)?\s*\d{3,8}\b""", RegexOption.IGNORE_CASE)
        if (pinRegex.containsMatchIn(text)) {
            return SensitiveDetectionResult(
                isSensitive = true,
                detectedType = "PIN Code",
                warningMessage = "This appears to contain a personal identification number (PIN)."
            )
        }

        // Financial & Payment Data
        val financialKeywords = listOf("credit card", "debit card", "card number", "cvv", "cvc", "bank account", "routing number", "iban", "swift code")
        for (kw in financialKeywords) {
            if (lower.contains(kw)) {
                return SensitiveDetectionResult(
                    isSensitive = true,
                    detectedType = "Financial / Payment Information",
                    warningMessage = "This appears to contain banking or payment card details."
                )
            }
        }

        val creditCardRegex = Regex("""\b(?:\d[ -]*?){13,16}\b""")
        if (creditCardRegex.containsMatchIn(text) && (lower.contains("card") || lower.contains("pay") || lower.contains("visa") || lower.contains("mastercard"))) {
            return SensitiveDetectionResult(
                isSensitive = true,
                detectedType = "Credit / Debit Card Number",
                warningMessage = "This appears to contain a credit or debit card number."
            )
        }

        // Social Security / National ID
        val ssnKeywords = listOf("social security", "ssn", "tax id", "national id")
        for (kw in ssnKeywords) {
            if (lower.contains(kw)) {
                return SensitiveDetectionResult(
                    isSensitive = true,
                    detectedType = "Social Security or National ID",
                    warningMessage = "This appears to contain a government-issued identification or SSN."
                )
            }
        }

        val ssnRegex = Regex("""\b\d{3}-\d{2}-\d{4}\b""")
        if (ssnRegex.containsMatchIn(text)) {
            return SensitiveDetectionResult(
                isSensitive = true,
                detectedType = "Social Security Number",
                warningMessage = "This appears to contain a social security number pattern."
            )
        }

        // Health / Medical Data
        val medicalKeywords = listOf("medication", "medical", "prescription", "health condition", "blood pressure", "diagnosis", "doctor", "illness", "disease")
        for (kw in medicalKeywords) {
            if (lower.contains(kw)) {
                return SensitiveDetectionResult(
                    isSensitive = true,
                    detectedType = "Health / Medical Data",
                    warningMessage = "This appears to contain personal health or medical information."
                )
            }
        }

        return SensitiveDetectionResult(isSensitive = false)
    }

    /**
     * Determines a suitable category for a remembered preference based on its text content.
     */
    fun categorizeText(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("food") || lower.contains("eat") || lower.contains("vegetarian") ||
            lower.contains("vegan") || lower.contains("diet") || lower.contains("allergic") ||
            lower.contains("allergy") || lower.contains("coffee") || lower.contains("tea") ||
            lower.contains("drink") -> "Food & Diet"

            lower.contains("concise") || lower.contains("brief") || lower.contains("short") ||
            lower.contains("detailed") || lower.contains("bullet") || lower.contains("speak") ||
            lower.contains("voice") || lower.contains("language") || lower.contains("french") ||
            lower.contains("spanish") || lower.contains("german") -> "Response Style"

            lower.contains("name") || lower.contains("call me") || lower.contains("birthday") ||
            lower.contains("live in") || lower.contains("work at") || lower.contains("job") -> "Personal Info"

            lower.contains("alarm") || lower.contains("morning") || lower.contains("night") ||
            lower.contains("routine") || lower.contains("every day") || lower.contains("usually") -> "Routines & Habits"

            else -> "Preference"
        }
    }

    /**
     * Saves a user-approved preference to local memory.
     * Respects the strict privacy rule: never stores sensitive data without explicit consent.
     */
    fun rememberPreference(
        rawText: String,
        category: String? = null,
        allowSensitiveIfConsented: Boolean = false
    ): SaveMemoryResult {
        if (!_isMemoryEnabled.value) {
            return SaveMemoryResult.Disabled("Personalized memory is currently turned off in Settings.")
        }

        val cleanText = cleanRememberQuery(rawText)
        if (cleanText.isBlank()) {
            return SaveMemoryResult.Empty
        }

        // Check for sensitive content
        val sensitivity = detectSensitivity(cleanText)
        if (sensitivity.isSensitive && !allowSensitiveIfConsented) {
            return SaveMemoryResult.RequiresSensitiveConsent(
                candidateText = cleanText,
                sensitiveType = sensitivity.detectedType ?: "Confidential Data",
                warning = sensitivity.warningMessage ?: "This appears to contain sensitive information. Storing sensitive data requires your explicit consent."
            )
        }

        val existingList = _memories.value
        // Check if an identical memory already exists
        val duplicate = existingList.find { it.text.equals(cleanText, ignoreCase = true) }
        if (duplicate != null) {
            return SaveMemoryResult.AlreadyExists(duplicate)
        }

        val determinedCategory = category ?: categorizeText(cleanText)
        val newItem = UserMemoryItem(
            id = UUID.randomUUID().toString(),
            text = cleanText,
            category = determinedCategory,
            timestamp = System.currentTimeMillis(),
            isSensitive = sensitivity.isSensitive,
            explicitlyConsented = true
        )

        val updated = existingList + newItem
        _memories.value = updated
        saveToDisk(updated)
        AssistantLogger.i(TAG, "Saved a user memory [${newItem.category}, sensitive=${newItem.isSensitive}]")

        return SaveMemoryResult.Success(
            item = newItem,
            message = "I've remembered that: \"$cleanText\"."
        )
    }

    /**
     * Strips leading conversational prefixes like "remember that", "remember", "please remember".
     */
    fun cleanRememberQuery(query: String): String {
        var text = query.trim()
        val prefixes = listOf(
            "please remember that",
            "please remember to",
            "please remember",
            "remember that",
            "remember to",
            "remember my",
            "remember me as",
            "remember",
            "note that",
            "keep in mind that",
            "don't forget that",
            "dont forget that",
            "save preference that",
            "save to memory that",
            "save to memory"
        )
        for (prefix in prefixes) {
            if (text.startsWith(prefix, ignoreCase = true)) {
                text = text.substring(prefix.length).trim()
                break
            }
        }
        // Capitalize first character for clean presentation
        return if (text.isNotEmpty()) text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } else text
    }

    /**
     * Forgets memories matching the target string.
     * Supports "all" or "everything" to clear all memories.
     */
    fun forgetMemory(target: String): ForgetMemoryResult {
        val existing = _memories.value
        if (existing.isEmpty()) {
            return ForgetMemoryResult.EmptyMemory
        }

        val cleanTarget = target.trim().lowercase(Locale.ROOT)
            .removePrefix("that ")
            .removePrefix("my ")
            .trim()

        if (cleanTarget == "all" || cleanTarget == "everything" || cleanTarget == "all memories" || cleanTarget == "all preferences") {
            val count = existing.size
            _memories.value = emptyList()
            saveToDisk(emptyList())
            AssistantLogger.i(TAG, "Cleared all $count memories upon user request")
            return ForgetMemoryResult.AllCleared(count, "I've cleared all $count of your remembered preferences.")
        }

        // Search for closest matching memory item
        val matchingItem = existing.find { item ->
            val itemLower = item.text.lowercase(Locale.ROOT)
            itemLower.contains(cleanTarget) || cleanTarget.contains(itemLower)
        }

        if (matchingItem != null) {
            val updated = existing.filter { it.id != matchingItem.id }
            _memories.value = updated
            saveToDisk(updated)
            AssistantLogger.i(TAG, "Removed a user memory")
            return ForgetMemoryResult.ItemRemoved(
                item = matchingItem,
                message = "I've forgotten: \"${matchingItem.text}\"."
            )
        }

        return ForgetMemoryResult.NotFound(
            target = target,
            message = "I couldn't find a remembered preference matching \"$target\". Say 'What do you remember?' to see all saved items."
        )
    }

    /**
     * Forgets a specific memory by its unique ID.
     */
    fun forgetMemoryById(id: String): Boolean {
        val existing = _memories.value
        val item = existing.find { it.id == id } ?: return false
        val updated = existing.filter { it.id != id }
        _memories.value = updated
        saveToDisk(updated)
        AssistantLogger.i(TAG, "Removed a user memory by ID")
        return true
    }

    /**
     * Clears all stored memories.
     */
    fun clearAllMemories(): Int {
        val count = _memories.value.size
        _memories.value = emptyList()
        saveToDisk(emptyList())
        AssistantLogger.i(TAG, "Cleared all memories ($count items)")
        return count
    }

    /**
     * Formats remembered preferences into a clean natural language summary for voice or chat.
     */
    fun getMemoriesSummaryText(): String {
        if (!_isMemoryEnabled.value) {
            return "Personalized memory is currently turned off in Settings. When turned on, I only remember preferences you explicitly ask me to save."
        }
        val list = _memories.value
        if (list.isEmpty()) {
            return "I don't have any remembered preferences yet. You can ask me anytime, for example: 'Remember that I prefer coffee' or 'Remember that I like concise answers'."
        }

        val sb = StringBuilder()
        sb.append("Here is what I currently remember about your preferences (${list.size} item${if (list.size > 1) "s" else ""}):\n")
        list.forEachIndexed { index, item ->
            val sensitiveTag = if (item.isSensitive) " [Explicitly Consented]" else ""
            sb.append("${index + 1}. ${item.text}$sensitiveTag\n")
        }
        sb.append("\nYou can say 'Forget [item]' or 'Forget all' to remove any preference at any time.")
        return sb.toString().trim()
    }

    /**
     * Formats remembered preferences as instructions to be injected into Gemini prompt.
     */
    fun getFormattedMemoriesForContext(): String {
        if (!_isMemoryEnabled.value) return ""
        val list = _memories.value
        if (list.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n\nUSER'S REMEMBERED PREFERENCES (User-Approved Memory):\n")
        sb.append("The user has explicitly asked you to remember the following preferences. Always tailor your responses, recommendations, and actions to respect them:\n")
        list.forEach { item ->
            sb.append("- ${item.text} (${item.category})\n")
        }
        return sb.toString()
    }
}
