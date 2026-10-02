package com.example.action

import com.example.AssistantException
import com.example.ErrorCategory
import com.example.PlannedAction
import java.net.URI

object PlannedActionMapper {
    private val noPayloadActions = setOf(
        ActionName.OPEN_SETTINGS, ActionName.GO_HOME, ActionName.GO_BACK,
        ActionName.OPEN_NOTIFICATIONS, ActionName.RECENT_APPS,
        ActionName.OPEN_QUICK_SETTINGS, ActionName.SHOW_MEMORIES, ActionName.CHAT
    )
    private val videoRequest = Regex(
        """(?i)\bwatch\b|\b(?:a\s+|the\s+)?videos?\b(?!\s+games\b)|\bvideos?\s+(?:about|of|on)\b|\bsearch\s+(?:on\s+)?youtube\b|\byoutube\s+videos?\b|વીડિયો|વિડિઓ|वीडियो"""
    )

    fun map(plan: PlannedAction, id: String): Result<ActionRequest<out ActionParameters>> {
        fun invalid(message: String) = Result.failure<ActionRequest<out ActionParameters>>(
            AssistantException(ErrorCategory.ACTION_FAILED, message, canRetry = false)
        )

        val name = runCatching { ActionName.valueOf(plan.action.uppercase()) }.getOrElse {
            return invalid("Unsupported action.")
        }
        val payload = plan.payload
        if (name in noPayloadActions && payload != null) return invalid("This action does not accept a payload.")
        if (name !in noPayloadActions && payload.isNullOrBlank()) return invalid("This action requires a payload.")

        val params: ActionParameters = when (name) {
            ActionName.OPEN_APP -> {
                val packageName = payload!!
                if (!packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
                    return invalid("Invalid app package name.")
                }
                ActionParameters.OpenApp(packageName)
            }
            ActionName.SEARCH_WEB -> ActionParameters.SearchWeb(payload!!)
            ActionName.OPEN_URL -> {
                val rawUrl = payload!!
                val candidate = if (rawUrl.contains("://")) rawUrl else "https://$rawUrl"
                val uri = runCatching { URI(candidate) }.getOrNull()
                val scheme = uri?.scheme?.lowercase()
                if (uri == null || scheme == null || scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                    return invalid("Invalid web URL.")
                }
                ActionParameters.OpenUrl(rawUrl)
            }
            ActionName.PLAY_MUSIC -> {
                if (videoRequest.containsMatchIn(payload!!)) return invalid("Video requests must use PLAY_VIDEO.")
                ActionParameters.PlayMusic(payload)
            }
            ActionName.PLAY_VIDEO -> ActionParameters.PlayVideo(payload!!)
            ActionName.CALL -> ActionParameters.Call(payload!!)
            ActionName.SEND_SMS -> ActionParameters.SendSms(payload!!)
            ActionName.OPEN_SETTINGS -> ActionParameters.OpenSettings
            ActionName.NAVIGATE -> ActionParameters.Navigate(payload!!)
            ActionName.SET_ALARM -> payload!!.toIntOrNull()?.takeIf { it in 0..23 }
                ?.let(ActionParameters::SetAlarm) ?: return invalid("Invalid alarm hour.")
            ActionName.SET_TIMER -> payload!!.toIntOrNull()?.takeIf { it >= 0 }
                ?.let(ActionParameters::SetTimer) ?: return invalid("Invalid timer duration.")
            ActionName.GO_HOME -> ActionParameters.GoHome
            ActionName.GO_BACK -> ActionParameters.GoBack
            ActionName.OPEN_NOTIFICATIONS -> ActionParameters.OpenNotifications
            ActionName.RECENT_APPS -> ActionParameters.RecentApps
            ActionName.TOGGLE_WIFI -> validatedToggle(payload!!)
                ?.let(ActionParameters::ToggleWifi) ?: return invalid("Invalid Wi-Fi state.")
            ActionName.TOGGLE_BLUETOOTH -> validatedToggle(payload!!)
                ?.let(ActionParameters::ToggleBluetooth) ?: return invalid("Invalid Bluetooth state.")
            ActionName.SET_BRIGHTNESS -> payload!!.toIntOrNull()?.takeIf { it in 0..100 }
                ?.let(ActionParameters::SetBrightness) ?: return invalid("Invalid brightness percentage.")
            ActionName.OPEN_QUICK_SETTINGS -> ActionParameters.OpenQuickSettings
            ActionName.SEND_WHATSAPP -> ActionParameters.SendWhatsApp(payload!!)
            ActionName.CREATE_TASK -> ActionParameters.CreateTask(payload!!)
            ActionName.COMPLETE_TASK -> ActionParameters.CompleteTask(payload!!)
            ActionName.REMEMBER_PREFERENCE -> ActionParameters.RememberPreference(payload!!)
            ActionName.SHOW_MEMORIES -> ActionParameters.ShowMemories
            ActionName.FORGET_MEMORY -> ActionParameters.ForgetMemory(payload!!)
            ActionName.CHAT -> ActionParameters.Chat
        }
        return Result.success(ActionRequest(id, name, params))
    }

    private fun validatedToggle(value: String): String? =
        value.lowercase().takeIf { it in setOf("on", "off", "toggle", "open") }
}
