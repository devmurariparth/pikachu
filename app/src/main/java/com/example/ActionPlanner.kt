package com.example

import com.example.network.Content
import com.example.network.GenerateContentRequest
import com.example.network.Part
import com.example.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class PlannedAction(
    val action: String,
    val payload: String?,
    val speechResponse: String,
    val language: String = "en"
)

object ActionPlanner {
    private val systemPrompt = """
        You are MJ, a next-generation Android AI Assistant built for production use. Your identity is permanently MJ. Never rename yourself.
        Your mission is to provide the fastest, smartest, most reliable Android assistant experience possible while respecting Android security, permissions, privacy, and platform limitations.

        Determine the user's intent and output exactly ONE JSON object (and nothing else, no markdown) containing the following fields:
        {
          "action": "<ACTION_TYPE>",
          "payload": "<REQUIRED_DATA>",
          "speech": "<NATURAL_SPOKEN_RESPONSE>",
          "lang": "<TWO_LETTER_ISO_LANGUAGE_CODE>"
        }
        
        ACTION_TYPE can be:
        - OPEN_APP (payload: android package name like 'com.whatsapp', 'com.instagram.android', 'com.google.android.youtube', 'com.android.chrome')
        - SEARCH_WEB (payload: the search query)
        - OPEN_URL (payload: https URL to open in browser)
        - CALL (payload: phone number or contact name)
        - SEND_SMS (payload: 'contact|message' or 'number|message' - opens SMS app with recipient and text prefilled)
        - OPEN_SETTINGS (payload: null)
        - NAVIGATE (payload: destination name)
        - SET_ALARM (payload: hour in 24h format as string, e.g. "7")
        - SET_TIMER (payload: duration in minutes as string, e.g. "5")
        - GO_HOME (payload: null)
        - GO_BACK (payload: null)
        - OPEN_NOTIFICATIONS (payload: null)
        - RECENT_APPS (payload: null)
        - TOGGLE_WIFI (payload: 'on', 'off', or 'toggle' - for Wi-Fi system control)
        - TOGGLE_BLUETOOTH (payload: 'on', 'off', or 'toggle' - for Bluetooth system control)
        - SET_BRIGHTNESS (payload: percentage 0-100 like '80' - for screen brightness control)
        - OPEN_QUICK_SETTINGS (payload: null - opens system quick settings panel)
        - SEND_WHATSAPP (payload: 'contact' or 'contact|message' - triggers native share intent for WhatsApp)
        - CREATE_TASK (payload: 'task title and optional reminder time' - persists task in Room and schedules reminder via WorkManager)
        - COMPLETE_TASK (payload: 'task title' - marks task done in Room)
        - REMEMBER_PREFERENCE (payload: the preference or fact user asked you to remember)
        - SHOW_MEMORIES (payload: null - for queries like 'what do you remember', 'show memories')
        - FORGET_MEMORY (payload: the item to forget, or 'all'/'everything' to clear all)
        - CHAT (payload: null - for conversational answers, general queries, or clarification)
        
        CRITICAL RULES:
        1. When asked who or what you are, proudly identify as MJ, the next-generation Android AI Assistant.
        2. AUTOMATIC LANGUAGE DETECTION: Detect the language used by the user automatically (e.g. English, Spanish, Hindi, French, German, Japanese, Chinese, Arabic, Portuguese, etc.). Always respond in kind in the exact same language in the "speech" field, and set "lang" to the 2-letter ISO language code (e.g. "en", "es", "hi", "fr", "de", "ja", "zh", "ar", "pt").
        3. CLARIFICATION FOR UNCLEAR SPEECH: If the user speech is unclear, mumbled, incomplete, or missing critical details (e.g. "call" or "text" with no recipient), use the "CHAT" action and ask for clarification naturally and conversationally in the user's language (e.g., "Who would you like me to call?", "I didn't catch that, could you please repeat?").
        4. USER-APPROVED MEMORY & PREFERENCES:
           - Only remember preferences when explicitly asked (e.g., "Remember that I am vegetarian", "Remember my name is Alex", "Please remember I like short answers"). Use REMEMBER_PREFERENCE.
           - When user asks "What do you remember?", "What are my memories?", or "Show what you remember", use SHOW_MEMORIES.
           - When user asks "Forget [X]" or "Forget all", use FORGET_MEMORY with the payload matching the item or "all".
           - NEVER store sensitive credentials (passwords, PINs, bank accounts, credit cards) without explicit consent.
        5. Keep spoken responses natural, conversational, friendly, and concise.
        6. If the intent is impossible, unsupported, or unsafe, use "CHAT" and explain clearly while respecting Android platform security and privacy.
    """.trimIndent()

    private fun getCompleteSystemPrompt(): String {
        val memoryContext = com.example.data.UserMemoryManager.getFormattedMemoriesForContext()
        return if (memoryContext.isNotEmpty()) {
            systemPrompt + memoryContext
        } else {
            systemPrompt
        }
    }

    suspend fun plan(query: String): Result<PlannedAction> = withContext(Dispatchers.IO) {
        val taskId = "PLANNER_${System.currentTimeMillis()}"
        AssistantLogger.i(taskId, "Planning action for query: '$query'")
        
        val apiKey = com.example.data.AppSettingsManager.getActiveApiKey().trim()
        if (apiKey.isEmpty()) {
            AssistantLogger.w(taskId, "No Gemini API key configured")
            return@withContext Result.failure(
                AssistantException(
                    ErrorCategory.PERMISSION_ERROR,
                    "Gemini API key is not configured. Please add your API key in Settings.",
                    canRetry = false
                )
            )
        }

        val apiResult = com.example.network.safeApiCall {
            withTimeout(45000L) {
                val request = GenerateContentRequest(
                    contents = listOf(Content(parts = listOf(Part(text = query)), role = "user")),
                    systemInstruction = Content(parts = listOf(Part(text = getCompleteSystemPrompt()))),
                    generationConfig = com.example.network.GenerationConfig(temperature = 0.2f)
                )
                
                RetrofitClient.service.generateFlashContent(
                    apiKey = apiKey,
                    request = request
                )
            }
        }
        
        when (apiResult) {
            is com.example.network.ApiResult.Success -> {
                val result = apiResult.data
                val jsonText = result.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text 
                    ?: return@withContext Result.failure(AssistantException(ErrorCategory.AI_SERVICE_ERROR, "Invalid response from AI.", canRetry = true))
                
                AssistantLogger.d(taskId, "Raw AI response: $jsonText")
                
                val cleanJson = jsonText.replace("```json", "").replace("```", "").trim()

                var action = "CHAT"
                var payload: String? = null
                var speech = "Done."
                var language = "en"

                val jsonParsed = try {
                    val firstBrace = cleanJson.indexOf('{')
                    val lastBrace = cleanJson.lastIndexOf('}')
                    if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                        val jsonSubstring = cleanJson.substring(firstBrace, lastBrace + 1)
                        val jsonObj = org.json.JSONObject(jsonSubstring)
                        action = jsonObj.optString("action", "CHAT")
                        payload = if (jsonObj.isNull("payload")) null else jsonObj.optString("payload").takeIf { it.isNotBlank() && it != "null" }
                        speech = jsonObj.optString("speech", "Done.")
                        language = jsonObj.optString("lang", "en").lowercase()
                        true
                    } else false
                } catch (e: Exception) {
                    false
                }

                if (!jsonParsed) {
                    val actionMatch = "\"action\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(cleanJson)
                    val payloadMatch = "\"payload\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(cleanJson)
                    val speechMatch = "\"speech\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(cleanJson)
                    val langMatch = "\"lang\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(cleanJson)

                    action = actionMatch?.groupValues?.get(1) ?: "CHAT"
                    payload = payloadMatch?.groupValues?.get(1).takeIf { it != "null" && it != "" }
                    speech = speechMatch?.groupValues?.get(1) ?: "Done."
                    language = langMatch?.groupValues?.get(1)?.lowercase() ?: "en"
                }

                val plannedAction = PlannedAction(action, payload, speech, language)
                AssistantLogger.i(taskId, "Planned action: $plannedAction")
                Result.success(plannedAction)
            }
            is com.example.network.ApiResult.Error -> {
                AssistantLogger.w(taskId, "API Error: ${apiResult.message}")
                com.example.GlobalErrorHandler.handleError(apiResult.category, apiResult.message)
                val canRetry = apiResult.category == ErrorCategory.NETWORK_ERROR || apiResult.category == ErrorCategory.TIMEOUT_ERROR || apiResult.category == ErrorCategory.AI_SERVICE_ERROR
                Result.failure(AssistantException(apiResult.category, apiResult.message, canRetry = canRetry))
            }
        }
    }
}
