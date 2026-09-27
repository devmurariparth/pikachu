package com.example.agent

import com.example.action.ActionName
import com.example.action.ActionParameters
import com.example.action.ActionRequest
import com.example.voice.LanguageNormalizer
import java.util.UUID

sealed interface LocalRoute {
    data class Handled(val request: ActionRequest<out ActionParameters>, val language: String) : LocalRoute
    data object FallbackToAi : LocalRoute
}

object LocalIntentRouter {
    fun route(text: String): LocalRoute {
        val normalized = LanguageNormalizer.normalize(text)
        val q = normalized.normalized.lowercase()
        fun h(name: ActionName, p: ActionParameters) = LocalRoute.Handled(ActionRequest("LOCAL_" + UUID.randomUUID(), name, p), normalized.language.code)

        if (q == "home" || q == "go home" || q.contains("go home")) return h(ActionName.GO_HOME, ActionParameters.GoHome)
        if (q == "back" || q.contains("go back")) return h(ActionName.GO_BACK, ActionParameters.GoBack)
        if (q.contains("recent")) return h(ActionName.RECENT_APPS, ActionParameters.RecentApps)
        if (q.contains("quick settings")) return h(ActionName.OPEN_QUICK_SETTINGS, ActionParameters.OpenQuickSettings)
        if (q.contains("wifi") || q.contains("wi-fi")) return h(ActionName.TOGGLE_WIFI, ActionParameters.ToggleWifi("open"))
        if (q.contains("bluetooth")) return h(ActionName.TOGGLE_BLUETOOTH, ActionParameters.ToggleBluetooth("open"))
        if (q.contains("settings") || q.contains("setting")) return h(ActionName.OPEN_SETTINGS, ActionParameters.OpenSettings)
        if (q.matches(Regex(".*\b(open|launch)\s+(youtube|yt)\b.*")) || q.contains("youtube kholo")) return h(ActionName.OPEN_APP, ActionParameters.OpenApp("com.google.android.youtube"))
        if (q.matches(Regex(".*\b(open|launch)\s+(chrome)\b.*")) || q.contains("chrome kholo")) return h(ActionName.OPEN_APP, ActionParameters.OpenApp("com.android.chrome"))
        if (q.matches(Regex(".*\b(open|launch)\s+(spotify)\b.*")) || q.contains("spotify kholo")) return h(ActionName.OPEN_APP, ActionParameters.OpenApp("com.spotify.music"))
        Regex(".*\b(?:set|start|create)\s+(?:a\s+)?timer\s+(?:for\s+)?(\d+)\s*(?:minutes?|min).*").matchEntire(q)?.let {
            return h(ActionName.SET_TIMER, ActionParameters.SetTimer(it.groupValues[1].toInt()))
        }
        Regex(".*\b(?:set|create)\s+(?:an\s+)?alarm\s+(?:for\s+)?(\d{1,2}).*").matchEntire(q)?.let {
            return h(ActionName.SET_ALARM, ActionParameters.SetAlarm(it.groupValues[1].toInt().coerceIn(0,23)))
        }
        Regex(".*alarm.*?(\\d{1,2})(?:\\s*(?:am|pm))?.*").matchEntire(q)?.let {
            return h(ActionName.SET_ALARM, ActionParameters.SetAlarm(it.groupValues[1].toInt().coerceIn(0,23)))
        }
        Regex(".*timer.*?(\\d+)\\s*(?:minutes?|min)?.*").matchEntire(q)?.let {
            return h(ActionName.SET_TIMER, ActionParameters.SetTimer(it.groupValues[1].toInt()))
        }
        return LocalRoute.FallbackToAi
    }
}
