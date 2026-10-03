package com.example.agent

import com.example.action.ActionName
import com.example.action.ActionParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanParserTest {
    private fun singleAction(
        action: String = "PLAY_VIDEO",
        payload: String = "\"Naruto\"",
        risk: String = "LOW",
        tools: String = "\"$action\""
    ) = """{"goal":"Find a video","required_tools":[$tools],"risk_level":"$risk","expected_result":"Android accepts the video request","final_response_mode":"SPEAK_RESULT","speech":"Starting the search.","lang":"en","steps":[{"id":"search","action":"$action","payload":$payload,"depends_on":[],"risk_level":"$risk","expected_result":"Video search is opened"}]}"""

    @Test fun valid_single_step_plan_becomes_typed_tool_request() {
        val parsed = AgentPlanParser.parse(singleAction(), "gu")
        assertTrue(parsed.isSuccess)
        val plan = parsed.getOrThrow()
        assertEquals(setOf(ActionName.PLAY_VIDEO), plan.requiredTools)
        assertEquals(ActionName.PLAY_VIDEO, plan.steps.single().request.name)
        assertEquals("Naruto", (plan.steps.single().request.parameters as ActionParameters.PlayVideo).query)
        assertEquals("gu", plan.language)
    }

    @Test fun valid_multistep_dependencies_are_preserved_in_order() {
        val json = """{"goal":"Set alarm then search","required_tools":["SET_ALARM","SEARCH_WEB"],"risk_level":"MEDIUM","expected_result":"Both requests are accepted","final_response_mode":"SPEAK_RESULT","speech":"I started both requests.","lang":"hi","steps":[{"id":"alarm","action":"SET_ALARM","payload":"7","depends_on":[],"risk_level":"MEDIUM","expected_result":"Clock receives the alarm request"},{"id":"search","action":"SEARCH_WEB","payload":"morning routine","depends_on":["alarm"],"risk_level":"LOW","expected_result":"Search opens"}]}"""
        val parsed = AgentPlanParser.parse(json, "hi")
        assertTrue(parsed.isSuccess)
        assertEquals(listOf("alarm", "search"), parsed.getOrThrow().steps.map { it.id })
        assertEquals(listOf("alarm"), parsed.getOrThrow().steps.last().dependsOn)
        assertEquals(AgentRiskLevel.MEDIUM, parsed.getOrThrow().riskLevel)
    }

    @Test fun direct_response_and_clarification_can_have_no_tools() {
        val json = """{"goal":"Answer greeting","required_tools":[],"risk_level":"LOW","expected_result":"A response is ready","final_response_mode":"DIRECT_RESPONSE","speech":"Hello!","lang":"en","steps":[]}"""
        assertTrue(AgentPlanParser.parse(json, "en").isSuccess)
    }

    @Test fun malformed_json_and_extra_fields_fail_safely() {
        assertTrue(AgentPlanParser.parse("{oops", "en").isFailure)
        val extra = singleAction().dropLast(1) + ",\"raw_action\":\"RUN_SHELL\"}"
        assertTrue(AgentPlanParser.parse(extra, "en").isFailure)
    }

    @Test fun unsupported_action_and_tool_mismatch_are_rejected() {
        assertTrue(AgentPlanParser.parse(singleAction(action = "RUN_SHELL", tools = "\"RUN_SHELL\""), "en").isFailure)
        assertTrue(AgentPlanParser.parse(singleAction(tools = "\"OPEN_APP\""), "en").isFailure)
    }

    @Test fun risk_understatement_and_invalid_parameters_are_rejected() {
        assertTrue(AgentPlanParser.parse(singleAction(action = "SEND_SMS", payload = "\"Mom|hi\"", risk = "LOW"), "en").isFailure)
        assertTrue(AgentPlanParser.parse(singleAction(action = "SET_BRIGHTNESS", payload = "\"500\"", risk = "MEDIUM"), "en").isFailure)
    }

    @Test fun duplicate_and_forward_dependencies_are_rejected() {
        val forward = """{"goal":"do two things","required_tools":["CHAT"],"risk_level":"LOW","expected_result":"done","final_response_mode":"SPEAK_RESULT","speech":"Started","lang":"en","steps":[{"id":"first","action":"CHAT","payload":null,"depends_on":["second"],"risk_level":"LOW","expected_result":"response ready"},{"id":"second","action":"CHAT","payload":null,"depends_on":[],"risk_level":"LOW","expected_result":"response ready"}]}"""
        assertTrue(AgentPlanParser.parse(forward, "en").isFailure)
        assertTrue(AgentPlanParser.parse(singleAction().replace("\"id\":\"search\"", "\"id\":\"bad id\""), "en").isFailure)
    }
}
