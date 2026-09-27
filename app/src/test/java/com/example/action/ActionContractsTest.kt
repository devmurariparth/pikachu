package com.example.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionContractsTest {
    @Test
    fun success_is_verified() {
        val result = ActionResult.Success("a1", ActionName.OPEN_APP, "ok")
        assertEquals(VerificationStatus.VERIFIED, result.verification)
        assertEquals(null, result.error)
    }

    @Test
    fun blocked_is_never_verified() {
        val result = ActionResult.Blocked(
            "a1",
            ActionName.CALL,
            ActionError(ActionErrorCode.PERMISSION_REQUIRED, "permission")
        )
        assertTrue(result.verification != VerificationStatus.VERIFIED)
        assertEquals(ActionErrorCode.PERMISSION_REQUIRED, result.error?.code)
    }

    @Test
    fun typed_request_preserves_parameters() {
        val request = ActionRequest(
            id = "a1",
            name = ActionName.SET_BRIGHTNESS,
            parameters = ActionParameters.SetBrightness(80)
        )
        assertEquals(80, request.parameters.percent)
    }
}
