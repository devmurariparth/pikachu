package com.example.agent

import com.example.action.ActionName
import com.example.action.ActionParameters
import com.example.action.ActionRequest
import com.example.action.ActionResult

enum class AgentRiskLevel { LOW, MEDIUM, HIGH }

enum class FinalResponseMode { DIRECT_RESPONSE, ASK_CLARIFICATION, SPEAK_RESULT }

enum class AgentExecutionStatus { RESPONDED, VERIFIED, STARTED, PARTIAL, FAILED, CANCELLED }

data class AgentPlanningRequest(
    val command: String,
    val language: String,
    val recentGoal: String? = null
)

data class AgentPlanStep(
    val id: String,
    val request: ActionRequest<out ActionParameters>,
    val dependsOn: List<String>,
    val riskLevel: AgentRiskLevel,
    val expectedResult: String
)

data class AgentPlan(
    val id: String,
    val goal: String,
    val requiredTools: Set<ActionName>,
    val steps: List<AgentPlanStep>,
    val riskLevel: AgentRiskLevel,
    val expectedResult: String,
    val finalResponseMode: FinalResponseMode,
    val speechResponse: String,
    val language: String
) {
    companion object {
        fun singleAction(plan: com.example.PlannedAction, request: ActionRequest<out ActionParameters>, goal: String): AgentPlan {
            return singleAction(request, goal, plan.language, plan.speechResponse)
        }

        fun singleAction(
            request: ActionRequest<out ActionParameters>,
            goal: String,
            language: String,
            speechResponse: String = "I started the requested action."
        ): AgentPlan {
            val stepId = "step-1"
            val risk = riskFor(request.name)
            return AgentPlan(
                id = request.id,
                goal = goal.take(MAX_PLAN_TEXT_LENGTH),
                requiredTools = setOf(request.name),
                steps = listOf(AgentPlanStep(stepId, request, emptyList(), risk, "Android accepts the requested action.")),
                riskLevel = risk,
                expectedResult = "The requested Android action is accepted.",
                finalResponseMode = FinalResponseMode.SPEAK_RESULT,
                speechResponse = speechResponse.take(MAX_SPEECH_LENGTH),
                language = language
            )
        }

        const val MAX_PLAN_TEXT_LENGTH = 240
        const val MAX_SPEECH_LENGTH = 320
    }
}

data class AgentStepExecution(
    val step: AgentPlanStep,
    val result: ActionResult,
    val status: com.example.action.PlanStepStatus
)

data class AgentPlanExecution(
    val plan: AgentPlan,
    val steps: List<AgentStepExecution>,
    val status: AgentExecutionStatus
)

data class AgentResponse(
    val status: AgentExecutionStatus,
    val message: String,
    val language: String,
    val completedSteps: List<String>,
    val failedSteps: List<String>
)

data class AgentOutcome(
    val plan: AgentPlan,
    val execution: AgentPlanExecution?,
    val response: AgentResponse,
    val fromLocalFastPath: Boolean
)

fun riskFor(action: ActionName): AgentRiskLevel = when (action) {
    ActionName.CALL, ActionName.SEND_SMS, ActionName.SEND_WHATSAPP,
    ActionName.FORGET_MEMORY, ActionName.REMEMBER_PREFERENCE -> AgentRiskLevel.HIGH
    ActionName.SET_ALARM, ActionName.SET_TIMER, ActionName.TOGGLE_WIFI,
    ActionName.TOGGLE_BLUETOOTH, ActionName.SET_BRIGHTNESS,
    ActionName.CREATE_TASK, ActionName.COMPLETE_TASK, ActionName.NAVIGATE -> AgentRiskLevel.MEDIUM
    else -> AgentRiskLevel.LOW
}

internal fun highestRisk(levels: Iterable<AgentRiskLevel>): AgentRiskLevel =
    levels.maxByOrNull(AgentRiskLevel::ordinal) ?: AgentRiskLevel.LOW
