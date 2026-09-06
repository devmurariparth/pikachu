package com.example

import android.content.Context
import android.os.Handler
import android.os.Looper

object CrashPreventionManager {
    private var isInitialized = false
    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private const val PREFS_NAME = "mj_crash_telemetry"
    private const val KEY_LAST_CRASH_TIME = "last_crash_timestamp"
    private const val KEY_LAST_CRASH_MESSAGE = "last_crash_message"

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val appContext = context.applicationContext
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AssistantLogger.e(
                "CRASH_PREVENTION",
                "Uncaught exception on thread '${thread.name}': ${throwable.message}",
                throwable
            )

            // Save crash telemetry locally
            try {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
                    .putString(KEY_LAST_CRASH_MESSAGE, "${throwable.javaClass.simpleName}: ${throwable.message}")
                    .apply()
            } catch (e: Exception) {
                // Ignore secondary preference failure
            }

            // If it's a background worker thread, prevent immediate process termination
            if (thread != Looper.getMainLooper().thread) {
                AssistantLogger.w(
                    "CRASH_PREVENTION",
                    "Caught uncaught background exception on '${thread.name}'. Suppressing fatal crash to maintain assistant stability."
                )
                return@setDefaultUncaughtExceptionHandler
            }

            // If on main thread, pass to original handler after flushing logs
            originalHandler?.uncaughtException(thread, throwable)
        }

        AssistantLogger.i("CRASH_PREVENTION", "Global crash prevention & telemetry installed successfully.")
    }

    fun getLastCrashReport(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
        val msg = prefs.getString(KEY_LAST_CRASH_MESSAGE, null)
        return if (timestamp > 0 && msg != null) {
            "Last unexpected event: $msg"
        } else null
    }

    fun clearCrashReport(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
