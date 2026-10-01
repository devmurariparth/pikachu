package com.example.agent

import android.content.Context
import com.example.PlannedAction
import com.example.action.ActionContext
import com.example.action.ActionParameters
import com.example.action.ActionRequest
import com.example.action.ActionResult
import com.example.action.ActionRuntime
import com.example.action.PlannedActionMapper
import com.example.voice.LanguageNormalizer
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AgentPipeline(private val runtime: ActionRuntime = ActionRuntime()) {
    suspend fun execute(
        context: Context,
        command: String,
        planner: suspend (String, String) -> Result<PlannedAction>,
        actionContext: ActionContext = ActionContext(),
        onActionExecutionStarting: (ActionRequest<out ActionParameters>) -> Unit = {}
    ): Result<AgentOutcome> {
        val normalized = LanguageNormalizer.normalize(command)
        return when (val local = LocalIntentRouter.route(command)) {
            is LocalRoute.Handled -> {
                onActionExecutionStarting(local.request)
                val result = runtime.execute(context, local.request, actionContext)
                currentCoroutineContext().ensureActive()
                Result.success(AgentOutcome(local.language, result, true))
            }
            LocalRoute.FallbackToAi -> {
                val planned = planner(normalized.normalized, normalized.language.code).getOrElse { return Result.failure(it) }
                val request = PlannedActionMapper.map(planned, "AI_" + UUID.randomUUID()).getOrElse { return Result.failure(it) }
                onActionExecutionStarting(request)
                val result = runtime.execute(context, request, actionContext)
                currentCoroutineContext().ensureActive()
                Result.success(AgentOutcome(planned.language, result, false, planned))
            }
        }
    }

    fun cancel(actionId: String) = runtime.cancel(actionId)
    fun cancelAll() = runtime.cancelAll()
}

data class AgentOutcome(
    val language: String,
    val result: ActionResult,
    val fromLocalFastPath: Boolean,
    val plannedAction: PlannedAction? = null
)
