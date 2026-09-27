package com.example.network

import com.example.ErrorCategory
import java.io.IOException
import java.net.SocketTimeoutException

sealed class ApiResult<out T> {
    data class Success<out T>(val data: T) : ApiResult<T>()
    data class Error(val category: ErrorCategory, val message: String, val code: Int? = null) : ApiResult<Nothing>()
}

suspend fun <T> safeApiCall(apiCall: suspend () -> T): ApiResult<T> {
    return try {
        ApiResult.Success(apiCall())
    } catch (e: retrofit2.HttpException) {
        val code = e.code()
        val category = when (code) {
            400 -> ErrorCategory.API_ERROR
            401, 403 -> ErrorCategory.PERMISSION_ERROR
            404 -> ErrorCategory.API_ERROR
            429 -> ErrorCategory.AI_SERVICE_ERROR
            in 500..599 -> ErrorCategory.AI_SERVICE_ERROR
            else -> ErrorCategory.UNKNOWN_ERROR
        }
        ApiResult.Error(category, "AI provider request failed (HTTP $code).", code)
    } catch (e: SocketTimeoutException) {
        ApiResult.Error(ErrorCategory.TIMEOUT_ERROR, "Request timed out. Please try again.")
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        ApiResult.Error(ErrorCategory.TIMEOUT_ERROR, "Request timed out. Please try again.")
    } catch (e: java.util.concurrent.TimeoutException) {
        ApiResult.Error(ErrorCategory.TIMEOUT_ERROR, "Request timed out. Please try again.")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: IOException) {
        ApiResult.Error(ErrorCategory.NETWORK_ERROR, "Network Error: ${e.message}")
    } catch (e: Exception) {
        ApiResult.Error(ErrorCategory.UNKNOWN_ERROR, "Unexpected error: ${e.message}")
    }
}
