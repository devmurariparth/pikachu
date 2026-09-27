package com.example.action

import com.example.PlannedAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedActionMapperTest {
    @Test
    fun planner_output_is_converted_to_typed_parameters() {
        val result = PlannedActionMapper.map(
            PlannedAction(
                action = "SET_BRIGHTNESS",
                payload = "75",
                speechResponse = "Done",
                language = "en"
            ),
            "a1"
        )

        assertTrue(result.isSuccess)
        val request = result.getOrThrow()
        assertEquals(ActionName.SET_BRIGHTNESS, request.name)
        assertEquals(75, (request.parameters as ActionParameters.SetBrightness).percent)
    }

    @Test
    fun malformed_planner_output_is_rejected() {
        val result = PlannedActionMapper.map(
            PlannedAction(
                action = "SET_BRIGHTNESS",
                payload = "not-a-number",
                speechResponse = "Done",
                language = "en"
            ),
            "a1"
        )

        assertTrue(result.isFailure)
    }
}
