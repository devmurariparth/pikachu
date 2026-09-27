package com.example.action

import android.content.Context
import kotlinx.coroutines.CancellationException

data class ActionPlan(
    val id: String,
    val steps: List<ActionRequest<out ActionParameters>>
)

enum class PlanStepStatus { PENDING, RUNNING, VERIFIED, FAILED, CANCELLED }

data class PlanStepResult(
    val request: ActionRequest<out ActionParameters>,
    val result: ActionResult,
    val status: PlanStepStatus
)

data class ActionPlanResult(
    val plan: ActionPlan,
    val steps: List<PlanStepResult>,
    val completed: Boolean,
    val cancelled: Boolean
)

class ActionPlanPipeline(
    private val runtime: ActionRuntime
) {
    fun validate(plan: ActionPlan): ActionError? {
        if (plan.steps.isEmpty()) {
            return ActionError(ActionErrorCode.INVALID_PARAMETERS, "Action plan has no steps.")
        }
        if (plan.steps.any { it.id.isBlank() }) {
            return ActionError(ActionErrorCode.INVALID_PARAMETERS, "Every plan step needs an action id.")
        }
        return null
    }

    suspend fun execute(
        context: Context,
        plan: ActionPlan,
        actionContext: ActionContext = ActionContext()
    ): ActionPlanResult {
        validate(plan)?.let {
            val request = plan.steps.firstOrNull()
                ?: ActionRequest("invalid", ActionName.CHAT, ActionParameters.Chat)
            return ActionPlanResult(
                plan,
                listOf(PlanStepResult(request, ActionResult.Failure(request.id, request.name, it), PlanStepStatus.FAILED)),
                completed = false,
                cancelled = false
            )
        }

        val results = mutableListOf<PlanStepResult>()
        return try {
            for (request in plan.steps) {
                val result = runtime.execute(context, request, actionContext)
                val status = when (result) {
                    is ActionResult.Success -> PlanStepStatus.VERIFIED
                    is ActionResult.Started -> PlanStepStatus.RUNNING
                    is ActionResult.Cancelled -> PlanStepStatus.CANCELLED
                    is ActionResult.Failure, is ActionResult.Blocked, is ActionResult.PermissionRequired, is ActionResult.Unsupported, is ActionResult.TimedOut -> PlanStepStatus.FAILED
                }
                results += PlanStepResult(request, result, status)
                if (result is ActionResult.Failure || result is ActionResult.Blocked || result is ActionResult.PermissionRequired || result is ActionResult.Unsupported || result is ActionResult.TimedOut || result is ActionResult.Cancelled) {
                    return ActionPlanResult(plan, results.toList(), completed = false, cancelled = result is ActionResult.Cancelled)
                }
                if (result is ActionResult.Started) {
                    // A started-but-unverified step must not unlock later steps.
                    return ActionPlanResult(plan, results.toList(), completed = false, cancelled = false)
                }
            }
            ActionPlanResult(plan, results.toList(), completed = true, cancelled = false)
        } catch (_: CancellationException) {
            ActionPlanResult(plan, results.toList(), completed = false, cancelled = true)
        }
    }

    fun cancel(actionId: String): Boolean = runtime.cancel(actionId)
    fun cancelAll() = runtime.cancelAll()
}
