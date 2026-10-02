package com.example.agent

import com.example.PlannedAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderRouterTest {
    private class FailingProvider(override val id: AiProviderId) : AiProvider {
        override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> =
            Result.failure(AiProviderException(AiProviderErrorCategory.NETWORK_ERROR, "offline", true))
    }

    private class WorkingProvider(override val id: AiProviderId) : AiProvider {
        override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> =
            Result.success(PlannedAction("CHAT", null, "ok", language))
    }

    @Test fun provider_failure_falls_back() = runTest {
        val router = AiProviderRouter(
            providers = mapOf(
                AiProviderId.GEMINI to FailingProvider(AiProviderId.GEMINI),
                AiProviderId.OPENAI to WorkingProvider(AiProviderId.OPENAI)
            )
        )
        val result = router.plan("hello", "en")
        assertTrue(result.isSuccess)
        assertEquals("CHAT", result.getOrThrow().action)
    }

    @Test fun configured_primary_provider_is_selected_before_fallback() = runTest {
        var primaryCalls = 0
        var fallbackCalls = 0
        val primary = object : AiProvider {
            override val id = AiProviderId.GEMINI
            override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> {
                primaryCalls++
                return Result.success(PlannedAction("CHAT", null, "primary", language))
            }
        }
        val fallback = object : AiProvider {
            override val id = AiProviderId.OPENAI
            override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> {
                fallbackCalls++
                return Result.success(PlannedAction("CHAT", null, "fallback", language))
            }
        }

        val result = AiProviderRouter(mapOf(AiProviderId.GEMINI to primary, AiProviderId.OPENAI to fallback))
            .plan("hello", "en")

        assertEquals("primary", result.getOrThrow().speechResponse)
        assertEquals(1, primaryCalls)
        assertEquals(0, fallbackCalls)
    }

    @Test fun missing_fallback_key_does_not_hide_the_primary_provider_error() = runTest {
        val primaryError = AiProviderException(AiProviderErrorCategory.NO_API_KEY, "Add a Gemini API key.", false)
        val primary = object : AiProvider {
            override val id = AiProviderId.GEMINI
            override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> =
                Result.failure(primaryError)
        }
        val fallback = object : AiProvider {
            override val id = AiProviderId.OPENAI
            override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> =
                Result.failure(AiProviderException(AiProviderErrorCategory.NO_API_KEY, "Add an OpenAI key.", false))
        }

        val result = AiProviderRouter(mapOf(AiProviderId.GEMINI to primary, AiProviderId.OPENAI to fallback))
            .plan("hello", "en")

        assertEquals(primaryError.message, result.exceptionOrNull()?.message)
    }

    @Test fun malformed_action_does_not_fall_back() = runTest {
        var fallbackCalls = 0
        val fallback = object : AiProvider {
            override val id = AiProviderId.OPENAI
            override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> {
                fallbackCalls++
                return Result.success(PlannedAction("CHAT", null, "ok", language))
            }
        }
        val primary = object : AiProvider {
            override val id = AiProviderId.GEMINI
            override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> =
                Result.failure(AiProviderException(AiProviderErrorCategory.ACTION_ERROR, "Invalid action", false))
        }
        val router = AiProviderRouter(mapOf(AiProviderId.GEMINI to primary, AiProviderId.OPENAI to fallback))

        assertTrue(router.plan("do something", "en").isFailure)
        assertEquals(0, fallbackCalls)
    }
}
