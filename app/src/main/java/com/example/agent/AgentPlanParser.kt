package com.example.agent

import com.example.PlannedAction
import com.example.action.ActionName
import com.example.action.PlannedActionMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** Strictly validates model JSON and converts it into typed action requests before execution. */
object AgentPlanParser {
    private val json = Json { isLenient = false; ignoreUnknownKeys = false }
    private val planKeys = setOf(
        "goal", "required_tools", "risk_level", "expected_result", "final_response_mode",
        "speech", "lang", "steps"
    )
    private val stepKeys = setOf("id", "action", "payload", "depends_on", "risk_level", "expected_result")
    private val languages = setOf("gu", "hi", "en")

    fun parse(response: String, detectedLanguage: String): Result<AgentPlan> {
        val root = try {
            json.parseToJsonElement(response).jsonObject
        } catch (_: Exception) {
            return invalid("The AI returned malformed plan data.")
        }
        if (root.keys != planKeys) return invalid("The AI plan has missing or unexpected fields.")

        val goal = root.string("goal")?.trim()?.takeIf(String::isNotEmpty)
            ?: return invalid("The AI plan has no goal.")
        if (goal.length > AgentPlan.MAX_PLAN_TEXT_LENGTH) return invalid("The AI plan is too long.")
        val expected = root.string("expected_result")?.trim()?.takeIf(String::isNotEmpty)
            ?: return invalid("The AI plan has no expected result.")
        if (expected.length > AgentPlan.MAX_PLAN_TEXT_LENGTH) return invalid("The AI plan expected result is too long.")
        val speech = root.string("speech")?.trim()?.takeIf(String::isNotEmpty)
            ?: return invalid("The AI plan has no user-facing response.")
        if (speech.length > AgentPlan.MAX_SPEECH_LENGTH) return invalid("The AI response is too long.")
        val responseLanguage = root.string("lang")
            ?: return invalid("The AI plan has no response language.")
        if (responseLanguage !in languages) return invalid("The AI plan language is unsupported.")
        val language = detectedLanguage.takeIf { it in languages } ?: responseLanguage

        val responseMode = root.string("final_response_mode")?.let { value ->
            runCatching { FinalResponseMode.valueOf(value) }.getOrNull()
        } ?: return invalid("The AI plan response mode is unsupported.")
        val declaredPlanRisk = parseRisk(root.string("risk_level"))
            ?: return invalid("The AI plan risk level is invalid.")

        val rawTools = root["required_tools"] as? JsonArray
            ?: return invalid("The AI plan tool list is invalid.")
        val declaredTools = mutableListOf<ActionName>()
        for (entry in rawTools) {
            val value = (entry as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: return invalid("The AI plan contains an invalid tool name.")
            val tool = runCatching { ActionName.valueOf(value) }.getOrNull()
                ?: return invalid("The AI plan requests an unsupported tool.")
            declaredTools += tool
        }
        if (declaredTools.size != declaredTools.toSet().size) return invalid("The AI plan repeats a tool name.")

        val rawSteps = root["steps"] as? JsonArray
            ?: return invalid("The AI plan steps are invalid.")
        if (rawSteps.size > MAX_STEPS) return invalid("The AI plan contains too many steps.")
        val planId = UUID.randomUUID().toString()
        val steps = mutableListOf<AgentPlanStep>()
        val seenIds = mutableSetOf<String>()
        val tools = linkedSetOf<ActionName>()

        for (element in rawSteps) {
            val step = element as? JsonObject ?: return invalid("An AI plan step is invalid.")
            if (step.keys != stepKeys) return invalid("An AI plan step has missing or unexpected fields.")
            val id = step.string("id")?.takeIf { STEP_ID.matches(it) }
                ?: return invalid("An AI plan step id is invalid.")
            if (!seenIds.add(id)) return invalid("The AI plan repeats a step id.")
            val action = step.string("action")?.let { runCatching { ActionName.valueOf(it) }.getOrNull() }
                ?: return invalid("The AI plan contains an unsupported action.")
            val payload = when (val value = step["payload"]) {
                JsonNull -> null
                is JsonPrimitive -> if (value.isString) value.content else return invalid("An action payload has an invalid type.")
                else -> return invalid("An action payload has an invalid type.")
            }
            val expectedStep = step.string("expected_result")?.trim()?.takeIf(String::isNotEmpty)
                ?: return invalid("An AI plan step has no expected result.")
            if (expectedStep.length > AgentPlan.MAX_PLAN_TEXT_LENGTH) return invalid("An AI plan step is too long.")
            val declaredRisk = parseRisk(step.string("risk_level"))
                ?: return invalid("An AI plan step risk level is invalid.")
            val minimumRisk = riskFor(action)
            if (declaredRisk.ordinal < minimumRisk.ordinal) return invalid("The AI plan understates an action's risk.")

            val dependencies = parseDependencies(step["depends_on"])
                ?: return invalid("An AI plan dependency list is invalid.")
            if (dependencies.size != dependencies.toSet().size || dependencies.any { it !in seenIds || it == id }) {
                return invalid("An AI plan step depends on a missing, repeated, or later step.")
            }

            val planned = PlannedAction(action.name, payload, speech, language)
            val request = PlannedActionMapper.map(planned, "$planId:$id").getOrElse {
                return invalid("An AI plan action has invalid parameters.")
            }
            steps += AgentPlanStep(id, request, dependencies, declaredRisk, expectedStep)
            tools += action
        }

        if (declaredTools.toSet() != tools) return invalid("The AI plan tool list does not match its steps.")
        val minimumPlanRisk = highestRisk(steps.map(AgentPlanStep::riskLevel))
        if (declaredPlanRisk.ordinal < minimumPlanRisk.ordinal) return invalid("The AI plan understates its risk.")
        if (steps.isEmpty() && (declaredTools.isNotEmpty() || responseMode == FinalResponseMode.SPEAK_RESULT)) {
            return invalid("An executable AI plan must contain at least one step.")
        }
        if (steps.isNotEmpty() && responseMode != FinalResponseMode.SPEAK_RESULT) {
            return invalid("An executable AI plan must use the result response mode.")
        }

        return Result.success(
            AgentPlan(
                id = planId,
                goal = goal,
                requiredTools = tools,
                steps = steps,
                riskLevel = declaredPlanRisk,
                expectedResult = expected,
                finalResponseMode = responseMode,
                speechResponse = speech,
                language = language
            )
        )
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun parseRisk(value: String?): AgentRiskLevel? = value?.let {
        runCatching { AgentRiskLevel.valueOf(it) }.getOrNull()
    }

    private fun parseDependencies(value: JsonElement?): List<String>? = try {
        (value as? JsonArray)?.map { element ->
            (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: return null
        }
    } catch (_: Exception) {
        null
    }

    private fun invalid(message: String): Result<AgentPlan> = Result.failure(
        AiProviderException(AiProviderErrorCategory.ACTION_ERROR, message, retryable = false)
    )

    private val STEP_ID = Regex("[A-Za-z0-9_-]{1,32}")
    private const val MAX_STEPS = 8
}
