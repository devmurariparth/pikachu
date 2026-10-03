package com.example.agent

import com.example.WhatsAppManager
import com.example.action.ActionName
import com.example.action.ActionParameters
import com.example.action.ActionRequest
import com.example.action.ToolRegistry
import com.example.voice.LanguageNormalizer
import com.example.voice.NormalizedCommand
import java.net.URI
import java.util.UUID

enum class RouteConfidence { HIGH, MEDIUM, LOW }

enum class RouteReason {
    DETERMINISTIC_MATCH,
    NATURAL_LANGUAGE_MATCH,
    AMBIGUOUS_REFERENCE,
    MISSING_PARAMETER,
    INVALID_PARAMETER,
    UNSUPPORTED_TOOL,
    REASONING_REQUIRED,
    NO_MATCH
}

sealed interface RouteDecision {
    val language: String
    val confidence: RouteConfidence
    val reason: RouteReason
    val ambiguous: Boolean

    data class DirectTool(
        val request: ActionRequest<out ActionParameters>,
        override val language: String,
        override val confidence: RouteConfidence,
        override val reason: RouteReason = RouteReason.DETERMINISTIC_MATCH,
        override val ambiguous: Boolean = false
    ) : RouteDecision

    data class PlannerRequired(
        override val language: String,
        override val confidence: RouteConfidence = RouteConfidence.LOW,
        override val reason: RouteReason = RouteReason.REASONING_REQUIRED,
        override val ambiguous: Boolean = false
    ) : RouteDecision

    data class ClarificationRequired(
        val prompt: String,
        val action: ActionName?,
        val partialParameters: Map<String, String> = emptyMap(),
        override val language: String,
        override val confidence: RouteConfidence = RouteConfidence.LOW,
        override val reason: RouteReason,
        override val ambiguous: Boolean = true
    ) : RouteDecision

    data class Unsupported(
        val action: ActionName,
        override val language: String,
        override val confidence: RouteConfidence = RouteConfidence.HIGH,
        override val reason: RouteReason = RouteReason.UNSUPPORTED_TOOL,
        override val ambiguous: Boolean = false
    ) : RouteDecision

    data class NoMatch(
        override val language: String,
        override val confidence: RouteConfidence = RouteConfidence.LOW,
        override val reason: RouteReason = RouteReason.NO_MATCH,
        override val ambiguous: Boolean = false
    ) : RouteDecision
}

/** Shared intent vocabulary for routing; script normalization stays in LanguageNormalizer. */
object RoutingPhraseCatalog {
    val open = setOf("open", "launch", "ખોલ", "ખોલો", "खोल", "खोलो")
    val search = setOf("search", "find", "શોધ", "શોધો", "ढूंढो", "खोजो")
    val play = setOf("play", "listen", "વગાડ", "વગાડો", "બજાવો", "बजाओ", "चलाओ")
    val call = setOf("call", "phone", "કોલ", "ફોન કર", "फोन", "कॉल करो")
    val message = setOf("message", "msg", "સંદેશ", "મેસેજ", "मेसेज", "मैसेज")
    val alarm = setOf("alarm", "એલાર્મ", "अलार्म")
    val timer = setOf("timer", "ટાઈમર", "टाइमर")
    val video = setOf("video", "વીડિયો", "વિડિયો", "वीडियो")
    val music = setOf("music", "song", "ગીત", "गाना", "गाने")

    internal val appPackages = linkedMapOf(
        "youtube music" to "com.google.android.apps.youtube.music",
        "youtube" to "com.google.android.youtube",
        "yt" to "com.google.android.youtube",
        "spotify" to "com.spotify.music",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "google maps" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps",
        "whatsapp" to WhatsAppManager.WHATSAPP_PACKAGE,
        "messages" to "com.google.android.apps.messaging",
        "camera" to "com.google.android.GoogleCamera",
        "photos" to "com.google.android.apps.photos"
    )
}

/** Pure routing selection. It never executes tools or accepts model-generated action text. */
class SmartToolRouter(
    private val registry: ToolRegistry = ToolRegistry.existingCapabilities()
) {
    fun route(command: String, safeFollowUpContext: String? = null): RouteDecision {
        return routeNormalized(LanguageNormalizer.normalize(command), safeFollowUpContext)
    }

    internal fun routeNormalized(normalized: NormalizedCommand, safeFollowUpContext: String? = null): RouteDecision {
        val command = normalized.original
        val q = normalized.normalized.lowercase()
        if (q.isBlank()) return RouteDecision.NoMatch(normalized.language.code)

        if (!safeFollowUpContext.isNullOrBlank() && FOLLOW_UP_REFERENCE.containsMatchIn(q) &&
            !containsAny(q, RoutingPhraseCatalog.call + RoutingPhraseCatalog.message) && !q.contains("whatsapp")) {
            return RouteDecision.PlannerRequired(normalized.language.code, reason = RouteReason.REASONING_REQUIRED)
        }
        if (hasReasoningCue(q)) return RouteDecision.PlannerRequired(normalized.language.code)

        if (AMBIGUOUS_REFERENCE.containsMatchIn(q)) {
            val action = when {
                q.contains("whatsapp") -> ActionName.SEND_WHATSAPP
                containsAny(q, RoutingPhraseCatalog.music) -> ActionName.PLAY_MUSIC
                containsAny(q, RoutingPhraseCatalog.video) -> ActionName.PLAY_VIDEO
                containsAny(q, RoutingPhraseCatalog.open) -> ActionName.OPEN_APP
                containsAny(q, RoutingPhraseCatalog.call) -> ActionName.CALL
                else -> null
            }
            val prompt = when {
                containsAny(q, RoutingPhraseCatalog.play) -> "What would you like me to play?"
                action == ActionName.CALL -> "Who should I call?"
                action == ActionName.SEND_WHATSAPP -> "Who should receive the WhatsApp message?"
                containsAny(q, RoutingPhraseCatalog.message) && !q.contains("whatsapp") -> "Who should receive the message, and should I use SMS or WhatsApp?"
                else -> "Which item or person do you mean?"
            }
            return clarify(prompt, action, normalized.language.code, RouteReason.AMBIGUOUS_REFERENCE)
        }

        val direct = routeNaturalMediaAndSensitive(command, normalized, q)
        if (direct != null) return if (direct is RouteDecision.DirectTool) validate(direct) else direct

        when (val existing = LocalIntentRouter.routeNormalized(normalized)) {
            is LocalRoute.Handled -> return validate(RouteDecision.DirectTool(
                existing.request,
                existing.language,
                RouteConfidence.HIGH
            ))
            LocalRoute.FallbackToAi -> Unit
        }

        if (hasReasoningCue(q)) return RouteDecision.PlannerRequired(normalized.language.code)
        // Safe prior context is a planner hint only; it never supplies executable parameters.
        if (!safeFollowUpContext.isNullOrBlank() && FOLLOW_UP_REFERENCE.containsMatchIn(q)) {
            return RouteDecision.PlannerRequired(normalized.language.code, reason = RouteReason.REASONING_REQUIRED)
        }
        if (containsAny(q, RoutingPhraseCatalog.video + RoutingPhraseCatalog.music + RoutingPhraseCatalog.alarm + RoutingPhraseCatalog.timer)) {
            val action = when {
                containsAny(q, RoutingPhraseCatalog.video) -> ActionName.PLAY_VIDEO
                containsAny(q, RoutingPhraseCatalog.alarm) -> ActionName.SET_ALARM
                containsAny(q, RoutingPhraseCatalog.timer) -> ActionName.SET_TIMER
                else -> ActionName.PLAY_MUSIC
            }
            return clarify(parameterPrompt(action, normalized.language.code), action, normalized.language.code, RouteReason.MISSING_PARAMETER)
        }
        return RouteDecision.NoMatch(normalized.language.code)
    }

    private fun routeNaturalMediaAndSensitive(
        original: String,
        normalized: NormalizedCommand,
        q: String
    ): RouteDecision? {
        val language = normalized.language.code
        val hasYoutube = q.contains("youtube") || q.contains(" yt ")
        val hasPlayIntent = containsAny(q, RoutingPhraseCatalog.play)
        val hasMusicNoun = containsAny(q, RoutingPhraseCatalog.music)
        val hasVideoIntent = containsAny(q, RoutingPhraseCatalog.video)
        val hasSearch = containsAny(q, RoutingPhraseCatalog.search)

        if (hasSearch && hasVideoIntent && !hasYoutube) {
            if (containsAny(q, setOf("find")) && !q.contains("web") && !q.contains("google")) {
                val query = preserveOriginalCase(cleanQuery(q, setOf("find", "a", "the", "video", "videos")), original)
                return if (query.isBlank()) clarify("Which video should I find?", ActionName.PLAY_VIDEO, language, RouteReason.MISSING_PARAMETER)
                else candidate(ActionName.PLAY_VIDEO, ActionParameters.PlayVideo(query), language, RouteConfidence.MEDIUM, RouteReason.NATURAL_LANGUAGE_MATCH)
            }
            return clarify("Should I search the web for information or find a video?", null, language, RouteReason.AMBIGUOUS_REFERENCE)
        }

        if (hasYoutube && hasSearch && !hasMusicNoun) {
            val query = preserveOriginalCase(cleanQuery(q, setOf("youtube", "yt", "on", "ma", "search", "find", "for", "video", "videos", "kar", "karo", "please")), original)
            return if (query.isBlank()) clarify("What should I search for on YouTube?", ActionName.PLAY_VIDEO, language, RouteReason.MISSING_PARAMETER)
            else candidate(ActionName.PLAY_VIDEO, ActionParameters.PlayVideo(query), language)
        }

        // Distinguish a generic web search from a search constrained to YouTube.
        if (hasYoutube && hasVideoIntent && (hasSearch || hasPlayIntent)) {
            val query = preserveOriginalCase(cleanQuery(q, setOf("youtube", "yt", "on", "ma", "search", "find", "for", "video", "videos", "watch", "play", "kar", "karo", "please")), original)
            return if (query.isBlank()) clarify("What video should I find?", ActionName.PLAY_VIDEO, language, RouteReason.MISSING_PARAMETER)
            else candidate(ActionName.PLAY_VIDEO, ActionParameters.PlayVideo(query), language)
        }

        if ((hasPlayIntent || (hasMusicNoun && !hasSearch)) && !hasVideoIntent) {
            val query = preserveOriginalCase(cleanQuery(q, setOf("play", "listen", "song", "songs", "music", "on", "ma", "nu", "no", "ne", "par", "vagado", "vagad", "vagadvo", "bajao", "please", "spotify", "youtube", "youtube music")), original)
            return if (query.isBlank()) clarify("Which song or artist should I play?", ActionName.PLAY_MUSIC, language, RouteReason.MISSING_PARAMETER)
            else candidate(ActionName.PLAY_MUSIC, ActionParameters.PlayMusic(withPlatform(query, q)), language, RouteConfidence.MEDIUM, RouteReason.NATURAL_LANGUAGE_MATCH)
        }

        if (hasMusicNoun && hasSearch && !hasPlayIntent && !hasYoutube) {
            if (q.contains("web") || q.contains("google")) return null
            if (containsAny(q, setOf("find"))) {
                val query = preserveOriginalCase(cleanQuery(q, setOf("find", "a", "the", "song", "music")), original)
                if (query.isNotBlank()) return candidate(ActionName.PLAY_MUSIC, ActionParameters.PlayMusic(query), language, RouteConfidence.MEDIUM, RouteReason.NATURAL_LANGUAGE_MATCH)
                return clarify("Which song or artist should I play?", ActionName.PLAY_MUSIC, language, RouteReason.MISSING_PARAMETER)
            }
            return clarify("Do you want me to find music or search the web?", null, language, RouteReason.AMBIGUOUS_REFERENCE)
        }

        val rawUrl = Regex("(?i)\\b(?:https?://|ftp://)[^\\s]+")
            .find(q)?.value?.trimEnd('.', ',', '?', '!')
        if (rawUrl != null && containsAny(q, RoutingPhraseCatalog.open)) {
            return candidate(ActionName.OPEN_URL, ActionParameters.OpenUrl(rawUrl), language)
        }
        val packageToken = Regex("(?i)(?:^|\\s)open\\s+([^\\s]+\\.[^\\s]+)")
            .find(q)?.groupValues?.getOrNull(1)
            ?.trimEnd('.', ',', '?', '!')
        if (packageToken != null && packageToken.startsWith("com.")) {
            return candidate(ActionName.OPEN_APP, ActionParameters.OpenApp(packageToken), language)
        }
        if (packageToken != null && packageToken.substringAfterLast('.').lowercase() in WEB_TLDS) {
            return candidate(ActionName.OPEN_URL, ActionParameters.OpenUrl("https://$packageToken"), language)
        }

        val callIntent = containsAny(q, RoutingPhraseCatalog.call)
        if (callIntent) {
            val target = preserveOriginalCase(cleanQuery(q, setOf("call", "phone", "kar", "karo", "please", "now", "to", "ne", "ko")), original)
            if (target.isBlank() || target in SENSITIVE_PRONOUNS) {
                return clarify("Who should I call?", ActionName.CALL, language, RouteReason.MISSING_PARAMETER)
            }
            return candidate(ActionName.CALL, ActionParameters.Call(target), language, RouteConfidence.MEDIUM, RouteReason.NATURAL_LANGUAGE_MATCH)
        }

        val hasWhatsApp = q.contains("whatsapp")
        val hasMessage = hasWhatsApp && (containsAny(q, RoutingPhraseCatalog.message) || containsAny(q, setOf("send", "moklo", "mokal", "mokle", "bhejo", "भेजो")))
        if (hasWhatsApp && hasMessage) {
            val parsed = WhatsAppManager.parseWhatsAppVoiceCommand(original)
                ?: WhatsAppManager.parseWhatsAppVoiceCommand(normalized.normalized)
            if (parsed != null && !parsed.first.isSensitivePronoun()) {
                val message = parsed.second?.takeIf(String::isNotBlank)
                if (message != null) return candidate(ActionName.SEND_WHATSAPP, ActionParameters.SendWhatsApp("${parsed.first}|$message"), language, RouteConfidence.MEDIUM, RouteReason.NATURAL_LANGUAGE_MATCH)
                return clarify("What message should I prepare for ${parsed.first}?", ActionName.SEND_WHATSAPP, language, RouteReason.MISSING_PARAMETER, mapOf("target" to parsed.first))
            }
            val target = preserveOriginalCase(cleanQuery(q, setOf("whatsapp", "message", "msg", "send", "moklo", "mokal", "mokle", "bhejo", "on", "par", "to", "ne", "ko", "please", "now")), original)
            if (target.isBlank() || target.isSensitivePronoun()) {
                return clarify("Who should receive the WhatsApp message?", ActionName.SEND_WHATSAPP, language, RouteReason.MISSING_PARAMETER)
            }
            return clarify("What message should I prepare for $target?", ActionName.SEND_WHATSAPP, language, RouteReason.MISSING_PARAMETER, mapOf("target" to target))
        }
        return null
    }

    private fun candidate(
        name: ActionName,
        parameters: ActionParameters,
        language: String,
        confidence: RouteConfidence = RouteConfidence.HIGH,
        reason: RouteReason = RouteReason.DETERMINISTIC_MATCH
    ): RouteDecision {
        val request = ActionRequest("ROUTE_${UUID.randomUUID()}", name, parameters)
        return RouteDecision.DirectTool(request, language, confidence, reason)
    }

    private fun validate(route: RouteDecision.DirectTool): RouteDecision {
        val request = route.request
        if (!registry.accepts(request.name, request.parameters)) {
            return RouteDecision.Unsupported(request.name, route.language)
        }
        val invalid = when (val parameters = request.parameters) {
            is ActionParameters.OpenApp -> !parameters.packageName.matches(PACKAGE_NAME)
            is ActionParameters.OpenUrl -> !isHttpUrl(parameters.url)
            is ActionParameters.SearchWeb -> parameters.query.isBlank()
            is ActionParameters.PlayMusic -> parameters.query.isBlank()
            is ActionParameters.PlayVideo -> parameters.query.isBlank()
            is ActionParameters.Call -> parameters.target.isBlank() || parameters.target.isSensitivePronoun()
            is ActionParameters.SendSms -> parameters.targetAndMessage.substringBefore('|').isBlank()
            is ActionParameters.SendWhatsApp -> parameters.targetAndMessage.substringBefore('|').isBlank()
            is ActionParameters.SetAlarm -> parameters.hour !in 0..23
            is ActionParameters.SetTimer -> parameters.minutes <= 0
            is ActionParameters.Navigate -> parameters.destination.isBlank()
            is ActionParameters.ToggleWifi -> parameters.state !in TOGGLE_STATES
            is ActionParameters.ToggleBluetooth -> parameters.state !in TOGGLE_STATES
            is ActionParameters.SetBrightness -> parameters.percent !in 0..100
            is ActionParameters.CreateTask -> parameters.value.isBlank()
            is ActionParameters.CompleteTask -> parameters.title.isBlank()
            is ActionParameters.RememberPreference -> parameters.value.isBlank()
            is ActionParameters.ForgetMemory -> parameters.value.isBlank()
            else -> false
        }
        if (!invalid) return route
        val partial = when (val p = request.parameters) {
            is ActionParameters.OpenApp -> mapOf("packageName" to p.packageName)
            is ActionParameters.OpenUrl -> mapOf("url" to p.url)
            else -> emptyMap()
        }
        return clarify(
            prompt = if (request.name == ActionName.OPEN_URL) "Please provide a valid http or https URL." else parameterPrompt(request.name, route.language),
            action = request.name,
            language = route.language,
            reason = RouteReason.INVALID_PARAMETER,
            partial = partial
        )
    }

    private fun clarify(
        prompt: String,
        action: ActionName?,
        language: String,
        reason: RouteReason,
        partial: Map<String, String> = emptyMap()
    ) = RouteDecision.ClarificationRequired(localizePrompt(prompt, language), action, partial, language, reason = reason)

    private fun localizePrompt(prompt: String, language: String): String {
        val target = prompt.substringAfter("What message should I prepare for ", missingDelimiterValue = "").removeSuffix("?")
        return when (language) {
            "gu" -> when {
                prompt.startsWith("Who should I call?") -> "કોને ફોન કરું?"
                prompt.startsWith("Who should receive the WhatsApp message") -> "વોટ્સએપ સંદેશ કોને મોકલવો?"
                prompt.startsWith("What message should I prepare for ") -> "$target માટે કયો સંદેશ તૈયાર કરું?"
                prompt.startsWith("Who should receive the message") -> "સંદેશ કોને મોકલવો અને તેમાં શું લખવું?"
                prompt.startsWith("Which song or artist") -> "કયું ગીત અથવા કલાકાર વગાડવો?"
                prompt.startsWith("Which video") || prompt.startsWith("What video") -> "કયો વીડિયો શોધવો?"
                prompt.startsWith("What should I search for on YouTube") -> "YouTube પર શું શોધવું?"
                prompt.startsWith("What time") -> "એલાર્મ કયા સમયે મૂકવો?"
                prompt.startsWith("How many minutes") -> "ટાઈમર કેટલા મિનિટનો રાખવો?"
                prompt.startsWith("What would you like me to play") -> "તમારે શું વગાડવું છે?"
                prompt.startsWith("Which item or person") -> "કઈ વસ્તુ અથવા વ્યક્તિનો ઉલ્લેખ છે?"
                prompt.startsWith("Should I search the web") -> "વેબ પર શોધવું છે કે વીડિયો શોધવો છે?"
                prompt.startsWith("Please provide a valid http") -> "કૃપા કરીને માન્ય http અથવા https URL આપો."
                else -> prompt
            }
            "hi" -> when {
                prompt.startsWith("Who should I call?") -> "मैं किसे कॉल करूँ?"
                prompt.startsWith("Who should receive the WhatsApp message") -> "व्हाट्सऐप संदेश किसे भेजना है?"
                prompt.startsWith("What message should I prepare for ") -> "$target के लिए कौन सा संदेश तैयार करूँ?"
                prompt.startsWith("Who should receive the message") -> "संदेश किसे भेजना है और उसमें क्या लिखना है?"
                prompt.startsWith("Which song or artist") -> "कौन सा गाना या कलाकार चलाऊँ?"
                prompt.startsWith("Which video") || prompt.startsWith("What video") -> "कौन सा वीडियो ढूँढूँ?"
                prompt.startsWith("What should I search for on YouTube") -> "YouTube पर क्या खोजूँ?"
                prompt.startsWith("What time") -> "अलार्म किस समय लगाऊँ?"
                prompt.startsWith("How many minutes") -> "टाइमर कितने मिनट का हो?"
                prompt.startsWith("What would you like me to play") -> "आप क्या चलाना चाहते हैं?"
                prompt.startsWith("Which item or person") -> "आप किस चीज़ या व्यक्ति की बात कर रहे हैं?"
                prompt.startsWith("Should I search the web") -> "क्या वेब पर खोजूँ या वीडियो ढूँढूँ?"
                prompt.startsWith("Please provide a valid http") -> "कृपया मान्य http या https URL दें।"
                else -> prompt
            }
            else -> prompt
        }
    }

    private fun parameterPrompt(action: ActionName, language: String): String = when (action) {
        ActionName.PLAY_MUSIC -> "Which song or artist should I play?"
        ActionName.PLAY_VIDEO -> "Which video should I find?"
        ActionName.CALL -> "Who should I call?"
        ActionName.SEND_WHATSAPP -> "Who should receive the WhatsApp message, and what should it say?"
        ActionName.SEND_SMS -> "Who should receive the message, and what should it say?"
        ActionName.SET_ALARM -> "What time should I set the alarm for?"
        ActionName.SET_TIMER -> "How many minutes should the timer run?"
        ActionName.OPEN_APP -> "Which app should I open?"
        else -> if (language == "gu") "કૃપા કરીને જરૂરી વિગતો જણાવો." else if (language == "hi") "कृपया आवश्यक जानकारी बताएं।" else "Please provide the missing details."
    }

    private fun withPlatform(query: String, original: String): String = when {
        original.contains("spotify") -> "$query on spotify"
        original.contains("youtube music") -> "$query on youtube music"
        original.contains("youtube") -> "$query on youtube"
        else -> query
    }

    private fun cleanQuery(text: String, remove: Set<String>): String = text
        .replace(Regex("[^\\p{L}\\p{N}\\s'-]"), " ")
        .split(Regex("\\s+"))
        .filter { token ->
            val word = token.lowercase().trim('’', '\'')
            word !in remove && !isCommandFiller(word)
        }
        .joinToString(" ")
        .trim()

    private fun isCommandFiller(word: String): Boolean = word in FILLER_WORDS

    private fun preserveOriginalCase(value: String, original: String): String {
        val originalByWord = original
            .split(Regex("\\s+"))
            .map { it.trim().trim(',', '.', '?', '!', ':', ';', '\'', '"') }
            .filter(String::isNotBlank)
            .associateBy { it.lowercase() }
        return value.split(Regex("\\s+"))
            .joinToString(" ") { word -> originalByWord[word.lowercase()] ?: word }
    }

    private fun containsAny(text: String, cues: Set<String>): Boolean = cues.any { cue ->
        Regex("(?:^|\\s)${Regex.escape(cue)}(?:$|\\s)", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    private fun hasReasoningCue(text: String): Boolean = containsAny(text, REASONING_CUES)

    private fun isHttpUrl(raw: String): Boolean = runCatching {
        val candidate = if (raw.contains("://")) raw else "https://$raw"
        val uri = URI(candidate)
        uri.scheme?.lowercase()?.let { it in setOf("http", "https") } == true && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun String.isSensitivePronoun(): Boolean = lowercase() in SENSITIVE_PRONOUNS

    companion object {
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val TOGGLE_STATES = setOf("on", "off", "toggle", "open")
        private val WEB_TLDS = setOf("com", "org", "net", "io", "dev", "app", "in", "co")
        private val SENSITIVE_PRONOUNS = setOf("him", "her", "them", "it", "that", "this", "someone", "anyone", "me", "myself", "you", "उसको", "उसे", "उन्हें", "તેને", "એને")
        private val AMBIGUOUS_REFERENCE = Regex("(?i)\\b(?:play|open|call|phone|message|msg|send|whatsapp)\\b.*\\b(?:it|that|this|that one|something|him|her|them|उसको|उसे|તેને|એને)\\b|\\b(?:it|that|this|something|him|her|them)\\s+(?:play|open|call|message|msg)\\b")
        private val FOLLOW_UP_REFERENCE = Regex("(?i)(?:^|\\s)(?:it|that|there|same|this)(?:$|\\s)|એ|તે|वह|यह")
        private val FILLER_WORDS = setOf("i", "me", "my", "want", "to", "the", "a", "an", "please", "play", "on", "ma", "nu", "no", "ne", "par", "for", "song", "music", "vagado", "vagad", "vagadvo", "bajao", "laga", "do", "set", "alarm", "kal", "tomorrow", "subah", "morning", "baje", "at", "kar", "karo", "search", "find", "video", "videos", "youtube", "yt", "spotify", "open", "launch", "khol", "kholo", "खोलो", "ખોલો", "mujhe", "mare", "મારે", "છે", "હતું", "है")
        private val REASONING_CUES = setOf("compare", "explain", "summarize", "summarise", "plan", "why", "how", "કેમ", "સમજાવો", "तुलना", "समझाओ", "क्यों")
    }
}
