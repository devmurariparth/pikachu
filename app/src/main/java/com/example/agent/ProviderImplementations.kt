package com.example.agent

import com.example.ActionPlanner
import com.example.AssistantLogger
import com.example.PlannedAction
import com.example.data.AppSettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiAiProvider : AiProvider {
    override val id = AiProviderId.GEMINI
    override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> =
        runCatching { ActionPlanner.plan(command).getOrThrow() }
}

class OpenAiProvider(
    private val apiKeyProvider: () -> String = { AppSettingsManager.getOpenAiApiKey() },
    private val model: String = DEFAULT_MODEL
) : AiProvider {
    override val id = AiProviderId.OPENAI

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> {
        val key = apiKeyProvider()
        if (key.isBlank()) return Result.failure(IllegalStateException("OpenAI provider is not configured."))
        var last: Throwable? = null
        repeat(config.maxRetries + 1) { attempt ->
            try {
                return withTimeout(config.timeoutMs) { Result.success(call(key, command, language)) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                last = t
                if (attempt < config.maxRetries) delay(250L * (attempt + 1))
            }
        }
        return Result.failure(last ?: IllegalStateException("OpenAI request failed."))
    }

    private fun call(key: String, command: String, language: String): PlannedAction {
        val prompt = """Return ONLY valid JSON:
{"action":"ACTION_TYPE","payload":"string or null","speech":"natural concise response","lang":"gu|hi|en"}
Supported ACTION_TYPE: OPEN_APP, SEARCH_WEB, OPEN_URL, PLAY_MUSIC, CALL, SEND_SMS, OPEN_SETTINGS, NAVIGATE, SET_ALARM, SET_TIMER, GO_HOME, GO_BACK, OPEN_NOTIFICATIONS, RECENT_APPS, TOGGLE_WIFI, TOGGLE_BLUETOOTH, SET_BRIGHTNESS, OPEN_QUICK_SETTINGS, SEND_WHATSAPP, CREATE_TASK, COMPLETE_TASK, REMEMBER_PREFERENCE, SHOW_MEMORIES, FORGET_MEMORY, CHAT.
User language: $language
User command: $command"""

        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("input", JsonPrimitive(prompt))
            put("text", buildJsonObject {
                put("format", buildJsonObject { put("type", JsonPrimitive("json_object")) })
            })
        }.toString()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AssistantLogger.w("OpenAiProvider", "Provider request failed with HTTP " + response.code)
                throw IllegalStateException("OpenAI provider request failed.")
            }
            val raw = response.body?.string().orEmpty()
            val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(raw).jsonObject
            val text = findFirstText(json) ?: throw IllegalStateException("OpenAI returned no structured text.")
            val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(text).jsonObject
            return PlannedAction(
                action = obj["action"]?.toString()?.trim('"') ?: "CHAT",
                payload = obj["payload"]?.let { if (it is JsonPrimitive && it.isString) it.content else null },
                speechResponse = obj["speech"]?.toString()?.trim('"') ?: "I couldn't complete that.",
                language = obj["lang"]?.toString()?.trim('"')?.takeIf { it in setOf("gu", "hi", "en") } ?: language
            )
        }
    }

    private fun findFirstText(node: JsonObject): String? {
        node["output_text"]?.let { if (it is JsonPrimitive && it.isString) return it.content }
        node["output"]?.let { output ->
            output.jsonArray.forEach { item ->
                item.jsonObject["content"]?.jsonArray?.forEach { content ->
                    val text = content.jsonObject["text"]
                    if (text is JsonPrimitive && text.isString) return text.content
                }
            }
        }
        return null
    }

    companion object {
        private const val DEFAULT_MODEL = "gpt-5.6-luna"
    }
}
