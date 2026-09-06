package com.example

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import com.example.data.BatteryOptimizationManager
import com.example.data.UserMemoryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OfflineExecutionResult(
    val handled: Boolean,
    val spokenResponse: String,
    val actionTaken: String? = null,
    val isSystemAction: Boolean = false
)

object OfflineActionHandler {
    private const val TAG = "OfflineActionHandler"

    private val APP_MAP = mapOf(
        "chrome" to "com.android.chrome",
        "google chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "youtube" to "com.google.android.youtube",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "email" to "com.google.android.gm",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",
        "camera" to "com.google.android.GoogleCamera",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "whatsapp" to "com.whatsapp",
        "spotify" to "com.spotify.music",
        "play store" to "com.android.vending",
        "store" to "com.android.vending",
        "files" to "com.google.android.apps.nbu.files"
    )

    suspend fun handleOfflineCommand(context: Context, query: String): OfflineExecutionResult {
        val raw = query.trim()
        val lower = raw.lowercase().trim()
        AssistantLogger.i(TAG, "Processing offline query: '$raw'")

        // 1. WhatsApp Voice Command ("Send a WhatsApp to [contact]")
        val whatsAppMatch = WhatsAppManager.parseWhatsAppVoiceCommand(raw)
        if (whatsAppMatch != null) {
            val res = WhatsAppManager.sendWhatsApp(context, whatsAppMatch.first, whatsAppMatch.second)
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = res.spokenMessage,
                actionTaken = "SEND_WHATSAPP"
            )
        }

        // 1.2. Tasks & Reminders via Room Database & WorkManager
        val taskResult = com.example.data.task.TaskManager.handleVoiceQuery(raw)
        if (taskResult !is com.example.data.task.TaskVoiceResult.NotHandled) {
            val message = when (taskResult) {
                is com.example.data.task.TaskVoiceResult.Created -> taskResult.message
                is com.example.data.task.TaskVoiceResult.Completed -> taskResult.message
                is com.example.data.task.TaskVoiceResult.Deleted -> taskResult.message
                is com.example.data.task.TaskVoiceResult.Listed -> taskResult.message
                else -> "Task updated."
            }
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = message,
                actionTaken = "TASK_ACTION"
            )
        }

        // 1.3. Device Controls: Wi-Fi
        if (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("internet connection")) {
            val turnOn = when {
                lower.contains("on") || lower.contains("enable") || lower.contains("turn on") -> true
                lower.contains("off") || lower.contains("disable") || lower.contains("turn off") -> false
                else -> null
            }
            val res = com.example.device.DeviceControlManager.toggleWifi(context, turnOn)
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = res.spokenMessage,
                actionTaken = "TOGGLE_WIFI",
                isSystemAction = true
            )
        }

        // 1.4. Device Controls: Bluetooth
        if (lower.contains("bluetooth")) {
            val turnOn = when {
                lower.contains("on") || lower.contains("enable") || lower.contains("turn on") -> true
                lower.contains("off") || lower.contains("disable") || lower.contains("turn off") -> false
                else -> null
            }
            val res = com.example.device.DeviceControlManager.toggleBluetooth(context, turnOn)
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = res.spokenMessage,
                actionTaken = "TOGGLE_BLUETOOTH",
                isSystemAction = true
            )
        }

        // 1.5. Device Controls: Screen Brightness
        if (lower.contains("brightness") || lower.contains("dim screen") || lower.contains("brighten screen")) {
            val percent = when {
                lower.contains("max") || lower.contains("100") || lower.contains("full") -> 100
                lower.contains("dim") || lower.contains("minimum") || lower.contains("low") -> 20
                lower.contains("half") || lower.contains("50") -> 50
                lower.contains("increase") || lower.contains("raise") || lower.contains("brighter") -> 85
                lower.contains("decrease") || lower.contains("lower") || lower.contains("darker") -> 30
                else -> {
                    val match = "(\\d{1,3})\\s*%?".toRegex().find(lower)
                    match?.groupValues?.get(1)?.toIntOrNull() ?: 75
                }
            }
            val res = com.example.device.DeviceControlManager.setBrightness(context, percent)
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = res.spokenMessage,
                actionTaken = "SET_BRIGHTNESS",
                isSystemAction = true
            )
        }

        // 1.6. Quick Settings
        if (lower.contains("quick settings") || lower == "open quick settings") {
            val res = com.example.device.DeviceControlManager.openQuickSettings()
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = res.spokenMessage,
                actionTaken = "OPEN_QUICK_SETTINGS",
                isSystemAction = true
            )
        }

        // 1.7. Battery inquiries
        if (lower.contains("battery") || lower == "power level") {
            val status = BatteryOptimizationManager.getBatteryStatusSummary()
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = status,
                actionTaken = "BATTERY_STATUS"
            )
        }

        // 1.5. User Memories ("what do you remember", "what are my preferences", "show memories")
        if (lower.contains("what do you remember") || lower.contains("what are my memories") || lower.contains("my preferences") || lower == "show memories") {
            val summary = UserMemoryManager.getMemoriesSummaryText()
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = summary,
                actionTaken = "SHOW_MEMORIES"
            )
        }

        // 2. Time and Date inquiries
        if (lower.contains("what time") || lower == "time" || lower == "current time") {
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = "The current time is $time.",
                actionTaken = "GET_TIME"
            )
        }

        if (lower.contains("what date") || lower.contains("today's date") || lower == "date") {
            val date = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = "Today is $date.",
                actionTaken = "GET_DATE"
            )
        }

        // 3. System Navigation
        if (lower == "go home" || lower == "take me home" || lower == "home" || lower == "home screen") {
            IntentManager.executeActionSync(context, "GO_HOME")
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = "Going home.",
                actionTaken = "GO_HOME",
                isSystemAction = true
            )
        }

        if (lower == "go back" || lower == "back") {
            IntentManager.executeActionSync(context, "GO_BACK")
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = "Going back.",
                actionTaken = "GO_BACK",
                isSystemAction = true
            )
        }

        if (lower.contains("notification")) {
            IntentManager.executeActionSync(context, "OPEN_NOTIFICATIONS")
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = "Opening notifications.",
                actionTaken = "OPEN_NOTIFICATIONS",
                isSystemAction = true
            )
        }

        if (lower.contains("recent app") || lower.contains("recents") || lower == "switch app") {
            IntentManager.executeActionSync(context, "RECENT_APPS")
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = "Opening recent apps.",
                actionTaken = "RECENT_APPS",
                isSystemAction = true
            )
        }

        // 4. Settings & System utilities
        if (lower == "open settings" || lower == "settings") {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            tryStartActivity(context, intent)
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = "Opening Settings.",
                actionTaken = "OPEN_SETTINGS"
            )
        }

        // 5. Flashlight / Torch
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val enable = !lower.contains("off") && !lower.contains("stop")
            val torchResult = toggleFlashlight(context, enable)
            val reply = if (torchResult) {
                if (enable) "Flashlight turned on." else "Flashlight turned off."
            } else {
                "Unable to control flashlight on this device."
            }
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = reply,
                actionTaken = "FLASHLIGHT"
            )
        }

        // 6. Volume controls
        if (lower.contains("volume") || lower == "mute") {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                when {
                    lower.contains("up") || lower.contains("increase") -> {
                        audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                        return OfflineExecutionResult(true, "Volume raised.", "VOLUME_UP")
                    }
                    lower.contains("down") || lower.contains("decrease") || lower.contains("lower") -> {
                        audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                        return OfflineExecutionResult(true, "Volume lowered.", "VOLUME_DOWN")
                    }
                    lower.contains("mute") -> {
                        audioManager.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                        return OfflineExecutionResult(true, "Muted.", "VOLUME_MUTE")
                    }
                }
            }
        }

        // 7. Alarms & Timers
        if (lower.startsWith("set alarm") || lower.startsWith("alarm for")) {
            val hourMatch = "(\\d{1,2})".toRegex().find(lower)
            val hour = hourMatch?.groupValues?.get(1)?.toIntOrNull()
            if (hour != null && hour in 0..23) {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, 0)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                tryStartActivity(context, intent)
                return OfflineExecutionResult(
                    handled = true,
                    spokenResponse = "Alarm set for $hour:00.",
                    actionTaken = "SET_ALARM"
                )
            }
        }

        // 8. App Launching ("open chrome", "launch youtube", "open camera")
        if (lower.startsWith("open ") || lower.startsWith("launch ")) {
            val appTarget = lower.replace("open ", "").replace("launch ", "").trim()

            // Check hardcoded map first
            val packageName = APP_MAP[appTarget]
            if (packageName != null) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    tryStartActivity(context, launchIntent)
                } else {
                    val directIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setPackage(packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    tryStartActivity(context, directIntent)
                }
                return OfflineExecutionResult(
                    handled = true,
                    spokenResponse = "Opening ${appTarget.replaceFirstChar { it.uppercase() }}.",
                    actionTaken = "OPEN_APP"
                )
            }

            // Fallback: search installed apps matching name
            val installed = findInstalledAppByName(context, appTarget)
            if (installed != null) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(installed)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    tryStartActivity(context, launchIntent)
                }
                return OfflineExecutionResult(
                    handled = true,
                    spokenResponse = "Opening $appTarget.",
                    actionTaken = "OPEN_APP"
                )
            }
        }

        // 9. Phone Calling
        if (lower.startsWith("call ") || lower.startsWith("dial ")) {
            val number = raw.replace("call", "", ignoreCase = true)
                .replace("dial", "", ignoreCase = true)
                .trim()
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStartActivity(context, intent)) {
                return OfflineExecutionResult(
                    handled = true,
                    spokenResponse = "Calling $number.",
                    actionTaken = "CALL"
                )
            }
        }

        // 10. General Offline Fallback Guidance
        val fallback = "I'm currently running in Offline Mode because there's no internet connection. I can still open your apps, set alarms, toggle settings, navigate your screen, or check your battery. Please connect to the internet for complex conversational answers!"
        return OfflineExecutionResult(
            handled = false,
            spokenResponse = fallback,
            actionTaken = "OFFLINE_FALLBACK"
        )
    }

    private fun toggleFlashlight(context: Context, enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
            return try {
                val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return false
                cameraManager.setTorchMode(cameraId, enabled)
                true
            } catch (e: Exception) {
                AssistantLogger.w(TAG, "Flashlight error: ${e.message}")
                false
            }
        }
        return false
    }

    private fun findInstalledAppByName(context: Context, appName: String): String? {
        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(0)
            packages.firstOrNull { appInfo ->
                val label = pm.getApplicationLabel(appInfo).toString().lowercase()
                label == appName || label.contains(appName)
            }?.packageName
        } catch (e: Exception) {
            null
        }
    }

    private fun tryStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AssistantLogger.w(TAG, "Failed to start activity: ${e.message}")
            false
        }
    }
}
