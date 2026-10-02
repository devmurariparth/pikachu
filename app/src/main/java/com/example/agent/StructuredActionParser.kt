package com.example.agent

import com.example.PlannedAction
import com.example.action.PlannedActionMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.parseToJsonElement

object StructuredActionParser {
    private val json = Json { isLenient = false; ignoreUnknownKeys = false }
    private val requiredKeys = setOf("action", "payload", "speech", "lang")
    private val supportedLanguages = setOf("gu", "hi", "en")

    fun parse(response: String, detectedLanguage: String): Result<PlannedAction> {
        val obj = try {
            json.parseToJsonElement(response).jsonObject
        } catch (_: Exception) {
            return invalid(AiProviderErrorCategory.INVALID_API_RESPONSE, "The AI returned malformed structured data.")
        }
        if (obj.keys != requiredKeys) {
            return invalid(AiProviderErrorCategory.INVALID_API_RESPONSE, "The AI response is missing required fields or contains unexpected data.")
        }

        val action = obj.stringField("action")
            ?: return invalid(AiProviderErrorCategory.INVALID_API_RESPONSE, "The AI response has an invalid action field.")
        if (runCatching { com.example.action.ActionName.valueOf(action) }.isFailure) {
            return invalid(AiProviderErrorCategory.ACTION_ERROR, "The AI suggested an unsupported action.")
        }
        val payloadValue = obj["payload"]
        val payload = when (payloadValue) {
            JsonNull -> null
            is JsonPrimitive -> if (payloadValue.isString) payloadValue.content else {
                return invalid(AiProviderErrorCategory.ACTION_ERROR, "The AI action payload has an invalid type.")
            }
            else -> return invalid(AiProviderErrorCategory.ACTION_ERROR, "The AI action payload has an invalid type.")
        }
        val speech = obj.stringField("speech")?.takeIf(String::isNotBlank)
            ?: return invalid(AiProviderErrorCategory.INVALID_API_RESPONSE, "The AI response has no user-facing speech.")
        val responseLanguage = obj.stringField("lang")
        if (responseLanguage == null || responseLanguage !in supportedLanguages) {
            return invalid(AiProviderErrorCategory.INVALID_API_RESPONSE, "The AI response has an unsupported language.")
        }
        val language = detectedLanguage.takeIf { it in supportedLanguages } ?: responseLanguage
        val planned = PlannedAction(action, payload, speech, language)
        val mapped = PlannedActionMapper.map(planned, "VALIDATION")
        if (mapped.isFailure) {
            val safeMessage = mapped.exceptionOrNull()?.message
                ?.takeIf(String::isNotBlank)
                ?: "The AI action has an invalid payload."
            return invalid(AiProviderErrorCategory.ACTION_ERROR, safeMessage)
        }
        return Result.success(planned)
    }

    private fun JsonObject.stringField(name: String): String? {
        val value = this[name] as? JsonPrimitive ?: return null
        return if (value.isString) value.contentOrNull else null
    }

    private fun invalid(category: AiProviderErrorCategory, message: String): Result<PlannedAction> =
        Result.failure(AiProviderException(category, message, retryable = false))
}
