package com.example.network

import com.example.ErrorCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiResultTest {
    @Test
    fun provider_error_keeps_typed_category_and_code() {
        val result = ApiResult.Error(
            category = ErrorCategory.AI_SERVICE_ERROR,
            message = "Provider unavailable.",
            code = 503
        )

        assertEquals(ErrorCategory.AI_SERVICE_ERROR, result.category)
        assertEquals(503, result.code)
    }
}
