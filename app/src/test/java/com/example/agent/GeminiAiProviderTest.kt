package com.example.agent

import com.example.action.ActionName
import com.example.network.GenerateContentRequest
import com.example.network.GenerateContentResponse
import com.example.network.GeminiApiService
import com.example.network.InteractionRequest
import com.example.network.InteractionResponse
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class GeminiAiProviderTest {
    private class FakeService(
        private val interaction: suspend (InteractionRequest) -> InteractionResponse
    ) : GeminiApiService {
        var calls = 0
        var lastRequest: InteractionRequest? = null

        override suspend fun createInteraction(apiKey: String, request: InteractionRequest): InteractionResponse {
            calls++
            lastRequest = request
            return interaction(request)
        }

        override suspend fun generateProContent(apiKey: String, request: GenerateContentRequest): GenerateContentResponse =
            error("Not used in planner provider tests")

        override suspend fun generateFlashContent(apiKey: String, request: GenerateContentRequest): GenerateContentResponse =
            error("Not used in planner provider tests")

        override suspend fun generateVisionContent(apiKey: String, request: GenerateContentRequest): GenerateContentResponse =
            error("Not used in planner provider tests")
    }

    private fun validResponse() = InteractionResponse(
        outputText = """{"action":"CHAT","payload":null,"speech":"Hello","lang":"en"}"""
    )

    private fun validPlanResponse() = InteractionResponse(
        outputText = """{"goal":"Search for weather","required_tools":["SEARCH_WEB"],"risk_level":"LOW","expected_result":"The search request is accepted","final_response_mode":"SPEAK_RESULT","speech":"I started the search.","lang":"en","steps":[{"id":"search","action":"SEARCH_WEB","payload":"weather","depends_on":[],"risk_level":"LOW","expected_result":"The search opens"}]}"""
    )

    private fun provider(service: FakeService, key: String = "test-key") = GeminiAiProvider(
        apiKeyProvider = { key },
        serviceProvider = { service },
        retryDelay = {}
    )

    @Test fun primary_model_request_uses_stateless_structured_output() = runTest {
        val service = FakeService { validResponse() }
        val result = provider(service).plan("hello", "en")

        assertTrue(result.isSuccess)
        val request = service.lastRequest!!
        assertEquals("gemini-3.8-flash", request.model)
        assertEquals(false, request.store)
        assertEquals("application/json", request.responseFormat.single().mimeType)
        assertTrue(request.systemInstruction.contains("PLAY_VIDEO"))

        val serialized = Json.encodeToString(request)
        assertTrue(serialized.contains("\"response_format\""))
        assertTrue(serialized.contains("\"mime_type\":\"application/json\""))
        assertTrue(serialized.contains("\"store\":false"))
    }

    @Test fun gemini_provider_model_is_configurable_for_a_future_reasoning_fallback() = runTest {
        val service = FakeService { validResponse() }
        val reasoningProvider = GeminiAiProvider(
            id = AiProviderId.GEMINI_REASONING,
            modelId = AiModelConfig.REASONING_FALLBACK,
            apiKeyProvider = { "test-key" },
            serviceProvider = { service },
            retryDelay = {}
        )

        assertTrue(reasoningProvider.plan("hello", "en").isSuccess)
        assertEquals(AiModelConfig.REASONING_FALLBACK, service.lastRequest?.model)
    }

    @Test fun missing_api_key_does_not_call_the_provider() = runTest {
        val service = FakeService { validResponse() }
        val result = provider(service, key = " ").plan("hello", "en")

        assertTrue(result.isFailure)
        assertEquals(0, service.calls)
        assertEquals(AiProviderErrorCategory.NO_API_KEY, (result.exceptionOrNull() as AiProviderException).category)
    }

    @Test fun transient_network_failure_retries_once_then_succeeds() = runTest {
        val networkAttempts = AtomicInteger()
        val service = FakeService { if (networkAttempts.getAndIncrement() == 0) throw IOException("temporary") else validResponse() }
        val result = provider(service).plan("hello", "en", AiProviderConfig(maxRetries = 1))

        assertTrue(result.isSuccess)
        assertEquals(2, service.calls)
    }

    @Test fun malformed_json_is_not_retried() = runTest {
        val service = FakeService { InteractionResponse(outputText = "not json") }
        val result = provider(service).plan("hello", "en", AiProviderConfig(maxRetries = 2))

        assertTrue(result.isFailure)
        assertEquals(1, service.calls)
    }

    @Test fun provider_timeout_is_bounded_and_reported() = runTest {
        val service = FakeService { delay(1_000); validResponse() }
        val result = provider(service).plan("hello", "en", AiProviderConfig(timeoutMs = 10, maxRetries = 0))

        assertTrue(result.isFailure)
        assertEquals(AiProviderErrorCategory.TIMEOUT, (result.exceptionOrNull() as AiProviderException).category)
        assertEquals(1, service.calls)
    }

    @Test fun external_cancellation_stops_the_active_provider_operation() = runTest {
        val service = FakeService { awaitCancellation() }
        val job = launch { provider(service).plan("hello", "en") }
        runCurrent()

        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, service.calls)
    }

    @Test fun agent_planner_uses_central_model_and_stateless_json_request_with_context() = runTest {
        val service = FakeService { validPlanResponse() }
        val result = provider(service).planAgent(
            AgentPlanningRequest("search for it", "en", recentGoal = "search the web for cats")
        )

        assertTrue(result.isSuccess)
        val request = service.lastRequest!!
        assertEquals(AiModelConfig.PRIMARY_PLANNER, request.model)
        assertEquals(false, request.store)
        assertEquals("application/json", request.responseFormat.single().mimeType)
        assertTrue(request.systemInstruction.contains("agent-plan schema"))
        assertTrue(request.input.contains("search the web for cats"))
        assertEquals(ActionName.SEARCH_WEB, result.getOrThrow().steps.single().request.name)
    }

    @Test fun malformed_agent_plan_fails_without_retrying_invalid_output() = runTest {
        val service = FakeService { InteractionResponse(outputText = "{bad json") }
        val result = provider(service).planAgent(
            AgentPlanningRequest("make a plan", "en"),
            AiProviderConfig(maxRetries = 2)
        )

        assertTrue(result.isFailure)
        assertEquals(1, service.calls)
        assertEquals(AiProviderErrorCategory.ACTION_ERROR, (result.exceptionOrNull() as AiProviderException).category)
    }
}
