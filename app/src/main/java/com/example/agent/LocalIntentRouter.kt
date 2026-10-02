package com.example.agent

import com.example.action.ActionName
import com.example.action.ActionParameters
import com.example.action.ActionRequest
import com.example.WhatsAppManager
import com.example.music.MusicActionManager
import com.example.voice.LanguageNormalizer
import java.util.UUID

sealed interface LocalRoute {
    data class Handled(val request: ActionRequest<out ActionParameters>, val language: String) : LocalRoute
    data object FallbackToAi : LocalRoute
    private fun extractYouTubeVideoQuery(q: String): String? {
        val lower = q.lowercase().trim()
        val musicCue = lower.contains("song") ||
            lower.contains("music") ||
            lower.contains("spotify") ||
            lower.contains("youtube music")
        val explicitVideo = lower.contains("video") ||
            lower.startsWith("watch ") ||
            lower.contains("search youtube") ||
            lower.contains("search on youtube") ||
            (lower.contains("youtube") && lower.contains("play") && !musicCue)
        if (!explicitVideo) return null

        var query = lower
        val prefixes = listOf(
            "open youtube and search for ",
            "open youtube and search ",
            "search on youtube for ",
            "search youtube for ",
            "search on youtube ",
            "search youtube ",
            "find on youtube ",
            "find youtube ",
            "open youtube and play ",
            "watch the video ",
            "watch video ",
            "play the video ",
            "play video ",
            "watch ",
            "play "
        )
        prefixes.firstOrNull { query.startsWith(it) }?.let { query = query.removePrefix(it) }
        query = query
            .replace(Regex("""\b(?:on|in)\s+(?:the\s+)?youtube(?:\s+music)?\b"""), " ")
            .replace(Regex("""\b(?:youtube|yt)\b"""), " ")
            .replace(Regex("""\bvideo\b"""), " ")
            .replace(Regex("""\b(?:please|now|the|a)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return query.takeIf { it.isNotBlank() }
    }

}

object LocalIntentRouter {
    fun route(text: String): LocalRoute {
        val normalized = LanguageNormalizer.normalize(text)
        val q = normalized.normalized.lowercase()
        fun h(name: ActionName, p: ActionParameters) =
            LocalRoute.Handled(ActionRequest("LOCAL_" + UUID.randomUUID(), name, p), normalized.language.code)

        if (q == "home" || q.contains("go home") || q.contains("take me home") || q.contains("home screen")) {
            return h(ActionName.GO_HOME, ActionParameters.GoHome)
        }
        if (q == "back" || q.contains("go back")) return h(ActionName.GO_BACK, ActionParameters.GoBack)
        if (q.contains("notification")) return h(ActionName.OPEN_NOTIFICATIONS, ActionParameters.OpenNotifications)
        if (q.contains("recent") || q == "switch app") return h(ActionName.RECENT_APPS, ActionParameters.RecentApps)
        if (q.contains("quick settings")) return h(ActionName.OPEN_QUICK_SETTINGS, ActionParameters.OpenQuickSettings)
        if (q.contains("wifi") || q.contains("wi-fi")) {
            val state = when {
                q.contains("off") || q.contains("disable") -> "off"
                q.contains("on") || q.contains("enable") -> "on"
                else -> "open"
            }
            return h(ActionName.TOGGLE_WIFI, ActionParameters.ToggleWifi(state))
        }
        if (q.contains("bluetooth")) {
            val state = when {
                q.contains("off") || q.contains("disable") -> "off"
                q.contains("on") || q.contains("enable") -> "on"
                else -> "open"
            }
            return h(ActionName.TOGGLE_BLUETOOTH, ActionParameters.ToggleBluetooth(state))
        }
        if (q.contains("brightness")) {
            val percent = Regex("\\b\\d{1,3}\\b").find(q)?.value?.toIntOrNull()?.coerceIn(0, 100) ?: 80
            return h(ActionName.SET_BRIGHTNESS, ActionParameters.SetBrightness(percent))
        }
        if (q.contains("settings") || q.contains("setting")) return h(ActionName.OPEN_SETTINGS, ActionParameters.OpenSettings)

        // Route explicit YouTube/video requests locally so they never fall through to Spotify AUTO routing.
        extractYouTubeVideoQuery(q)?.let { query ->
            return h(ActionName.PLAY_VIDEO, ActionParameters.PlayVideo(query))
        }

        // Common WhatsApp commands should work without the cloud planner.
        WhatsAppManager.parseWhatsAppVoiceCommand(q)?.let { (contact, message) ->
            val payload = if (message.isNullOrBlank()) contact else "$contact|$message"
            return h(ActionName.SEND_WHATSAPP, ActionParameters.SendWhatsApp(payload))
        }

        // Common song playback is also a local fast path; preserve an explicitly requested platform.
        MusicActionManager.parseMusicCommand(q)?.let { command ->
            val platformSuffix = when (command.platform) {
                com.example.music.MusicPlatform.SPOTIFY -> " on spotify"
                com.example.music.MusicPlatform.YOUTUBE_MUSIC -> " on youtube music"
                com.example.music.MusicPlatform.YOUTUBE -> " on youtube"
                else -> ""
            }
            val query = buildString {
                append(command.song.trim())
                command.artist?.takeIf { it.isNotBlank() }?.let { append(" by ").append(it.trim()) }
                append(platformSuffix)
            }.trim()
            if (query.isNotBlank()) {
                return h(ActionName.PLAY_MUSIC, ActionParameters.PlayMusic(query))
            }
        }

        // App-first and verb-first forms share the same route after language normalization.
        val appPatterns = listOf(
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "spotify" to "com.spotify.music",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "whatsapp" to "com.whatsapp",
            "messages" to "com.google.android.apps.messaging",
            "camera" to "com.google.android.GoogleCamera",
            "photos" to "com.google.android.apps.photos"
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
