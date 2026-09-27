package com.example

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwableMessage: String? = null
) {
    fun getFormattedTime(): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    }

    fun toFormattedString(): String {
        val levelSymbol = when (level) {
            LogLevel.DEBUG -> "[DEBUG]"
            LogLevel.INFO -> "[INFO ]"
            LogLevel.WARN -> "[WARN ]"
            LogLevel.ERROR -> "[ERROR]"
        }
        val base = "${getFormattedTime()} $levelSymbol [$tag] $message"
        return if (throwableMessage != null) "$base\n    Exception: $throwableMessage" else base
    }
}

object AssistantLogger {
    private const val TAG = "MJ_Assistant"
    private const val MAX_LOG_BUFFER_SIZE = 250

    // Sensitive data scrubbing patterns
    private val API_KEY_REGEX = "(AIza[0-9A-Za-z-_]{20,45})".toRegex()
    private val CREDIT_CARD_REGEX = "\\b(?:\\d[ -]*?){13,16}\\b".toRegex()
    private val PASSWORD_PATTERN_REGEX = "(?i)(password|pin|secret|token)\\s*[:=]\\s*['\"]?([^'\"\\s]+)['\"]?".toRegex()

    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private fun sanitize(input: String): String {
        var sanitized = input
        sanitized = API_KEY_REGEX.replace(sanitized, "AIza********************************")
        sanitized = CREDIT_CARD_REGEX.replace(sanitized) { match ->
            val digits = match.value.filter { it.isDigit() }
            if (digits.length in 13..16) "****-****-****-${digits.takeLast(4)}" else match.value
        }
        sanitized = PASSWORD_PATTERN_REGEX.replace(sanitized) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        sanitized = QUERY_KEY_REGEX.replace(sanitized, "$1[REDACTED]")
        return sanitized
    }

    private fun addEntry(level: LogLevel, tag: String, rawMessage: String, throwable: Throwable? = null) {
        val cleanMsg = sanitize(rawMessage)
        val cleanThrowable = throwable?.let { sanitize(it.message ?: it.javaClass.simpleName) }
        val entry = LogEntry(
            level = level,
            tag = tag,
            message = cleanMsg,
            throwableMessage = cleanThrowable
        )

        logQueue.add(entry)
        while (logQueue.size > MAX_LOG_BUFFER_SIZE) {
            logQueue.poll()
        }
        _logs.value = logQueue.toList()
    }

    fun d(task: String, message: String) {
        val clean = sanitize(message)
        Log.d(TAG, "[$task] $clean")
        addEntry(LogLevel.DEBUG, task, clean)
    }

    fun i(task: String, message: String) {
        val clean = sanitize(message)
        Log.i(TAG, "[$task] $clean")
        addEntry(LogLevel.INFO, task, clean)
    }

    fun w(task: String, message: String) {
        val clean = sanitize(message)
        Log.w(TAG, "[$task] $clean")
        addEntry(LogLevel.WARN, task, clean)
    }

    fun e(task: String, message: String, throwable: Throwable? = null) {
        val clean = sanitize(message)
        val cleanThrowable = throwable?.let { sanitize(it.message ?: it.javaClass.simpleName) }
        if (cleanThrowable != null) {
            Log.e(TAG, "[$task] $clean — $cleanThrowable")
        } else {
            Log.e(TAG, "[$task] $clean")
        }
        addEntry(LogLevel.ERROR, task, clean, throwable)
    }

    fun logState(oldState: String, newState: String, reason: String? = null) {
        val msg = if (reason != null) "State: $oldState -> $newState ($reason)" else "State: $oldState -> $newState"
        i("STATE", msg)
    }

    fun logError(error: AssistantException) {
        e("ERROR_HANDLER", "Category: ${error.category}, Message: ${error.message}, CanRetry: ${error.canRetry}", error)
    }

    fun clear() {
        logQueue.clear()
        _logs.value = emptyList()
        i("LOGGER", "Logs cleared by user")
    }

    fun getAllLogsFormatted(): String {
        return _logs.value.joinToString("\n") { it.toFormattedString() }
    }
}
