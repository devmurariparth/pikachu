package com.example.action

import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentPlan
import com.example.agent.AgentPlanStep
import com.example.agent.AgentRiskLevel
import com.example.agent.FinalResponseMode
import com.example.agent.riskFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActionPlanPipelineTest {
    private fun step(
        id: String,
        action: ActionName = ActionName.CHAT,
        parameters: ActionParameters = ActionParameters.Chat,
        dependsOn: List<String> = emptyList()
    ) = AgentPlanStep(
        id = id,
        request = ActionRequest("request-$id", action, parameters),
        dependsOn = dependsOn,
        riskLevel = riskFor(action),
        expectedResult = "The action request is accepted."
    )

    private fun plan(vararg steps: AgentPlanStep) = AgentPlan(
        id = "plan-1",
        goal = "Run a typed task",
        requiredTools = steps.map { it.request.name }.toSet(),
        steps = steps.toList(),
        riskLevel = steps.maxByOrNull { it.riskLevel.ordinal }?.riskLevel ?: AgentRiskLevel.LOW,
        expectedResult = "The steps are handled in order.",
        finalResponseMode = FinalResponseMode.SPEAK_RESULT,
        speechResponse = "The request is ready.",
        language = "en"
    )

    @Test fun empty_plan_fails_validation() {
        val pipeline = ActionPlanPipeline(ActionRuntime())
        assertEquals(ActionErrorCode.INVALID_PARAMETERS, pipeline.validate(plan())?.code)
    }

    @Test fun blank_step_id_fails_validation() {
        val pipeline = ActionPlanPipeline(ActionRuntime())
        val invalid = plan(step(" "))
        assertEquals(ActionErrorCode.INVALID_PARAMETERS, pipeline.validate(invalid)?.code)
    }

    @Test fun duplicate_or_forward_dependencies_are_rejected() {
        val pipeline = ActionPlanPipeline(ActionRuntime())
        val first = step("first", dependsOn = listOf("second"))
        val second = step("second")
        assertNotNull(pipeline.validate(plan(first, second)))
    }

    @Test fun duplicate_action_request_ids_are_rejected_even_when_step_ids_differ() {
        val pipeline = ActionPlanPipeline(ActionRuntime())
        val first = step("first")
        val second = step("second").copy(request = step("second").request.copy(id = first.request.id))
        assertEquals(ActionErrorCode.INVALID_PARAMETERS, pipeline.validate(plan(first, second))?.code)
    }

    @Test fun dependent_steps_wait_for_verification_and_stop_after_an_unverified_start() = runTest {
        val order = mutableListOf<String>()
        val runtime = ActionRuntime()
        val pipeline = ActionPlanPipeline(runtime, executeStep = { _, request, _ ->
            order += request.id
            if (request.id == "request-first") ActionResult.Started(request.id, request.name, "Started")
            else ActionResult.Success(request.id, request.name, "Accepted")
        })
        val result = pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            plan(step("first"), step("second", dependsOn = listOf("first")))
        )

        assertEquals(listOf("request-first"), order)
        assertEquals(PlanStepStatus.STARTED, result.steps.first().status)
        assertEquals(PlanStepStatus.FAILED, result.steps.last().status)
        assertFalse(result.completed)
    }

    @Test fun a_failure_stops_all_later_steps() = runTest {
        val calls = mutableListOf<String>()
        val runtime = ActionRuntime()
        val pipeline = ActionPlanPipeline(runtime, executeStep = { _, request, _ ->
            calls += request.id
            ActionResult.PermissionRequired(
                request.id,
                request.name,
                ActionError(ActionErrorCode.PERMISSION_REQUIRED, "Grant permission.")
            )
        })
        val result = pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            plan(step("first"), step("second", dependsOn = listOf("first")))
        )
        assertEquals(listOf("request-first"), calls)
        assertEquals(PlanStepStatus.FAILED, result.steps.single().status)
    }

    @Test fun default_retry_policy_allows_two_transient_retries_then_succeeds() = runTest {
        var calls = 0
        val runtime = ActionRuntime()
        val pipeline = ActionPlanPipeline(runtime, executeStep = { _, request, _ ->
            calls++
            if (calls < 3) ActionResult.Failure(
                request.id,
                request.name,
                ActionError(ActionErrorCode.TRANSIENT_FAILURE, "Temporary.")
            ) else ActionResult.Success(request.id, request.name, "Accepted")
        }, retryDelay = {})
        val result = pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            plan(step("search", ActionName.SEARCH_WEB, ActionParameters.SearchWeb("weather")))
        )
        assertEquals(3, calls)
        assertEquals(3, result.steps.single().attempts)
        assertTrue(result.completed)
    }

    @Test fun bounded_retry_only_retries_transient_failure_for_retry_safe_tool() = runTest {
        var calls = 0
        val runtime = ActionRuntime()
        val pipeline = ActionPlanPipeline(runtime, executeStep = { _, request, _ ->
            calls++
            if (calls == 1) ActionResult.Failure(
                request.id,
                request.name,
                ActionError(ActionErrorCode.TRANSIENT_FAILURE, "Temporary.")
            ) else ActionResult.Success(request.id, request.name, "Accepted")
        }, retryDelay = {}, maxRetries = 5)
        val result = pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            plan(step("search", ActionName.SEARCH_WEB, ActionParameters.SearchWeb("weather")))
        )
        assertEquals(2, calls)
        assertEquals(2, result.steps.single().attempts)
        assertTrue(result.completed)
    }

    @Test fun permission_failures_are_never_retried() = runTest {
        var calls = 0
        val pipeline = ActionPlanPipeline(ActionRuntime(), executeStep = { _, request, _ ->
            calls++
            ActionResult.PermissionRequired(
                request.id,
                request.name,
                ActionError(ActionErrorCode.PERMISSION_REQUIRED, "Grant permission.")
            )
        }, retryDelay = {})
        pipeline.execute(
            ApplicationProvider.getApplicationContext(),
            plan(step("search", ActionName.SEARCH_WEB, ActionParameters.SearchWeb("weather")))
        )
        assertEquals(1, calls)
    }

    @Test fun coroutine_cancellation_propagates_and_does_not_run_future_steps() = runTest {
        val pipeline = ActionPlanPipeline(ActionRuntime(), executeStep = { _, _, _ ->
            throw CancellationException("test cancellation")
        })
        val job = async {
            pipeline.execute(
                ApplicationProvider.getApplicationContext(),
                plan(step("first"), step("second", dependsOn = listOf("first")))
            )
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
