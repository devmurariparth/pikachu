package com.example.voice

/** Languages that have deterministic local command normalization. */
enum class SupportedLanguage(val code: String) { GUJARATI("gu"), HINDI("hi"), ENGLISH("en") }

enum class DetectedScript { LATIN, GUJARATI, DEVANAGARI, MIXED, OTHER, UNKNOWN }
enum class InputConfidence { HIGH, MEDIUM, LOW }
enum class InputCategory { DETERMINISTIC, TRANSLITERATED, MIXED_LANGUAGE, AMBIGUOUS, UNSUPPORTED, INVALID }
enum class PreservedEntityType { QUOTED_TEXT, URL, NUMBER_OR_TIME, APP_NAME, PROPER_NOUN }
enum class MultilingualErrorCode {
    UNSUPPORTED_LANGUAGE,
    INVALID_INPUT,
    AMBIGUOUS_LANGUAGE,
    MISSING_ENTITY,
    ENTITY_PARSE_FAILED,
    CONTEXT_UNSAFE,
    NORMALIZATION_FAILED
}

data class PreservedEntity(val text: String, val type: PreservedEntityType)

/**
 * The one normalized representation shared by routing and the planner. `original`/`normalized`/
 * `language`/`mixed` aliases keep existing Phase 2 and Phase 3 callers source-compatible.
 */
data class MultilingualInput(
    val originalText: String,
    val normalizedText: String,
    val detectedLanguage: SupportedLanguage,
    val detectedScript: DetectedScript,
    val transliteratedText: String,
    val mixedLanguage: Boolean,
    val confidence: InputConfidence,
    val category: InputCategory,
    val preservedEntities: List<PreservedEntity>,
    val errors: Set<MultilingualErrorCode> = emptySet()
) {
    val original: String get() = originalText
    val normalized: String get() = normalizedText
    val language: SupportedLanguage get() = detectedLanguage
    val mixed: Boolean get() = mixedLanguage
}

typealias NormalizedCommand = MultilingualInput

/** Local, deterministic detection and canonicalization. It never translates entity payloads. */
object LanguageNormalizer {
    private val gujaratiScript = Regex("[\\u0A80-\\u0AFF]")
    private val devanagariScript = Regex("[\\u0900-\\u097F]")
    private val otherLetterScript = Regex("[\\p{L}&&[^\\u0000-\\u024F\\u0900-\\u097F\\u0A80-\\u0AFF]]")
    private val latinLetters = Regex("[A-Za-z]")
    private val wordSplit = Regex("[^\\p{L}\\p{N}]+")

    private val gujaratiWords = setOf(
        "kholo", "khol", "kholje", "vagad", "vagado", "vagadvo", "moklo", "mokal", "mokale", "mokalo", "kar",
        "shodh", "shodho", "karvu", "che", "mare", "maare", "ne", "nu", "ane", "hamna", "gana", "gaana", "samjavo",
        "kaale", "savare", "vage", "vagye", "bandh", "rok", "muk", "muki", "par", "ma"
    )
    private val hindiWords = setOf(
        "kholo", "khol", "kholna", "lagao", "laga", "do", "abhi", "kal", "subah", "baje", "aur",
        "band", "roko", "ruko", "ko", "par", "bhejo", "bhej", "karo", "kare", "dhundo", "dhoondo", "gana", "gaana", "bajao", "samjhao"
    )
    private val englishWords = setOf(
        "open", "launch", "play", "listen", "search", "find", "call", "phone", "message", "send",
        "please", "could", "can", "would", "set", "alarm", "timer", "song", "music", "video", "for", "the"
    )
    private val englishEntityWords = setOf("spotify", "youtube", "whatsapp", "gmail", "chrome", "maps")

    private val phraseMap: List<Pair<String, String>> = listOf(
        // Gujarati script; longer forms precede shorter forms.
        "શોધી આપ" to "search", "શોધજે" to "search", "કરી આપ" to "", "કરવું છે" to "", "કરવું" to "", "કરવા" to "", "કરો" to "", "મારે" to "", "મને" to "", "ફોન કરો" to "call", "ફોન કર" to "call", "મોકલી આપ" to "send",
        "મેસેજ મોકલો" to "message send", "મેસેજ મોકલ" to "message send", "વગાડવું" to "play",
        "વગાડજો" to "play", "વગાડજે" to "play", "વગાડો" to "play", "વગાડ" to "play",
        "બજાવો" to "play", "ચાલુ કરો" to "play", "ખોલજે" to "open", "ખોલો" to "open", "ખોલ" to "open",
        "શોધજો" to "search", "શોધો" to "search", "શોધ" to "search", "મોકલો" to "send", "મોકલ" to "send",
        "કાલે" to "tomorrow", "સવારે" to "morning", "વાગ્યે" to "at", "વાગે" to "at",
        "હમણાં" to "now", "એલાર્મ" to "alarm", "ટાઈમર" to "timer", "મિનિટ" to "minutes",
        "મૂકી દો" to "set", "મૂકી દે" to "set", "લગાવો" to "set", "લગાડ" to "set", "રોકો" to "stop", "રોક" to "stop",
        "બંધ કરો" to "cancel", "બંધ કર" to "cancel", "બંધ" to "stop", "ગીત" to "song",
        "વીડિયો" to "video", "વિડિયો" to "video", "યૂટ્યુબ મ્યુઝિક" to "youtube music",
        "યુટ્યુબ મ્યુઝિક" to "youtube music", "યૂટ્યુબ" to "youtube", "યુટ્યુબ" to "youtube",
        "સ્પોટિફાઈ" to "spotify", "સ્પોટિફાઇ" to "spotify", "વોટ્સએપ" to "whatsapp", "વ્હોટ્સએપ" to "whatsapp",
        "મેસેજ" to "message", "ને" to "to", "પર" to "on", "માં" to "in", "છે" to "",
        // Hindi script.
        "वीडियो खोजो" to "video search", "वीडियो खोज" to "video search", "मुझे" to "", "करना है" to "", "कॉल करो" to "call",
        "फोन करो" to "call", "फोन कर" to "call", "लगा दो" to "set", "लगाओ" to "set",
        "लगाना" to "set", "लगा" to "set", "कर दो" to "", "करो" to "", "करना" to "", "खोलना" to "open", "खोलो" to "open", "खोल" to "open",
        "खोजो" to "search", "खोज" to "search", "ढूंढो" to "search", "ढूँढो" to "search",
        "बजाओ" to "play", "बजा" to "play", "चलाओ" to "play", "भेजो" to "send", "भेज" to "send", "मैसेज" to "message",
        "मेसेज" to "message", "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp",
        "यूट्यूब म्यूजिक" to "youtube music", "यूट्यूब" to "youtube", "स्पॉटिफाई" to "spotify",
        "अलार्म" to "alarm", "टाइमर" to "timer", "मिनट" to "minutes", "कल" to "tomorrow",
        "सुबह" to "morning", "बजे" to "at", "अभी" to "now", "रुको" to "stop", "रोको" to "stop",
        "बंद करो" to "cancel", "बंद कर" to "cancel", "गाने" to "song", "गाना" to "song", "को" to "to", "पर" to "on",
        // Latin-script Gujarati and Hindi variants.
        "vagadvo" to "play", "vagado" to "play", "vagadjo" to "play", "vagadje" to "play", "vagad" to "play", "bajao" to "play",
        "samjavo" to "explain", "samjhao" to "explain", "gana" to "song", "gaana" to "song",
        "mokale" to "send", "moklo" to "send", "mokle" to "send", "mokal" to "send", "mokalo" to "send",
        "shodho" to "search", "shodh" to "search", "kholo" to "open", "kholje" to "open", "khol" to "open",
        "karvu che" to "", "karvu chhe" to "", "set kari de" to "set", "muki do" to "set", "muki de" to "set",
        "kaale" to "tomorrow", "savare" to "morning", "vagye" to "at", "vage" to "at", "hamna" to "now",
        "nu" to "", "ne" to "to", "ma" to "on", "par" to "on", "che" to "", "chhe" to "",
        "lagao" to "set", "laga do" to "set", "laga" to "set", "baje" to "at", "subah" to "morning",
        "kal" to "tomorrow", "bhejo" to "send", "bhej" to "send", "dhundo" to "search", "dhoondo" to "search",
        "karo" to "", "kar" to "", "do" to "", "aur" to "and", "abhi" to "now", "bandh" to "stop",
        "ruko" to "stop", "roko" to "stop", "alarm muk" to "set alarm", "nu alarm" to "alarm", "nu timer" to "timer"
    ).sortedByDescending { it.first.length }

    private val appNames = listOf(
        "youtube music", "google maps", "whatsapp", "youtube", "spotify", "chrome", "gmail", "messages", "camera", "photos"
    )
    private val quoted = Regex("\"[^\"\\r\\n]{1,1000}\"|'(?:[^'\\r\\n]|'(?=[\\p{L}])){1,1000}'|“[^”\\r\\n]{1,1000}”|‘[^’\\r\\n]{1,1000}’")
    private val url = Regex("(?i)\\b(?:https?://|ftp://)[^\\s<>\\\"']+")
    private val numberOrTime = Regex("(?<![\\p{L}\\p{N}])\\d{1,4}(?::\\d{2})?(?:\\s?(?:am|pm))?(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val properNoun = Regex("(?<![\\p{L}\\p{N}])[A-Z][\\p{L}\\p{M}0-9]*(?:['’.-][A-Z]?[\\p{L}\\p{M}0-9]+)*(?![\\p{L}\\p{N}])")
    private val cancellationPhrases = setOf(
        "stop", "cancel", "cancel karo", "cancel kar", "cancel kro", "રોક", "રોકો", "બંધ", "બંધ કર", "બંધ કરો",
        "roko", "ruko", "रुको", "रोको", "બંધ કરો", "रोक", "रोक दो"
    )

    /** Compatibility facade used by the Phase 2/3 call sites. */
    fun normalize(text: String): MultilingualInput = understand(text)

    fun understand(text: String): MultilingualInput = try {
        normalizeInternal(text)
    } catch (_: Exception) {
        errorResult(text, MultilingualErrorCode.NORMALIZATION_FAILED)
    }

    private fun normalizeInternal(text: String): MultilingualInput {
        val original = text.trim()
        if (original.isEmpty() || original.length > MAX_INPUT_LENGTH || hasUnpairedSurrogate(original)) {
            return errorResult(original, MultilingualErrorCode.INVALID_INPUT)
        }

        val hasGu = gujaratiScript.containsMatchIn(original)
        val hasHi = devanagariScript.containsMatchIn(original)
        val hasLatin = latinLetters.containsMatchIn(original)
        val hasUnsupportedScript = otherLetterScript.containsMatchIn(original)
        val tokens = original.lowercase().split(wordSplit).filter(String::isNotBlank)
        val guScore = tokens.count { it in gujaratiWords } + if (hasGu) 5 else 0
        val hiScore = tokens.count { it in hindiWords } + if (hasHi) 5 else 0
        val enScore = tokens.count { it in englishWords }
        val script = detectScript(original)
        val errors = linkedSetOf<MultilingualErrorCode>()
        val language: SupportedLanguage
        val confidence: InputConfidence
        val category: InputCategory

        when {
            hasUnsupportedScript -> {
                language = SupportedLanguage.ENGLISH
                confidence = InputConfidence.LOW
                category = InputCategory.UNSUPPORTED
                errors += MultilingualErrorCode.UNSUPPORTED_LANGUAGE
            }
            hasGu && hasHi -> {
                language = if (guScore == hiScore) SupportedLanguage.ENGLISH else if (guScore > hiScore) SupportedLanguage.GUJARATI else SupportedLanguage.HINDI
                confidence = if (guScore == hiScore) InputConfidence.LOW else InputConfidence.MEDIUM
                category = if (guScore == hiScore) InputCategory.AMBIGUOUS else InputCategory.MIXED_LANGUAGE
                if (guScore == hiScore) errors += MultilingualErrorCode.AMBIGUOUS_LANGUAGE
            }
            hasGu -> {
                language = SupportedLanguage.GUJARATI
                confidence = InputConfidence.HIGH
                category = if (hasLatin) InputCategory.MIXED_LANGUAGE else InputCategory.DETERMINISTIC
            }
            hasHi -> {
                language = SupportedLanguage.HINDI
                confidence = InputConfidence.HIGH
                category = if (hasLatin) InputCategory.MIXED_LANGUAGE else InputCategory.DETERMINISTIC
            }
            guScore > hiScore && guScore > 0 -> {
                language = SupportedLanguage.GUJARATI
                confidence = if (guScore >= 2) InputConfidence.MEDIUM else InputConfidence.LOW
                category = InputCategory.TRANSLITERATED
            }
            hiScore > guScore && hiScore > 0 -> {
                language = SupportedLanguage.HINDI
                confidence = if (hiScore >= 2) InputConfidence.MEDIUM else InputConfidence.LOW
                category = InputCategory.TRANSLITERATED
            }
            guScore > 0 && hiScore > 0 -> {
                language = SupportedLanguage.ENGLISH
                confidence = InputConfidence.LOW
                category = InputCategory.AMBIGUOUS
                errors += MultilingualErrorCode.AMBIGUOUS_LANGUAGE
            }
            else -> {
                language = SupportedLanguage.ENGLISH
                confidence = if (enScore > 0 || tokens.any { it in englishEntityWords }) InputConfidence.HIGH else InputConfidence.MEDIUM
                category = InputCategory.DETERMINISTIC
            }
        }

        val entities = extractEntities(original)
        val masked = maskEntities(original, entities)
        val canonical = canonicalize(masked.text).replace(Regex("\\s+"), " ").trim()
        val normalized = unmaskEntities(canonical, masked.replacements)
        val mixed = (hasGu || hasHi || language != SupportedLanguage.ENGLISH) && hasLatin &&
            (enScore > 0 || tokens.any { it in englishEntityWords } || hasGu || hasHi)
        return MultilingualInput(
            originalText = original,
            normalizedText = normalized,
            detectedLanguage = language,
            detectedScript = script,
            transliteratedText = normalized,
            mixedLanguage = mixed,
            confidence = confidence,
            category = category,
            preservedEntities = entities,
            errors = errors
        )
    }

    fun isCancellation(text: String): Boolean {
        val compact = text.lowercase().trim().trimEnd('.', '!', '?', ',', ';', ':').replace(Regex("\\s+"), " ")
        return compact in cancellationPhrases
    }

    private data class Masked(val text: String, val replacements: List<Pair<String, String>>)

    private fun extractEntities(text: String): List<PreservedEntity> {
        val found = mutableListOf<Pair<IntRange, PreservedEntity>>()
        fun addMatches(regex: Regex, type: PreservedEntityType) {
            regex.findAll(text).forEach { match ->
                if (found.none { (range, _) -> match.range.first <= range.last && range.first <= match.range.last }) {
                    found += match.range to PreservedEntity(match.value, type)
                }
            }
        }
        addMatches(quoted, PreservedEntityType.QUOTED_TEXT)
        addMatches(url, PreservedEntityType.URL)
        addMatches(numberOrTime, PreservedEntityType.NUMBER_OR_TIME)
        appNames.forEach { name -> addMatches(Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(name)}(?![\\p{L}\\p{N}])"), PreservedEntityType.APP_NAME) }
        addMatches(properNoun, PreservedEntityType.PROPER_NOUN)
        return found.sortedBy { it.first.first }.map { it.second }
    }

    private fun maskEntities(text: String, entities: List<PreservedEntity>): Masked {
        var result = text
        val replacements = mutableListOf<Pair<String, String>>()
        entities.forEachIndexed { index, entity ->
            val token = "MJENTITYTOKEN${index}X"
            val at = result.indexOf(entity.text)
            if (at >= 0) {
                result = result.replaceRange(at, at + entity.text.length, token)
                replacements += token to entity.text
            }
        }
        return Masked(result, replacements)
    }

    private fun unmaskEntities(text: String, replacements: List<Pair<String, String>>): String =
        replacements.fold(text) { value, (token, original) -> value.replace(token, original) }

    private fun canonicalize(text: String): String {
        var result = text
        phraseMap.forEach { (phrase, replacement) ->
            val pattern = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            result = pattern.replace(result) { replacement }
        }
        // Preserve recipient/item stems while separating common Gujarati/Hindi case suffixes.
        result = Regex("([\\p{IsGujarati}\\p{IsDevanagari}]+)(ને|को)(?![\\p{L}\\p{N}])")
            .replace(result) { "${it.groupValues[1]} to" }
        return result
    }

    private fun detectScript(text: String): DetectedScript {
        val gu = gujaratiScript.containsMatchIn(text)
        val hi = devanagariScript.containsMatchIn(text)
        val latin = latinLetters.containsMatchIn(text)
        val other = otherLetterScript.containsMatchIn(text)
        return when {
            other -> DetectedScript.OTHER
            (gu && hi) || ((gu || hi) && latin) -> DetectedScript.MIXED
            gu -> DetectedScript.GUJARATI
            hi -> DetectedScript.DEVANAGARI
            latin -> DetectedScript.LATIN
            else -> DetectedScript.UNKNOWN
        }
    }

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                Character.isHighSurrogate(char) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return true
                    index++
                }
                Character.isLowSurrogate(char) -> return true
            }
            index++
        }
        return false
    }

    private fun errorResult(text: String, error: MultilingualErrorCode) = MultilingualInput(
        originalText = text.trim().take(MAX_INPUT_LENGTH),
        normalizedText = text.trim().take(MAX_INPUT_LENGTH),
        detectedLanguage = SupportedLanguage.ENGLISH,
        detectedScript = runCatching { detectScript(text) }.getOrDefault(DetectedScript.UNKNOWN),
        transliteratedText = text.trim().take(MAX_INPUT_LENGTH),
        mixedLanguage = false,
        confidence = InputConfidence.LOW,
        category = InputCategory.INVALID,
        preservedEntities = emptyList(),
        errors = setOf(error)
    )

    private const val MAX_INPUT_LENGTH = 4096
}
