package com.example.action

import android.content.Context
import com.example.agent.AgentPlan
import com.example.agent.AgentPlanStep
import com.example.agent.riskFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

enum class PlanStepStatus { PENDING, RUNNING, VERIFIED, STARTED, FAILED, CANCELLED }

data class PlanStepResult(
    val step: AgentPlanStep,
    val result: ActionResult,
    val status: PlanStepStatus,
    val attempts: Int
)

data class ActionPlanResult(
    val plan: AgentPlan,
    val steps: List<PlanStepResult>,
    val completed: Boolean,
    val cancelled: Boolean
)

class ActionPlanPipeline(
    private val runtime: ActionRuntime,
    private val executeStep: suspend (Context, ActionRequest<out ActionParameters>, ActionContext) -> ActionResult =
        { context, request, actionContext -> runtime.execute(context, request, actionContext) },
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    maxRetries: Int = 1
) {
    private val maxRetries = maxRetries.coerceIn(0, MAX_RETRIES)

    fun validate(plan: AgentPlan): ActionError? {
        if (plan.id.isBlank() || plan.goal.isBlank() || plan.expectedResult.isBlank()) {
            return invalid("The agent plan is missing required metadata.")
        }
        if (plan.steps.isEmpty() || plan.steps.size > MAX_STEPS) return invalid("The action plan has an invalid number of steps.")
        if (plan.finalResponseMode != com.example.agent.FinalResponseMode.SPEAK_RESULT) {
            return invalid("Executable plans must use the result response mode.")
        }

        val ids = linkedSetOf<String>()
        val tools = linkedSetOf<ActionName>()
        var highestRisk = com.example.agent.AgentRiskLevel.LOW
        for (step in plan.steps) {
            if (step.id.isBlank() || !ids.add(step.id) || step.request.id.isBlank()) {
                return invalid("Every plan step needs a unique id.")
            }
            if (step.expectedResult.isBlank()) return invalid("Every plan step needs an expected result.")
            if (step.dependsOn.size != step.dependsOn.toSet().size || step.dependsOn.any { it !in ids || it == step.id }) {
                return invalid("Plan dependencies must point to distinct earlier steps.")
            }
            if (!runtime.toolRegistry.accepts(step.request.name, step.request.parameters)) {
                return invalid("A plan step does not match its registered typed tool.")
            }
            val minimumRisk = riskFor(step.request.name)
            if (step.riskLevel.ordinal < minimumRisk.ordinal) return invalid("A plan step understates its risk.")
            if (step.riskLevel.ordinal > highestRisk.ordinal) highestRisk = step.riskLevel
            tools += step.request.name
        }
        if (tools != plan.requiredTools) return invalid("The required tool list does not match the plan steps.")
        if (plan.riskLevel.ordinal < highestRisk.ordinal) return invalid("The plan understates its risk.")
        return null
    }

    suspend fun execute(
        context: Context,
        plan: AgentPlan,
        actionContext: ActionContext = ActionContext(),
        onStepStarting: (ActionRequest<out ActionParameters>) -> Unit = {}
    ): ActionPlanResult {
        validate(plan)?.let { error ->
            val step = plan.steps.firstOrNull() ?: return ActionPlanResult(plan, emptyList(), false, false)
            val failed = ActionResult.Failure(step.request.id, step.request.name, error)
            return ActionPlanResult(plan, listOf(PlanStepResult(step, failed, PlanStepStatus.FAILED, 0)), false, false)
        }

        val results = mutableListOf<PlanStepResult>()
        for (step in plan.steps) {
            currentCoroutineContext().ensureActive()
            val dependenciesReady = step.dependsOn.all { dependency ->
                results.firstOrNull { it.step.id == dependency }?.status == PlanStepStatus.VERIFIED
            }
            if (!dependenciesReady) {
                val failure = ActionResult.Failure(
                    step.request.id,
                    step.request.name,
                    ActionError(ActionErrorCode.PRECONDITION_FAILED, "A required earlier step was not verified.")
                )
                results += PlanStepResult(step, failure, PlanStepStatus.FAILED, 0)
                break
            }

            onStepStarting(step.request)
            var attempts = 0
            var result: ActionResult
            do {
                result = executeStep(context, step.request, actionContext)
                currentCoroutineContext().ensureActive()
                val canRetry = result is ActionResult.Failure &&
                    result.error.code == ActionErrorCode.TRANSIENT_FAILURE &&
                    runtime.toolRegistry.isRetrySafe(step.request.name) && attempts < maxRetries
                if (!canRetry) break
                retryDelay((RETRY_BASE_DELAY_MS shl attempts.coerceIn(0, 2)).coerceAtMost(MAX_RETRY_DELAY_MS))
                currentCoroutineContext().ensureActive()
                attempts++
            } while (true)

            val status = when (result) {
                is ActionResult.Success -> PlanStepStatus.VERIFIED
                is ActionResult.Started -> PlanStepStatus.STARTED
                is ActionResult.Cancelled -> PlanStepStatus.CANCELLED
                is ActionResult.Failure, is ActionResult.Blocked, is ActionResult.PermissionRequired,
                is ActionResult.Unsupported, is ActionResult.TimedOut -> PlanStepStatus.FAILED
            }
            results += PlanStepResult(step, result, status, attempts + 1)
            if (status == PlanStepStatus.FAILED || status == PlanStepStatus.CANCELLED) break
        }

        val cancelled = results.any { it.status == PlanStepStatus.CANCELLED }
        val completed = !cancelled && results.size == plan.steps.size &&
            results.all { it.status == PlanStepStatus.VERIFIED }
        return ActionPlanResult(plan, results.toList(), completed, cancelled)
    }

    fun cancel(actionId: String): Boolean = runtime.cancel(actionId)
    fun cancelAll() = runtime.cancelAll()

    private fun invalid(message: String) = ActionError(ActionErrorCode.INVALID_PARAMETERS, message)

    companion object {
        private const val MAX_STEPS = 8
        private const val MAX_RETRIES = 2
        private const val RETRY_BASE_DELAY_MS = 100L
        private const val MAX_RETRY_DELAY_MS = 400L
    }
}
