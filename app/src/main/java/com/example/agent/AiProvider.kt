package com.example.agent

import com.example.PlannedAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

enum class AiProviderId { GEMINI, OPENAI }

data class AiProviderConfig(val timeoutMs: Long = 30_000, val maxRetries: Int = 1)

interface AiProvider {
    val id: AiProviderId
    suspend fun plan(command: String, language: String, config: AiProviderConfig = AiProviderConfig()): Result<PlannedAction>
}

class AiProviderRouter(
    private val providers: Map<AiProviderId, AiProvider>,
    private val primary: AiProviderId = AiProviderId.GEMINI,
    private val fallback: AiProviderId? = AiProviderId.OPENAI
) {
    suspend fun plan(command: String, language: String): Result<PlannedAction> {
        var last: Result<PlannedAction> = Result.failure(IllegalStateException("No AI provider configured"))
        for (id in listOf(primary, fallback).filterNotNull().distinct()) {
            val provider = providers[id] ?: continue
            try {
                val result = withTimeout(AiProviderConfig().timeoutMs) { provider.plan(command, language) }
                if (result.isSuccess) return result
                last = result
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                last = Result.failure(t)
            }
        }
        return last
    }
}
