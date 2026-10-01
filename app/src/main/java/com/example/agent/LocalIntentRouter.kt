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
        fun h(name: ActionName, p: ActionParameters) =
            LocalRoute.Handled(ActionRequest("LOCAL_" + UUID.randomUUID(), name, p), normalized.language.code)

        if (q == "home" || q == "go home" || q.contains("go home")) return h(ActionName.GO_HOME, ActionParameters.GoHome)
        if (q == "back" || q.contains("go back")) return h(ActionName.GO_BACK, ActionParameters.GoBack)
        if (q.contains("recent")) return h(ActionName.RECENT_APPS, ActionParameters.RecentApps)
        if (q.contains("quick settings")) return h(ActionName.OPEN_QUICK_SETTINGS, ActionParameters.OpenQuickSettings)
        if (q.contains("wifi") || q.contains("wi-fi")) return h(ActionName.TOGGLE_WIFI, ActionParameters.ToggleWifi("open"))
        if (q.contains("bluetooth")) return h(ActionName.TOGGLE_BLUETOOTH, ActionParameters.ToggleBluetooth("open"))
        if (q.contains("settings") || q.contains("setting")) return h(ActionName.OPEN_SETTINGS, ActionParameters.OpenSettings)

        // App-first and verb-first forms share the same route after language normalization.
        val appPatterns = listOf(
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "spotify" to "com.spotify.music"
        )
        val openVerb = Regex("""\b(open|launch)\b""")
        for ((app, packageName) in appPatterns) {
            val appWord = Regex("""\b${Regex.escape(app)}\b""")
            if (appWord.containsMatchIn(q) && openVerb.containsMatchIn(q)) {
                return h(ActionName.OPEN_APP, ActionParameters.OpenApp(packageName))
            }
        }

        Regex("""\b(?:set|start|create)\s+(?:a\s+)?timer\s+(?:for\s+)?(\d+)\s*(?:minutes?|min)\b.*""").matchEntire(q)?.let {
            return h(ActionName.SET_TIMER, ActionParameters.SetTimer(it.groupValues[1].toInt()))
        }

        // Alarm time may occur before or after the word "alarm" in natural multilingual word order.
        if (Regex("""\balarm\b""").containsMatchIn(q) &&
            Regex("""\b(set|create|start|kar|karo|laga|set)\b""").containsMatchIn(q)
        ) {
            val timeMatch = Regex("""(?:^|\s)(\d{1,2})(?:\s*(am|pm))?(?=\s|$)""").find(q)
            if (timeMatch != null) {
                var hour = timeMatch.groupValues[1].toIntOrNull()
                if (hour != null && hour in 0..23) {
                    when (timeMatch.groupValues[2]) {
                        "am" -> if (hour == 12) hour = 0
                        "pm" -> if (hour in 1..11) hour += 12
                    }
                    return h(ActionName.SET_ALARM, ActionParameters.SetAlarm(hour))
                }
            }
        }

        Regex("""\btimer\b.*?(\d+)\s*(?:minutes?|min)?\b.*""").matchEntire(q)?.let {
            return h(ActionName.SET_TIMER, ActionParameters.SetTimer(it.groupValues[1].toInt()))
        }
        return LocalRoute.FallbackToAi
    }
}
