package com.example.agent

import androidx.test.core.app.ApplicationProvider
import com.example.action.ActionName
import com.example.action.ActionParameters
import com.example.action.ActionPlanPipeline
import com.example.action.ActionRequest
import com.example.action.ActionResult
import com.example.action.ActionRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentPipelineTest {
    private fun responsePlan(language: String = "en", speech: String = "Ready.") = AgentPlan(
        id = "answer-plan",
        goal = "Answer the user",
        requiredTools = emptySet(),
        steps = emptyList(),
        riskLevel = AgentRiskLevel.LOW,
        expectedResult = "A direct answer is ready.",
        finalResponseMode = FinalResponseMode.DIRECT_RESPONSE,
        speechResponse = speech,
        language = language
    )

    @Test fun local_action_uses_runtime_without_calling_ai_planner() = runTest {
        var plannerCalls = 0
        var actionName: ActionName? = null
        val runtime = ActionRuntime()
        val planPipeline = ActionPlanPipeline(runtime, executeStep = { _, request, _ ->
            actionName = request.name
            ActionResult.Started(request.id, request.name, "Android accepted")
        })
        val result = AgentPipeline(runtime, planPipeline).execute(
            ApplicationProvider.getApplicationContext(),
            "open settings",
            planner = { plannerCalls++; Result.failure(AssertionError("Local route must stay local.")) }
        ).getOrThrow()

        assertEquals(0, plannerCalls)
        assertEquals(ActionName.OPEN_SETTINGS, actionName)
        assertTrue(result.fromLocalFastPath)
        assertEquals(AgentExecutionStatus.STARTED, result.response.status)
        assertTrue(result.response.message.contains("can't verify"))
    }

    @Test fun smart_mixed_language_action_is_local_and_ambiguous_request_is_clarified_without_ai() = runTest {
        var plannerCalls = 0
        val executed = mutableListOf<ActionName>()
        val runtime = ActionRuntime()
        val pipeline = AgentPipeline(runtime, ActionPlanPipeline(runtime, executeStep = { _, request, _ ->
            executed += request.name
            ActionResult.Started(request.id, request.name, "Android accepted")
        }))
        val music = pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            "Spotify ma Arijit nu song vagadvo",
            planner = { plannerCalls++; Result.failure(AssertionError("A clear local music request must not call the planner.")) }
        ).getOrThrow()
        assertTrue(music.fromLocalFastPath)
        assertEquals(listOf(ActionName.PLAY_MUSIC), executed)
        assertEquals(0, plannerCalls)

        val unclear = pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            "call her",
            planner = { plannerCalls++; Result.failure(AssertionError("Sensitive ambiguity must be clarified locally.")) }
        ).getOrThrow()
        assertEquals(AgentExecutionStatus.RESPONDED, unclear.response.status)
        assertTrue(unclear.response.message.contains("Who should I call?"))
        assertEquals(0, plannerCalls)
    }

    @Test fun planner_receives_normalized_command_and_detected_gujarati_language() = runTest {
        var planningRequest: AgentPlanningRequest? = null
        val result = AgentPipeline().execute(
            ApplicationProvider.getApplicationContext(),
            "મને quantum physics સમજાવો",
            planner = { request -> planningRequest = request; Result.success(responsePlan("gu", "હા, સમજાવું છું.")) }
        ).getOrThrow()

        assertEquals("gu", planningRequest?.language)
        assertTrue(planningRequest?.command?.isNotBlank() == true)
        assertFalse(result.fromLocalFastPath)
        assertEquals("હા, સમજાવું છું.", result.response.message)
        assertEquals("gu", result.response.language)
    }

    @Test fun a_started_action_does_not_become_follow_up_context_without_verification() = runTest {
        val requests = mutableListOf<AgentPlanningRequest>()
        val runtime = ActionRuntime()
        val planPipeline = ActionPlanPipeline(runtime, executeStep = { _, request, _ ->
            ActionResult.Started(request.id, request.name, "Accepted by Android")
        })
        val pipeline = AgentPipeline(runtime, planPipeline)

        pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            "search the web for cats",
            planner = { error("Search should use local fast path.") }
        ).getOrThrow()

        pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            "tell me more about it",
            planner = { request -> requests += request; Result.success(responsePlan()) }
        ).getOrThrow()

        assertEquals(1, requests.size)
        assertNull(requests.single().recentGoal)
    }

    @Test fun safe_search_goal_is_available_for_an_unambiguous_follow_up_only() = runTest {
        val requests = mutableListOf<AgentPlanningRequest>()
        val runtime = ActionRuntime()
        val planPipeline = ActionPlanPipeline(runtime, executeStep = { _, request, _ ->
            ActionResult.Success(request.id, request.name, "Verified")
        })
        val pipeline = AgentPipeline(runtime, planPipeline)
        pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            "search the web for cats",
            planner = { error("Search should use local fast path.") }
        ).getOrThrow()
        pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            "tell me more about it",
            planner = { request -> requests += request; Result.success(responsePlan()) }
        ).getOrThrow()
        pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            "Explain photosynthesis",
            planner = { request -> requests += request; Result.success(responsePlan()) }
        ).getOrThrow()

        assertEquals("search the web for cats", requests.first().recentGoal)
        assertNull(requests.last().recentGoal)
    }

    @Test fun failed_verification_is_reported_without_a_success_claim() = runTest {
        val runtime = ActionRuntime()
        val pipeline = AgentPipeline(runtime, ActionPlanPipeline(runtime, executeStep = { _, request, _ ->
            ActionResult.Failure(
                request.id,
                request.name,
                com.example.action.ActionError(com.example.action.ActionErrorCode.VERIFICATION_FAILED, "Could not verify action.")
            )
        }))
        val actionPlan = AgentPlan.singleAction(
            ActionRequest("test-action", ActionName.SEARCH_WEB, ActionParameters.SearchWeb("weather")),
            "Search weather",
            "en",
            "The search completed successfully."
        )

        val result = pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            "could you look into the weather please",
            planner = { Result.success(actionPlan) }
        ).getOrThrow()

        assertEquals(AgentExecutionStatus.FAILED, result.response.status)
        assertEquals("Could not verify action.", result.response.message)
        assertFalse(result.response.message.contains("successfully"))
    }
}
