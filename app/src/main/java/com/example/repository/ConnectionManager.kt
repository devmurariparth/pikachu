package com.example.repository

import com.example.AssistantLogger
import com.example.BuildConfig
import com.example.ErrorCategory
import com.example.network.ApiResult
import com.example.network.Content
import com.example.network.GenerateContentRequest
import com.example.network.Part
import com.example.network.RetrofitClient
import com.example.network.safeApiCall
import kotlinx.coroutines.delay

data class ConnectionTestResult(
    val isSuccess: Boolean,
    val message: String,
    val errorCategory: ErrorCategory? = null
)

object ConnectionManager {
    private const val TAG = "ConnectionManager"
    private const val MAX_RETRIES = 3
    private const val INITIAL_BACKOFF_MS = 1000L

    suspend fun testConnection(overrideApiKey: String? = null): ConnectionTestResult {
        var attempt = 1
        var currentBackoff = INITIAL_BACKOFF_MS
        val keyToTest = (overrideApiKey ?: com.example.data.AppSettingsManager.getActiveApiKey()).trim()

        if (keyToTest.isEmpty()) {
            return ConnectionTestResult(
                isSuccess = false,
                message = "No Gemini API key configured. Please enter your API key in Settings.",
                errorCategory = ErrorCategory.PERMISSION_ERROR
            )
        }

        while (attempt <= MAX_RETRIES) {
            AssistantLogger.i(TAG, "Testing Gemini API connection (Attempt $attempt/$MAX_RETRIES)...")
            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = "ping")), role = "user"))
            )
            val apiResult = safeApiCall {
                RetrofitClient.service.generateFlashContent(
                    apiKey = keyToTest,
                    request = request
                )
            }
            when (apiResult) {
                is ApiResult.Success -> {
                    AssistantLogger.i(TAG, "Connection test successful on attempt $attempt.")
                    return ConnectionTestResult(
                        isSuccess = true,
                        message = "Connected to Gemini API successfully!"
                    )
                }
                is ApiResult.Error -> {
                    AssistantLogger.e(TAG, "Connection attempt $attempt failed: ${apiResult.category}")
                    if (attempt == MAX_RETRIES) {
                        return ConnectionTestResult(
                            isSuccess = false,
                            message = "Failed to connect to Gemini API after $MAX_RETRIES attempts: ${apiResult.message}",
                            errorCategory = apiResult.category
                        )
                    }
                }
            }
            AssistantLogger.i(TAG, "Waiting ${currentBackoff}ms before retry...")
            delay(currentBackoff)
            currentBackoff *= 2
            attempt++
        }

        return ConnectionTestResult(
            isSuccess = false,
            message = "Failed to connect to Gemini API after $MAX_RETRIES attempts. Please check your internet connection.",
            errorCategory = ErrorCategory.NETWORK_ERROR
        )
    }
}
