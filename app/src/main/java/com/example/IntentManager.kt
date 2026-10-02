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
        AssistantLogger.i(taskId, "Executing action: $action")
        
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
                "PLAY_VIDEO" -> {
                    val query = payload?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw AssistantException(ErrorCategory.ACTION_FAILED, "Missing video search query.")
                    val youtubeUri = Uri.parse("https://www.youtube.com/results")
                        .buildUpon()
                        .appendQueryParameter("search_query", query)
                        .build()
                    val appIntent = Intent(Intent.ACTION_VIEW, youtubeUri).apply {
                        setPackage("com.google.android.youtube")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (appIntent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(appIntent)
                    } else {
                        val browserIntent = Intent(Intent.ACTION_VIEW, youtubeUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(browserIntent)
                    }
                }
                "PLAY_MUSIC", "OPEN_MUSIC" -> {
                    val query = payload ?: throw AssistantException(ErrorCategory.ACTION_FAILED, "Missing song title.")
                    val command = com.example.music.MusicActionManager.parseMusicCommand("play $query")
                        ?: com.example.music.MusicCommand(song = query)
                    val result = com.example.music.MusicActionManager.executeMusicCommand(context, command)
                    if (result is com.example.music.MusicExecutionResult.Error) {
                        throw AssistantException(ErrorCategory.ACTION_FAILED, result.spokenResponse)
                    }
                }
                "PLAY_VIDEO" -> {
                    val query = payload ?: throw AssistantException(ErrorCategory.ACTION_FAILED, "Missing video search query.")
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                "CALL" -> {
                    val target = payload ?: throw AssistantException(ErrorCategory.ACTION_FAILED, "Missing contact or phone number.")
                    when (val outcome = com.example.contact.ContactsManager.lookupContact(context, target)) {
                        is com.example.contact.ContactLookupOutcome.SingleMatch -> {
                            val res = com.example.contact.CallActionManager.executeCall(context, outcome.entry.displayName, outcome.entry.phoneNumber)
                            if (res is com.example.contact.CallExecutionResult.Failed) {
                                throw AssistantException(ErrorCategory.ACTION_FAILED, res.reason)
                            }
                        }
                        is com.example.contact.ContactLookupOutcome.DirectNumber -> {
                            val res = com.example.contact.CallActionManager.executeCall(context, outcome.phoneNumber, outcome.phoneNumber)
                            if (res is com.example.contact.CallExecutionResult.Failed) {
                                throw AssistantException(ErrorCategory.ACTION_FAILED, res.reason)
                            }
                        }
                        is com.example.contact.ContactLookupOutcome.ContactHasNoNumber -> {
                            throw AssistantException(ErrorCategory.ACTION_FAILED, "That contact doesn't have a phone number.", canRetry = false)
                        }
                        is com.example.contact.ContactLookupOutcome.NotFound -> {
                            throw AssistantException(ErrorCategory.ACTION_FAILED, "I couldn't find that contact.", canRetry = false)
                        }
                        is com.example.contact.ContactLookupOutcome.MultipleContacts -> {
                            throw AssistantException(ErrorCategory.ACTION_FAILED, "Multiple contacts found named $target. Please specify which one.", canRetry = false)
                        }
                        is com.example.contact.ContactLookupOutcome.MultipleNumbersForContact -> {
                            val labels = outcome.numbers.map { it.typeLabel.lowercase() }.distinct().joinToString(", or ")
                            throw AssistantException(ErrorCategory.ACTION_FAILED, "Multiple numbers found for $target: $labels.", canRetry = false)
                        }
                        is com.example.contact.ContactLookupOutcome.PermissionRequired -> {
                            throw AssistantException(ErrorCategory.PERMISSION_ERROR, outcome.message)
                        }
                        is com.example.contact.ContactLookupOutcome.Error -> {
                            throw AssistantException(ErrorCategory.ACTION_FAILED, outcome.message)
                        }
                    }
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
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(homeIntent)
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
                "SEND_SMS" -> {
                    val target = payload ?: throw AssistantException(ErrorCategory.ACTION_FAILED, "Missing message details.")
                    val parts = target.split("|", limit = 2)
                    val contact = parts.getOrNull(0)?.trim() ?: ""
                    val message = parts.getOrNull(1)?.trim()
                    val result = com.example.sms.SmsActionManager.sendSms(context, contact, message)
                    if (!result.success) {
                        throw AssistantException(ErrorCategory.ACTION_FAILED, result.spokenMessage)
                    }
                }
                "SET_TIMER" -> {
                    val minutes = payload?.filter { it.isDigit() }?.toIntOrNull() ?: 5
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                "OPEN_URL" -> {
                    val rawUrl = payload ?: throw AssistantException(ErrorCategory.ACTION_FAILED, "Missing URL.")
                    val validUrl = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) "https://$rawUrl" else rawUrl
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
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
            AssistantLogger.w("IntentManager", "Sync execution failed for $action")
            false
        }
    }
}
