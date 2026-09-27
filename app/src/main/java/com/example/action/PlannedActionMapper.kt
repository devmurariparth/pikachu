package com.example.action

import com.example.AssistantException
import com.example.ErrorCategory
import com.example.PlannedAction

object PlannedActionMapper {
    fun map(plan: PlannedAction, id: String): Result<ActionRequest<out ActionParameters>> {
        fun invalid(message: String) = Result.failure<ActionRequest<out ActionParameters>>(
            AssistantException(ErrorCategory.ACTION_FAILED, message, canRetry = false)
        )
        val name = runCatching { ActionName.valueOf(plan.action.uppercase()) }.getOrElse {
            return invalid("Unsupported action.")
        }
        val params: ActionParameters = when (name) {
            ActionName.OPEN_APP -> ActionParameters.OpenApp(plan.payload ?: return invalid("Missing app package name."))
            ActionName.SEARCH_WEB -> ActionParameters.SearchWeb(plan.payload ?: return invalid("Missing search query."))
            ActionName.OPEN_URL -> ActionParameters.OpenUrl(plan.payload ?: return invalid("Missing URL."))
            ActionName.PLAY_MUSIC -> ActionParameters.PlayMusic(plan.payload ?: return invalid("Missing music query."))
            ActionName.CALL -> ActionParameters.Call(plan.payload ?: return invalid("Missing call target."))
            ActionName.SEND_SMS -> ActionParameters.SendSms(plan.payload ?: return invalid("Missing SMS payload."))
            ActionName.OPEN_SETTINGS -> ActionParameters.OpenSettings
            ActionName.NAVIGATE -> ActionParameters.Navigate(plan.payload ?: return invalid("Missing destination."))
            ActionName.SET_ALARM -> plan.payload?.toIntOrNull()?.takeIf { it in 0..23 }?.let(ActionParameters::SetAlarm) ?: return invalid("Invalid alarm hour.")
            ActionName.SET_TIMER -> plan.payload?.toIntOrNull()?.takeIf { it >= 0 }?.let(ActionParameters::SetTimer) ?: return invalid("Invalid timer duration.")
            ActionName.GO_HOME -> ActionParameters.GoHome
            ActionName.GO_BACK -> ActionParameters.GoBack
            ActionName.OPEN_NOTIFICATIONS -> ActionParameters.OpenNotifications
            ActionName.RECENT_APPS -> ActionParameters.RecentApps
            ActionName.TOGGLE_WIFI -> ActionParameters.ToggleWifi(plan.payload ?: return invalid("Missing Wi-Fi state."))
            ActionName.TOGGLE_BLUETOOTH -> ActionParameters.ToggleBluetooth(plan.payload ?: return invalid("Missing Bluetooth state."))
            ActionName.SET_BRIGHTNESS -> plan.payload?.toIntOrNull()?.takeIf { it in 0..100 }?.let(ActionParameters::SetBrightness) ?: return invalid("Invalid brightness percentage.")
            ActionName.OPEN_QUICK_SETTINGS -> ActionParameters.OpenQuickSettings
            ActionName.SEND_WHATSAPP -> ActionParameters.SendWhatsApp(plan.payload ?: return invalid("Missing WhatsApp payload."))
            ActionName.CREATE_TASK -> ActionParameters.CreateTask(plan.payload ?: return invalid("Missing task."))
            ActionName.COMPLETE_TASK -> ActionParameters.CompleteTask(plan.payload ?: return invalid("Missing task title."))
            ActionName.REMEMBER_PREFERENCE -> ActionParameters.RememberPreference(plan.payload ?: return invalid("Missing preference."))
            ActionName.SHOW_MEMORIES -> ActionParameters.ShowMemories
            ActionName.FORGET_MEMORY -> ActionParameters.ForgetMemory(plan.payload ?: return invalid("Missing memory."))
            ActionName.CHAT -> ActionParameters.Chat
        }
        return Result.success(ActionRequest(id, name, params))
    }
}
