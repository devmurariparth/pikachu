package com.example.agent

import com.example.PlannedAction
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

enum class AiProviderId { GEMINI, GEMINI_REASONING, OPENAI }

data class AiProviderConfig(
    val timeoutMs: Long = 20_000,
    val maxRetries: Int = 1,
    val retryBaseDelayMs: Long = 250
)

enum class AiProviderErrorCategory {
    NO_API_KEY, NETWORK_ERROR, TIMEOUT, INVALID_API_RESPONSE, RATE_LIMIT,
    AUTH_ERROR, MODEL_ERROR, ACTION_ERROR, PERMISSION_REQUIRED, UNSUPPORTED, CANCELLED
}

class AiProviderException(
    val category: AiProviderErrorCategory,
    override val message: String,
    val retryable: Boolean
) : Exception(message)

object AiProviderErrors {
    fun fromThrowable(error: Throwable): AiProviderException = when (error) {
        is AiProviderException -> error
        is TimeoutCancellationException, is SocketTimeoutException -> AiProviderException(
            AiProviderErrorCategory.TIMEOUT, "The AI request timed out. Please try again.", true
        )
        is HttpException -> fromHttpStatus(error.code())
        is IOException -> AiProviderException(
            AiProviderErrorCategory.NETWORK_ERROR, "MJ couldn't connect to the AI service. Check your connection and try again.", true
        )
        is CancellationException -> AiProviderException(
            AiProviderErrorCategory.CANCELLED, "AI request cancelled.", false
        )
        else -> AiProviderException(
            AiProviderErrorCategory.INVALID_API_RESPONSE, "MJ couldn't read the AI response. Please try again.", false
        )
    }

    fun fromHttpStatus(code: Int): AiProviderException = when (code) {
            401, 403 -> AiProviderException(
                AiProviderErrorCategory.AUTH_ERROR, "The AI provider rejected its API key. Check the key in Settings.", false
            )
            404 -> AiProviderException(
                AiProviderErrorCategory.MODEL_ERROR, "The configured AI model is unavailable.", false
            )
            429 -> AiProviderException(
                AiProviderErrorCategory.RATE_LIMIT, "The AI service is busy. Please try again shortly.", true
            )
            in 500..599 -> AiProviderException(
                AiProviderErrorCategory.NETWORK_ERROR, "The AI service is temporarily unavailable.", true
            )
            else -> AiProviderException(
                AiProviderErrorCategory.INVALID_API_RESPONSE, "The AI request was rejected. Please try a different request.", false
            )
        }

    fun mayUseFallback(error: AiProviderException): Boolean = error.category in setOf(
        AiProviderErrorCategory.NO_API_KEY,
        AiProviderErrorCategory.NETWORK_ERROR,
        AiProviderErrorCategory.TIMEOUT,
        AiProviderErrorCategory.RATE_LIMIT,
        AiProviderErrorCategory.MODEL_ERROR
    )
}

interface AiProvider {
    val id: AiProviderId
    suspend fun plan(command: String, language: String, config: AiProviderConfig = AiProviderConfig()): Result<PlannedAction>
}

class AiProviderRouter(
    private val providers: Map<AiProviderId, AiProvider>,
    private val primary: AiProviderId = AiProviderId.GEMINI,
    private val fallback: AiProviderId? = AiProviderId.OPENAI,
    private val config: AiProviderConfig = AiProviderConfig()
) {
    suspend fun plan(command: String, language: String): Result<PlannedAction> {
        var last: Result<PlannedAction> = Result.failure(
            AiProviderException(AiProviderErrorCategory.NO_API_KEY, "No AI provider is configured.", false)
        )
        var primaryError: AiProviderException? = null
        for (id in listOf(primary, fallback).filterNotNull().distinct()) {
            val provider = providers[id] ?: continue
            try {
                val result = provider.plan(command, language, config)
                if (result.isSuccess) return result
                val error = result.exceptionOrNull()?.let(AiProviderErrors::fromThrowable)
                    ?: AiProviderException(
                        AiProviderErrorCategory.INVALID_API_RESPONSE,
                        "MJ couldn't read the AI response. Please try again.",
                        false
                    )
                last = Result.failure(error)
                if (id == primary) {
                    primaryError = error
                    if (!AiProviderErrors.mayUseFallback(error)) return last
                } else if (error.category == AiProviderErrorCategory.NO_API_KEY && primaryError != null) {
                    return Result.failure(primaryError)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val error = AiProviderErrors.fromThrowable(t)
                last = Result.failure(error)
                if (id == primary) {
                    primaryError = error
                    if (!AiProviderErrors.mayUseFallback(error)) return last
                } else if (error.category == AiProviderErrorCategory.NO_API_KEY && primaryError != null) {
                    return Result.failure(primaryError)
                }
            }
        }
        return last
    }
}
