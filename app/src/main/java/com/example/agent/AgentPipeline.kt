package com.example.agent

import android.content.Context
import com.example.PlannedAction
import com.example.action.ActionContext
import com.example.action.ActionResult
import com.example.action.ActionRuntime
import com.example.action.PlannedActionMapper
import com.example.voice.LanguageNormalizer
import java.util.UUID

class AgentPipeline(private val runtime: ActionRuntime = ActionRuntime()) {
    suspend fun execute(
        context: Context,
        command: String,
        planner: suspend (String, String) -> Result<PlannedAction>,
        actionContext: ActionContext = ActionContext()
    ): Result<AgentOutcome> {
        val normalized = LanguageNormalizer.normalize(command)
        return when (val local = LocalIntentRouter.route(command)) {
            is LocalRoute.Handled -> Result.success(
                AgentOutcome(local.language, runtime.execute(context, local.request, actionContext), true)
            )
            LocalRoute.FallbackToAi -> {
                val planned = planner(normalized.normalized, normalized.language.code).getOrElse { return Result.failure(it) }
                val request = PlannedActionMapper.map(planned, "AI_" + UUID.randomUUID()).getOrElse { return Result.failure(it) }
                Result.success(AgentOutcome(planned.language, runtime.execute(context, request, actionContext), false))
            }
        }
    }

    fun cancel(actionId: String) = runtime.cancel(actionId)
    fun cancelAll() = runtime.cancelAll()
}

data class AgentOutcome(val language: String, val result: ActionResult, val fromLocalFastPath: Boolean)
