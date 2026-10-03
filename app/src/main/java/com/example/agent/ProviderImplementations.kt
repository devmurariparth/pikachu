package com.example.agent

import com.example.ActionPlanner
import com.example.AssistantLogger
import com.example.PlannedAction
import com.example.data.AppSettingsManager
import com.example.network.GeminiApiService
import com.example.network.InteractionResponse
import com.example.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GeminiAiProvider(
    override val id: AiProviderId = AiProviderId.GEMINI,
    private val modelId: String = AiModelConfig.PRIMARY_PLANNER,
    private val apiKeyProvider: () -> String = { AppSettingsManager.getActiveApiKey() },
    private val serviceProvider: () -> GeminiApiService = { RetrofitClient.service },
    private val retryDelay: suspend (Long) -> Unit = { delay(it) }
) : AiProvider, AgentPlanProvider {
    override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> {
        val key = runCatching { apiKeyProvider().trim() }.getOrDefault("")
        if (key.isBlank()) return failure(
            AiProviderErrorCategory.NO_API_KEY,
            "Add a Gemini API key in Settings, or configure another AI provider.",
            retryable = false
        )

        val request = try {
            ActionPlanner.buildRequest(command, language, modelId)
        } catch (_: Exception) {
            return failure(AiProviderErrorCategory.ACTION_ERROR, "MJ couldn't prepare the AI request.", retryable = false)
        }

        var attempt = 0
        val maxRetries = config.maxRetries.coerceIn(0, MAX_RETRIES)
        while (true) {
            val result = try {
                val response = withTimeout(config.timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)) {
                    serviceProvider().createInteraction(key, request)
                }
                val text = response.plannerText()
                    ?: return failure(
                        AiProviderErrorCategory.INVALID_API_RESPONSE,
                        "The AI service returned no structured action.",
                        retryable = false
                    )
                StructuredActionParser.parse(text, language)
            } catch (error: TimeoutCancellationException) {
                Result.failure(AiProviderErrors.fromThrowable(error))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(AiProviderErrors.fromThrowable(error))
            }

            if (result.isSuccess) {
                AssistantLogger.d(TAG, "Gemini returned a validated structured action")
                return result
            }
            val error = result.exceptionOrNull()?.let(AiProviderErrors::fromThrowable)
                ?: AiProviderException(AiProviderErrorCategory.INVALID_API_RESPONSE, "The AI response was invalid.", false)
            AssistantLogger.w(TAG, "Gemini planner failed: ${error.category}")
            if (!error.retryable || attempt >= maxRetries) return Result.failure(error)
            retryDelay(backoff(config, attempt))
            attempt++
        }
    }

    override suspend fun planAgent(request: AgentPlanningRequest, config: AiProviderConfig): Result<AgentPlan> {
        val key = runCatching { apiKeyProvider().trim() }.getOrDefault("")
        if (key.isBlank()) return Result.failure(
            AiProviderException(
                AiProviderErrorCategory.NO_API_KEY,
                "Add a Gemini API key in Settings, or configure another AI provider.",
                retryable = false
            )
        )
        val interaction = try {
            ActionPlanner.buildAgentPlanRequest(
                request.command,
                request.language,
                request.recentGoal,
                model = modelId
            )
        } catch (_: Exception) {
            return Result.failure(AiProviderException(
                AiProviderErrorCategory.ACTION_ERROR,
                "MJ couldn't prepare the agent plan request.",
                retryable = false
            ))
        }

        var attempt = 0
        val maxRetries = config.maxRetries.coerceIn(0, MAX_RETRIES)
        while (true) {
            val result = try {
                val response = withTimeout(config.timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)) {
                    serviceProvider().createInteraction(key, interaction)
                }
                val text = response.plannerText()
                    ?: return Result.failure(AiProviderException(
                        AiProviderErrorCategory.INVALID_API_RESPONSE,
                        "The AI service returned no agent plan.",
                        retryable = false
                    ))
                AgentPlanParser.parse(text, request.language)
            } catch (error: TimeoutCancellationException) {
                Result.failure(AiProviderErrors.fromThrowable(error))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(AiProviderErrors.fromThrowable(error))
            }

            if (result.isSuccess) {
                AssistantLogger.d(TAG, "Gemini returned a validated agent plan")
                return result
            }
            val error = result.exceptionOrNull()?.let(AiProviderErrors::fromThrowable)
                ?: AiProviderException(AiProviderErrorCategory.INVALID_API_RESPONSE, "The AI plan was invalid.", false)
            AssistantLogger.w(TAG, "Gemini agent planning failed: ${error.category}")
            if (!error.retryable || attempt >= maxRetries) return Result.failure(error)
            retryDelay(backoff(config, attempt))
            attempt++
        }
    }

    private fun InteractionResponse.plannerText(): String? = outputText?.takeIf(String::isNotBlank)
        ?: steps.asReversed()
            .firstOrNull { it.type == "model_output" }
            ?.content
            ?.firstOrNull { it.type == "text" }
            ?.text
            ?.takeIf(String::isNotBlank)

    companion object {
        private const val TAG = "GeminiAiProvider"
        private const val MAX_RETRIES = 2
        private const val MAX_TIMEOUT_MS = 60_000L
    }
}

class OpenAiProvider(
    private val apiKeyProvider: () -> String = { AppSettingsManager.getOpenAiApiKey() },
    private val model: String = DEFAULT_MODEL,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) }
) : AiProvider, AgentPlanProvider {
    override val id = AiProviderId.OPENAI

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> {
        val key = runCatching { apiKeyProvider().trim() }.getOrDefault("")
        if (key.isBlank()) return failure(
            AiProviderErrorCategory.NO_API_KEY,
            "Add an OpenAI API key in Settings, or configure Gemini.",
            retryable = false
        )

        var attempt = 0
        val maxRetries = config.maxRetries.coerceIn(0, MAX_RETRIES)
        while (true) {
            val result = try {
                withTimeout(config.timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)) {
                    Result.success(call(key, command, language))
                }
            } catch (error: TimeoutCancellationException) {
                Result.failure(AiProviderErrors.fromThrowable(error))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(AiProviderErrors.fromThrowable(error))
            }

            if (result.isSuccess) return result
            val error = result.exceptionOrNull()?.let(AiProviderErrors::fromThrowable)
                ?: AiProviderException(AiProviderErrorCategory.INVALID_API_RESPONSE, "The AI response was invalid.", false)
            AssistantLogger.w("OpenAiProvider", "OpenAI planner failed: ${error.category}")
            if (!error.retryable || attempt >= maxRetries) return Result.failure(error)
            retryDelay(backoff(config, attempt))
            attempt++
        }
    }

    override suspend fun planAgent(request: AgentPlanningRequest, config: AiProviderConfig): Result<AgentPlan> {
        val key = runCatching { apiKeyProvider().trim() }.getOrDefault("")
        if (key.isBlank()) return Result.failure(AiProviderException(
            AiProviderErrorCategory.NO_API_KEY,
            "Add an OpenAI API key in Settings, or configure Gemini.",
            retryable = false
        ))
        var attempt = 0
        val maxRetries = config.maxRetries.coerceIn(0, MAX_RETRIES)
        while (true) {
            val result = try {
                withTimeout(config.timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)) {
                    Result.success(callAgentPlan(key, request))
                }
            } catch (error: TimeoutCancellationException) {
                Result.failure(AiProviderErrors.fromThrowable(error))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(AiProviderErrors.fromThrowable(error))
            }
            if (result.isSuccess) return result
            val error = result.exceptionOrNull()?.let(AiProviderErrors::fromThrowable)
                ?: AiProviderException(AiProviderErrorCategory.INVALID_API_RESPONSE, "The AI plan was invalid.", false)
            AssistantLogger.w("OpenAiProvider", "OpenAI agent planning failed: ${error.category}")
            if (!error.retryable || attempt >= maxRetries) return Result.failure(error)
            retryDelay(backoff(config, attempt))
            attempt++
        }
    }

    private suspend fun call(key: String, command: String, language: String): PlannedAction {
        val prompt = "${ActionPlanner.systemInstruction(language)}\n\nUser command:\n$command"
        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("input", JsonPrimitive(prompt))
            put("text", buildJsonObject {
                put("format", buildJsonObject { put("type", JsonPrimitive("json_object")) })
            })
        }.toString()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        execute(request).use { response ->
            if (!response.isSuccessful) {
                AssistantLogger.w("OpenAiProvider", "Provider request failed with HTTP ${response.code}")
                throw AiProviderErrors.fromHttpStatus(response.code)
            }
            val raw = response.body?.string().orEmpty()
            val outputText = try {
                findFirstText(Json { ignoreUnknownKeys = true }.parseToJsonElement(raw).jsonObject)
            } catch (_: Exception) {
                null
            } ?: throw AiProviderException(
                AiProviderErrorCategory.INVALID_API_RESPONSE,
                "The AI service returned no structured action.",
                retryable = false
            )
            return StructuredActionParser.parse(outputText, language).getOrElse { throw it }
        }
    }

    private suspend fun callAgentPlan(key: String, request: AgentPlanningRequest): AgentPlan {
        val interaction = ActionPlanner.buildAgentPlanRequest(request.command, request.language, request.recentGoal)
        val prompt = "${interaction.systemInstruction}\n\nPlanner input JSON:\n${interaction.input}"
        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("input", JsonPrimitive(prompt))
            put("text", buildJsonObject {
                put("format", buildJsonObject { put("type", JsonPrimitive("json_object")) })
            })
        }.toString()
        val apiRequest = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(apiRequest).use { response ->
            if (!response.isSuccessful) {
                AssistantLogger.w("OpenAiProvider", "Provider request failed with HTTP ${response.code}")
                throw AiProviderErrors.fromHttpStatus(response.code)
            }
            val raw = response.body?.string().orEmpty()
            val output = try {
                findFirstText(Json { ignoreUnknownKeys = true }.parseToJsonElement(raw).jsonObject)
            } catch (_: Exception) {
                null
            } ?: throw AiProviderException(
                AiProviderErrorCategory.INVALID_API_RESPONSE,
                "The AI service returned no agent plan.",
                retryable = false
            )
            return AgentPlanParser.parse(output, request.language).getOrElse { throw it }
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
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
        private const val MAX_RETRIES = 2
        private const val MAX_TIMEOUT_MS = 60_000L
    }
}

private fun backoff(config: AiProviderConfig, attempt: Int): Long =
    (config.retryBaseDelayMs.coerceIn(50L, 1_000L) * (1L shl attempt.coerceIn(0, 2))).coerceAtMost(2_000L)

private fun failure(
    category: AiProviderErrorCategory,
    message: String,
    retryable: Boolean
): Result<PlannedAction> = Result.failure(AiProviderException(category, message, retryable))
