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
import com.example.contact.ContactsManager
import com.example.data.BatteryOptimizationManager
import com.example.data.UserMemoryManager
import com.example.sms.SmsActionManager
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

    /**
     * Normalizes natural language queries:
     * - Strips surrounding punctuation (. , ! ?)
     * - Removes conversational filler prefixes ("can you please", "hey mj", "could you")
     */
    fun normalizeCommand(query: String): String {
        var text = query.trim().lowercase()
        // Strip trailing and leading punctuation
        text = text.replace(Regex("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$"), "").trim()

        val prefixes = listOf(
            "hey mj please ", "hey mj can you ", "hey mj could you ", "hey mj ",
            "ok mj please ", "ok mj can you ", "ok mj ",
            "okay mj please ", "okay mj ",
            "mj please ", "mj can you ", "mj could you ", "mj ",
            "can you please ", "could you please ", "would you please ",
            "can you ", "could you ", "would you mind ", "please "
        )
        for (prefix in prefixes) {
            if (text.startsWith(prefix)) {
                text = text.removePrefix(prefix).trim()
                break
            }
        }
        return text
    }

    suspend fun handleOfflineCommand(context: Context, query: String): OfflineExecutionResult {
        val raw = query.trim()
        val normalized = normalizeCommand(raw)
        val lower = normalized.lowercase()
        AssistantLogger.i(TAG, "Processing offline query: raw='$raw', normalized='$normalized'")

        // 0. Direct Music Command ("play believer", "play shape of you on spotify", "listen to believer")
        val musicCommand = com.example.music.MusicActionManager.parseMusicCommand(normalized)
            ?: com.example.music.MusicActionManager.parseMusicCommand(raw)
        if (musicCommand != null) {
            val res = com.example.music.MusicActionManager.executeMusicCommand(context, musicCommand)
            return when (res) {
                is com.example.music.MusicExecutionResult.Success -> {
                    OfflineExecutionResult(
                        handled = true,
                        spokenResponse = res.spokenResponse,
                        actionTaken = res.actionTaken
                    )
                }
                is com.example.music.MusicExecutionResult.NeedsSongPrompt -> {
                    OfflineExecutionResult(
                        handled = true,
                        spokenResponse = res.spokenResponse,
                        actionTaken = "MUSIC_PROMPT"
                    )
                }
                is com.example.music.MusicExecutionResult.Error -> {
                    OfflineExecutionResult(
                        handled = true,
                        spokenResponse = res.spokenResponse,
                        actionTaken = "MUSIC_ERROR"
                    )
                }
            }
        }

        // 1. WhatsApp Voice Command ("Send a WhatsApp to [contact]")
        val whatsAppMatch = WhatsAppManager.parseWhatsAppVoiceCommand(normalized)
            ?: WhatsAppManager.parseWhatsAppVoiceCommand(raw)
        if (whatsAppMatch != null) {
            val res = WhatsAppManager.sendWhatsApp(context, whatsAppMatch.first, whatsAppMatch.second)
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = res.spokenMessage,
                actionTaken = "SEND_WHATSAPP"
            )
        }

        // 2. SMS / Text Messaging ("Text [contact] [message]")
        val smsMatch = SmsActionManager.parseSmsCommand(normalized)
            ?: SmsActionManager.parseSmsCommand(raw)
        if (smsMatch != null) {
            val res = SmsActionManager.sendSms(context, smsMatch.first, smsMatch.second)
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = res.spokenMessage,
                actionTaken = "SEND_SMS"
            )
        }

        // 3. Tasks & Reminders via Room Database & WorkManager
        val taskResult = com.example.data.task.TaskManager.handleVoiceQuery(normalized)
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

        // 4. Device Controls: Wi-Fi
        if (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("internet connection")) {
            val turnOn = when {
                lower.contains("on") || lower.contains("enable") || lower.contains("connect") -> true
                lower.contains("off") || lower.contains("disable") || lower.contains("disconnect") -> false
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

        // 5. Device Controls: Bluetooth
        if (lower.contains("bluetooth")) {
            val turnOn = when {
                lower.contains("on") || lower.contains("enable") || lower.contains("connect") -> true
                lower.contains("off") || lower.contains("disable") || lower.contains("disconnect") -> false
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

        // 6. Device Controls: Screen Brightness
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

        // 7. Quick Settings
        if (lower.contains("quick settings") || lower == "open quick settings") {
            val res = com.example.device.DeviceControlManager.openQuickSettings()
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = res.spokenMessage,
                actionTaken = "OPEN_QUICK_SETTINGS",
                isSystemAction = true
            )
        }

        // 8. Battery inquiries
        if (lower.contains("battery") || lower == "power level") {
            val status = BatteryOptimizationManager.getBatteryStatusSummary()
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = status,
                actionTaken = "BATTERY_STATUS"
            )
        }

        // 9. User Memories
        if (lower.contains("what do you remember") || lower.contains("what are my memories") || lower.contains("my preferences") || lower == "show memories") {
            val summary = UserMemoryManager.getMemoriesSummaryText()
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = summary,
                actionTaken = "SHOW_MEMORIES"
            )
        }

        // 10. Time and Date inquiries
        if (lower.contains("what time") || lower == "time" || lower == "current time" || lower.contains("tell me the time")) {
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = "The current time is $time.",
                actionTaken = "GET_TIME"
            )
        }

        if (lower.contains("what date") || lower.contains("today's date") || lower == "date" || lower.contains("what is today")) {
            val date = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = "Today is $date.",
                actionTaken = "GET_DATE"
            )
        }

        // 11. Weather inquiries (Offline explanation)
        if (lower.contains("weather") || lower.contains("forecast") || lower.contains("temperature outside")) {
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = "Live weather forecasts require an internet connection. Please connect to Wi-Fi or mobile data, or ask me again once online.",
                actionTaken = "WEATHER_OFFLINE"
            )
        }

        // 12. System Navigation
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

        // 13. Settings & System utilities
        if (lower == "open settings" || lower == "settings" || lower == "launch settings") {
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

        // 14. Flashlight / Torch
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val enable = !lower.contains("off") && !lower.contains("stop") && !lower.contains("disable")
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

        // 15. Volume controls
        if (lower.contains("volume") || lower == "mute") {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                when {
                    lower.contains("up") || lower.contains("increase") || lower.contains("raise") -> {
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

        // 16. Alarms & Timers
        if (lower.startsWith("set alarm") || lower.startsWith("alarm for") || lower.startsWith("set an alarm")) {
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

        if (lower.startsWith("set timer") || lower.startsWith("timer for")) {
            val minuteMatch = "(\\d{1,3})\\s*(?:min|minute)".toRegex().find(lower)
            val minutes = minuteMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            tryStartActivity(context, intent)
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = "Timer set for $minutes minutes.",
                actionTaken = "SET_TIMER"
            )
        }

        // 17. App Launching ("open chrome", "launch youtube", "start spotify", "open camera")
        val appLaunchPrefixes = listOf("open up ", "open ", "launch ", "start ", "run ")
        val matchedPrefix = appLaunchPrefixes.firstOrNull { lower.startsWith(it) }
        if (matchedPrefix != null) {
            val appTarget = lower.removePrefix(matchedPrefix).removePrefix("the ").trim()

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

            // If app was explicitly requested but not found
            return OfflineExecutionResult(
                handled = true,
                spokenResponse = "I couldn't find an app named $appTarget on your device.",
                actionTaken = "APP_NOT_FOUND"
            )
        }

        // 18. Phone Calling
        val callTarget = com.example.contact.CallActionManager.parseCallCommand(normalized)
        if (callTarget != null) {
            if (callTarget.isBlank()) {
                return OfflineExecutionResult(
                    handled = true,
                    spokenResponse = "Who would you like me to call?",
                    actionTaken = "CALL_PROMPT"
                )
            }
            when (val outcome = ContactsManager.lookupContact(context, callTarget)) {
                is com.example.contact.ContactLookupOutcome.SingleMatch -> {
                    com.example.contact.CallActionManager.executeCall(context, outcome.entry.displayName, outcome.entry.phoneNumber)
                    return OfflineExecutionResult(
                        handled = true,
                        spokenResponse = "Calling ${outcome.entry.displayName}.",
                        actionTaken = "CALL"
                    )
                }
                is com.example.contact.ContactLookupOutcome.DirectNumber -> {
                    com.example.contact.CallActionManager.executeCall(context, outcome.phoneNumber, outcome.phoneNumber)
                    return OfflineExecutionResult(
                        handled = true,
                        spokenResponse = "Calling ${outcome.phoneNumber}.",
                        actionTaken = "CALL"
                    )
                }
                is com.example.contact.ContactLookupOutcome.ContactHasNoNumber -> {
                    return OfflineExecutionResult(
                        handled = true,
                        spokenResponse = "That contact doesn't have a phone number.",
                        actionTaken = "CALL_NO_NUMBER"
                    )
                }
                is com.example.contact.ContactLookupOutcome.NotFound -> {
                    return OfflineExecutionResult(
                        handled = true,
                        spokenResponse = "I couldn't find that contact.",
                        actionTaken = "CALL_NOT_FOUND"
                    )
                }
                is com.example.contact.ContactLookupOutcome.MultipleContacts -> {
                    return OfflineExecutionResult(
                        handled = true,
                        spokenResponse = "I found multiple contacts named $callTarget. Which one should I call?",
                        actionTaken = "CALL_AMBIGUOUS"
                    )
                }
                is com.example.contact.ContactLookupOutcome.MultipleNumbersForContact -> {
                    val labels = outcome.numbers.map { it.typeLabel.lowercase() }.distinct().joinToString(", or ")
                    return OfflineExecutionResult(
                        handled = true,
                        spokenResponse = "Which number should I call: $labels?",
                        actionTaken = "CALL_MULTIPLE_NUMBERS"
                    )
                }
                is com.example.contact.ContactLookupOutcome.PermissionRequired -> {
                    return OfflineExecutionResult(
                        handled = true,
                        spokenResponse = outcome.message,
                        actionTaken = "CALL_PERMISSION_REQUIRED"
                    )
                }
                is com.example.contact.ContactLookupOutcome.Error -> {
                    return OfflineExecutionResult(
                        handled = true,
                        spokenResponse = outcome.message,
                        actionTaken = "CALL_ERROR"
                    )
                }
            }
        }

        // 19. Web Search ("search for [query]", "google [query]")
        val searchPrefixes = listOf("search for ", "search the web for ", "search web for ", "search ", "google ")
        val matchedSearch = searchPrefixes.firstOrNull { lower.startsWith(it) }
        if (matchedSearch != null) {
            val searchQuery = normalized.removePrefix(matchedSearch).trim()
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, searchQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStartActivity(context, intent)) {
                return OfflineExecutionResult(
                    handled = true,
                    spokenResponse = "Searching the web for $searchQuery.",
                    actionTaken = "SEARCH_WEB"
                )
            }
        }

        // 20. General Offline Fallback Guidance
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
