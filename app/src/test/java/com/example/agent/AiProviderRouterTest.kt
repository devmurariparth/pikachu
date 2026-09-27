package com.example.agent

import com.example.PlannedAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderRouterTest {
    private class FailingProvider(override val id: AiProviderId) : AiProvider {
        override suspend fun plan(command: String, language: String, config: AiProviderConfig): Result<PlannedAction> =
            Result.failure(IllegalStateException("provider failed"))
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
}
