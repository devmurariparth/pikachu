package com.example.agent

import android.content.Context
import com.example.action.ActionContext
import com.example.action.ActionParameters
import com.example.action.ActionRequest
import com.example.action.ActionResult
import com.example.action.ActionRuntime
import com.example.action.ActionPlanPipeline
import com.example.action.PlanStepStatus
import com.example.voice.LanguageNormalizer

/** One local-first orchestration path for parsing, planning, gated tools, verification, and response. */
class AgentPipeline(
    private val runtime: ActionRuntime = ActionRuntime(),
    private val planPipeline: ActionPlanPipeline = ActionPlanPipeline(runtime),
    private val smartToolRouter: SmartToolRouter = SmartToolRouter()
) {
    @Volatile private var lastSafeGoal: String? = null

    suspend fun execute(
        context: Context,
        command: String,
        planner: suspend (AgentPlanningRequest) -> Result<AgentPlan>,
        actionContext: ActionContext = ActionContext(),
        onActionExecutionStarting: (ActionRequest<out ActionParameters>) -> Unit = {}
    ): Result<AgentOutcome> {
        val normalized = LanguageNormalizer.normalize(command)
        val language = normalized.language.code
        val priorContext = lastSafeGoal.takeIf { !it.isNullOrBlank() && hasFollowUpReference(normalized.normalized) }
        val route = smartToolRouter.routeNormalized(normalized, priorContext)
        val localPlan = when (route) {
            is RouteDecision.DirectTool -> AgentPlan.singleAction(
                route.request,
                command,
                route.language,
                speechResponse = "I started the requested action."
            )
            is RouteDecision.ClarificationRequired -> responsePlan(
                route.prompt,
                route.language,
                FinalResponseMode.ASK_CLARIFICATION
            )
            is RouteDecision.Unsupported -> responsePlan(
                "That capability is not available on this device.",
                route.language,
                FinalResponseMode.DIRECT_RESPONSE
            )
            is RouteDecision.PlannerRequired, is RouteDecision.NoMatch -> null
        }
        val plan = localPlan ?: run {
            planner(AgentPlanningRequest(normalized.normalized, language, priorContext)).getOrElse { return Result.failure(it) }
        }

        if (plan.steps.isEmpty()) {
            if (plan.requiredTools.isNotEmpty() || plan.finalResponseMode == FinalResponseMode.SPEAK_RESULT) {
                return Result.failure(AiProviderException(
                    AiProviderErrorCategory.ACTION_ERROR,
                    "The planner returned an incomplete executable plan.",
                    retryable = false
                ))
            }
            val response = AgentResponse(
                status = AgentExecutionStatus.RESPONDED,
                message = plan.speechResponse,
                language = plan.language,
                completedSteps = emptyList(),
                failedSteps = emptyList()
            )
            return Result.success(AgentOutcome(plan, null, response, fromLocalFastPath = localPlan != null))
        }

        planPipeline.validate(plan)?.let { error ->
            return Result.failure(AiProviderException(AiProviderErrorCategory.ACTION_ERROR, error.message, false))
        }
        val execution = planPipeline.execute(context.applicationContext, plan, actionContext, onActionExecutionStarting)
        val outcome = toOutcome(plan, execution, localPlan != null)
        if (execution.steps.none { it.status == PlanStepStatus.FAILED || it.status == PlanStepStatus.CANCELLED }) {
            lastSafeGoal = plan.goal.takeIf { isSafeContextPlan(plan) }
        }
        return Result.success(outcome)
    }

    fun cancel(actionId: String): Boolean = runtime.cancel(actionId)
    fun cancelAll() = runtime.cancelAll()

    private fun responsePlan(message: String, language: String, mode: FinalResponseMode) = AgentPlan(
        id = "route-response",
        goal = "Resolve the user's request safely",
        requiredTools = emptySet(),
        steps = emptyList(),
        riskLevel = AgentRiskLevel.LOW,
        expectedResult = "A routing response is ready.",
        finalResponseMode = mode,
        speechResponse = message,
        language = language
    )

    private fun toOutcome(plan: AgentPlan, execution: com.example.action.ActionPlanResult, local: Boolean): AgentOutcome {
        val completed = execution.steps.filter { it.status == PlanStepStatus.VERIFIED }.map { it.step.id }
        val failed = execution.steps.filter { it.status == PlanStepStatus.FAILED || it.status == PlanStepStatus.CANCELLED }
            .map { it.step.id }
        val status = when {
            execution.cancelled -> AgentExecutionStatus.CANCELLED
            execution.steps.any { it.status == PlanStepStatus.FAILED } && completed.isNotEmpty() -> AgentExecutionStatus.PARTIAL
            execution.steps.any { it.status == PlanStepStatus.FAILED } -> AgentExecutionStatus.FAILED
            execution.completed -> AgentExecutionStatus.VERIFIED
            execution.steps.any { it.status == PlanStepStatus.STARTED } -> AgentExecutionStatus.STARTED
            else -> AgentExecutionStatus.FAILED
        }
        val errorMessage = execution.steps.lastOrNull { it.status == PlanStepStatus.FAILED }
            ?.result?.error?.message
        val message = when (status) {
            AgentExecutionStatus.VERIFIED -> plan.speechResponse
            AgentExecutionStatus.STARTED -> startedMessage(plan.language)
            AgentExecutionStatus.PARTIAL, AgentExecutionStatus.FAILED -> errorMessage ?: failureMessage(plan.language)
            AgentExecutionStatus.CANCELLED -> cancelledMessage(plan.language)
            AgentExecutionStatus.RESPONDED -> plan.speechResponse
        }
        val response = AgentResponse(status, message, plan.language, completed, failed)
        val agentExecution = AgentPlanExecution(
            plan = plan,
            steps = execution.steps.map { AgentStepExecution(it.step, it.result, it.status) },
            status = status
        )
        return AgentOutcome(plan, agentExecution, response, local)
    }

    private fun isSafeContextPlan(plan: AgentPlan): Boolean =
        plan.goal.length <= AgentPlan.MAX_PLAN_TEXT_LENGTH &&
            plan.goal.isNotBlank() &&
            !SENSITIVE_TEXT.containsMatchIn(plan.goal) &&
            plan.requiredTools.isNotEmpty() &&
            plan.requiredTools.all { it in CONTEXT_SAFE_TOOLS }

    private fun hasFollowUpReference(command: String): Boolean = FOLLOW_UP.containsMatchIn(command)

    private fun startedMessage(language: String) = when (language) {
        "gu" -> "મેં વિનંતી કરેલી ક્રિયાઓ શરૂ કરી છે, પણ તે પૂર્ણ થઈ કે નહીં તેની પુષ્ટિ કરી શકતો નથી."
        "hi" -> "मैंने अनुरोधित कार्रवाइयाँ शुरू की हैं, लेकिन उनके पूरा होने की पुष्टि नहीं कर सकता।"
        else -> "I started the requested actions, but I can't verify that they completed."
    }

    private fun failureMessage(language: String) = when (language) {
        "gu" -> "વિનંતી કરેલી ક્રિયા પૂર્ણ થઈ શકી નથી."
        "hi" -> "अनुरोधित कार्रवाई पूरी नहीं हो सकी।"
        else -> "The requested action could not be completed."
    }

    private fun cancelledMessage(language: String) = when (language) {
        "gu" -> "ક્રિયા રદ કરી."
        "hi" -> "कार्रवाई रद्द कर दी गई।"
        else -> "Action cancelled."
    }

    companion object {
        private val FOLLOW_UP = Regex(
            "(?i)\\b(now|then|it|that|there|this|same|that one)\\b|હવે|તે|ત્યાં|આ|એ|अब|तब|यह|वह|वहाँ|उस"
        )
        private val SENSITIVE_TEXT = Regex(
            "(?i)\\b(password|passcode|pin|secret|token|credit card|bank|whatsapp message|sms message)\\b|(?:\\+?\\d[ .()-]?){8,}"
        )
        private val CONTEXT_SAFE_TOOLS = setOf(
            com.example.action.ActionName.OPEN_APP,
            com.example.action.ActionName.SEARCH_WEB,
            com.example.action.ActionName.PLAY_MUSIC,
            com.example.action.ActionName.PLAY_VIDEO
        )
    }
}
