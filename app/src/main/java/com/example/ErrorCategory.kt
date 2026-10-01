package com.example

enum class ErrorCategory {
    NETWORK_ERROR,
    TIMEOUT_ERROR,
    AI_SERVICE_ERROR,
    API_ERROR,
    APP_NOT_FOUND,
    APP_LAUNCH_ERROR,
    PERMISSION_ERROR,
    UNSUPPORTED_ACTION,
    ACTION_FAILED,
    UNKNOWN_ERROR
}

class AssistantException(
    val category: ErrorCategory,
    override val message: String,
    val canRetry: Boolean = false
) : Exception(message)

object GlobalErrorHandler {
    fun handleError(category: ErrorCategory, message: String) {
        AssistantLogger.e("ERROR_HANDLER", "Category: $category")
    }
}
