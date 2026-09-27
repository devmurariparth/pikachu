package com.example.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ActionPlanPipelineTest {
    @Test
    fun empty_plan_fails_validation() {
        val runtime = ActionRuntime()
        val pipeline = ActionPlanPipeline(runtime)
        val plan = ActionPlan("p1", emptyList())

        val error = pipeline.validate(plan)

        assertNotNull(error)
        assertEquals(ActionErrorCode.INVALID_PARAMETERS, error?.code)
    }

    @Test
    fun blank_step_id_fails_validation() {
        val runtime = ActionRuntime()
        val pipeline = ActionPlanPipeline(runtime)
        val plan = ActionPlan(
            "p1",
            listOf(ActionRequest("", ActionName.CHAT, ActionParameters.Chat))
        )

        val error = pipeline.validate(plan)

        assertEquals(ActionErrorCode.INVALID_PARAMETERS, error?.code)
    }
}
