package com.example

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object IntentManager {
    suspend fun executeAction(context: Context, action: String, payload: String? = null): Result<Unit> = withContext(Dispatchers.Main) {
        val taskId = "EXECUTION_${System.currentTimeMillis()}"
        AssistantLogger.i(taskId, "Executing action: $action with payload: $payload")
        
        try {
            when (action) {
                "OPEN_APP" -> {
                    val packageName = payload ?: throw AssistantException(ErrorCategory.ACTION_FAILED, "Missing app package name.")
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        context.startActivity(launchIntent)
                        AssistantLogger.i(taskId, "Successfully launched app: $packageName")
                    } else {
                        throw AssistantException(ErrorCategory.APP_NOT_FOUND, "App is not installed.", canRetry = false)
                    }
                }
                "SEARCH_WEB" -> {
                    val query = payload ?: throw AssistantException(ErrorCategory.ACTION_FAILED, "Missing search query.")
                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                        putExtra("query", query)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                "CALL" -> {
                    val number = payload ?: throw AssistantException(ErrorCategory.ACTION_FAILED, "Missing phone number.")
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        @Suppress("UseKtx")
                        data = Uri.parse("tel:$number")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                "OPEN_SETTINGS" -> {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                "NAVIGATE" -> {
                    val destination = payload ?: throw AssistantException(ErrorCategory.ACTION_FAILED, "Missing destination.")
                    @Suppress("UseKtx")
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$destination")).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    } else {
                        @Suppress("UseKtx")
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$destination")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(webIntent)
                    }
                }
                "SET_ALARM" -> {
                    val hour = payload?.toIntOrNull() ?: throw AssistantException(ErrorCategory.ACTION_FAILED, "Invalid alarm hour.")
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, 0)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                "GO_HOME" -> {
                    val service = AssistantService.instance
                    if (service != null) {
                        service.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    } else {
                        // Fallback to Home Intent
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(homeIntent)
                    }
                }
                "GO_BACK" -> {
                    val service = AssistantService.instance
                    if (service != null) {
                        service.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    } else {
                        throw AssistantException(
                            ErrorCategory.PERMISSION_ERROR,
                            "Accessibility service is needed to trigger Go Back. Please enable it in Settings.",
                            canRetry = false
                        )
                    }
                }
                "OPEN_NOTIFICATIONS" -> {
                    val service = AssistantService.instance
                    if (service != null) {
                        service.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                    } else {
                        throw AssistantException(
                            ErrorCategory.PERMISSION_ERROR,
                            "Accessibility service is needed to open notifications. Please enable it in Settings.",
                            canRetry = false
                        )
                    }
                }
                "RECENT_APPS" -> {
                    val service = AssistantService.instance
                    if (service != null) {
                        service.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                    } else {
                        throw AssistantException(
                            ErrorCategory.PERMISSION_ERROR,
                            "Accessibility service is needed to show recent apps. Please enable it in Settings.",
                            canRetry = false
                        )
                    }
                }
                "CHAT" -> {
                    // Handled purely by speech response.
                }
                "SHOW_MEMORIES" -> {
                    // Spoken and displayed via response text.
                }
                "FORGET_MEMORY" -> {
                    val target = payload ?: "all"
                    com.example.data.UserMemoryManager.forgetMemory(target)
                }
                "REMEMBER_PREFERENCE" -> {
                    val preference = payload ?: throw AssistantException(ErrorCategory.ACTION_FAILED, "Missing preference to remember.")
                    val result = com.example.data.UserMemoryManager.rememberPreference(preference)
                    if (result is com.example.data.SaveMemoryResult.RequiresSensitiveConsent) {
                        throw AssistantException(
                            ErrorCategory.PERMISSION_ERROR,
                            "Explicit consent required to store sensitive data: ${result.sensitiveType}",
                            canRetry = false
                        )
                    }
                }
                "TOGGLE_WIFI" -> {
                    val enable = when (payload?.lowercase()?.trim()) {
                        "on", "true", "enable" -> true
                        "off", "false", "disable" -> false
                        else -> null
                    }
                    val result = com.example.device.DeviceControlManager.toggleWifi(context, enable)
                    if (!result.success) {
                        throw AssistantException(ErrorCategory.ACTION_FAILED, result.spokenMessage)
                    }
                }
                "TOGGLE_BLUETOOTH" -> {
                    val enable = when (payload?.lowercase()?.trim()) {
                        "on", "true", "enable" -> true
                        "off", "false", "disable" -> false
                        else -> null
                    }
                    val result = com.example.device.DeviceControlManager.toggleBluetooth(context, enable)
                    if (!result.success) {
                        throw AssistantException(ErrorCategory.ACTION_FAILED, result.spokenMessage)
                    }
                }
                "SET_BRIGHTNESS" -> {
                    val percent = payload?.filter { it.isDigit() }?.toIntOrNull() ?: 75
                    val result = com.example.device.DeviceControlManager.setBrightness(context, percent)
                    if (!result.success) {
                        throw AssistantException(ErrorCategory.ACTION_FAILED, result.spokenMessage)
                    }
                }
                "OPEN_QUICK_SETTINGS" -> {
                    val result = com.example.device.DeviceControlManager.openQuickSettings()
                    if (!result.success) {
                        throw AssistantException(ErrorCategory.PERMISSION_ERROR, result.spokenMessage)
                    }
                }
                "SEND_WHATSAPP" -> {
                    val target = payload ?: "Contact"
                    val parts = target.split("|", limit = 2)
                    val contact = parts.getOrNull(0)?.trim() ?: "Contact"
                    val message = parts.getOrNull(1)?.trim()
                    val result = com.example.WhatsAppManager.sendWhatsApp(context, contact, message)
                    if (!result.success) {
                        throw AssistantException(ErrorCategory.ACTION_FAILED, result.spokenMessage)
                    }
                }
                "CREATE_TASK" -> {
                    val taskTitle = payload ?: "Task"
                    com.example.data.task.TaskManager.createTask(title = taskTitle, isVoiceCaptured = true)
                }
                "COMPLETE_TASK" -> {
                    val taskTitle = payload ?: ""
                    val dao = com.example.data.task.TaskDatabase.getDatabase(context).taskDao()
                    val task = dao.findTaskByTitle(taskTitle)
                    if (task != null) {
                        com.example.data.task.TaskManager.setTaskCompleted(task.id, true)
                    }
                }
                else -> {
                    throw AssistantException(ErrorCategory.UNSUPPORTED_ACTION, "Unknown action: $action")
                }
            }
            Result.success(Unit)
        } catch (e: ActivityNotFoundException) {
            AssistantLogger.w(taskId, "Activity not found for action: $action")
            Result.failure(AssistantException(ErrorCategory.APP_LAUNCH_ERROR, "No application found to handle this action.", canRetry = false))
        } catch (e: SecurityException) {
            AssistantLogger.w(taskId, "Permission denied for action: $action")
            Result.failure(AssistantException(ErrorCategory.PERMISSION_ERROR, "Permission denied. Check settings.", canRetry = false))
        } catch (e: AssistantException) {
            AssistantLogger.logError(e)
            Result.failure(e)
        } catch (e: Exception) {
            AssistantLogger.e(taskId, "Unexpected execution error", e)
            Result.failure(AssistantException(ErrorCategory.UNKNOWN_ERROR, "Action failed unexpectedly.", canRetry = true))
        }
    }

    fun executeActionSync(context: Context, action: String, payload: String? = null): Boolean {
        return try {
            when (action) {
                "GO_HOME" -> {
                    val service = AssistantService.instance
                    if (service != null) {
                        service.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    } else {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(homeIntent)
                    }
                    true
                }
                "GO_BACK" -> {
                    AssistantService.instance?.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) ?: false
                }
                "OPEN_NOTIFICATIONS" -> {
                    AssistantService.instance?.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) ?: false
                }
                "RECENT_APPS" -> {
                    AssistantService.instance?.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
                }
                "OPEN_QUICK_SETTINGS" -> {
                    com.example.device.DeviceControlManager.openQuickSettings().success
                }
                "TOGGLE_WIFI" -> {
                    val enable = when (payload?.lowercase()?.trim()) {
                        "on", "true", "enable" -> true
                        "off", "false", "disable" -> false
                        else -> null
                    }
                    com.example.device.DeviceControlManager.toggleWifi(context, enable).success
                }
                "TOGGLE_BLUETOOTH" -> {
                    val enable = when (payload?.lowercase()?.trim()) {
                        "on", "true", "enable" -> true
                        "off", "false", "disable" -> false
                        else -> null
                    }
                    com.example.device.DeviceControlManager.toggleBluetooth(context, enable).success
                }
                "SET_BRIGHTNESS" -> {
                    val percent = payload?.filter { it.isDigit() }?.toIntOrNull() ?: 70
                    com.example.device.DeviceControlManager.setBrightness(context, percent).success
                }
                "SEND_WHATSAPP" -> {
                    val target = payload ?: "Contact"
                    val parts = target.split("|", limit = 2)
                    val contact = parts.getOrNull(0)?.trim() ?: "Contact"
                    val message = parts.getOrNull(1)?.trim()
                    com.example.WhatsAppManager.sendWhatsApp(context, contact, message).success
                }
                else -> false
            }
        } catch (e: Exception) {
            AssistantLogger.w("IntentManager", "Sync execution failed for $action: ${e.message}")
            false
        }
    }
}
