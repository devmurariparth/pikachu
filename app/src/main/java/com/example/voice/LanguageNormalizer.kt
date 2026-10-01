package com.example.voice

enum class SupportedLanguage(val code: String) { GUJARATI("gu"), HINDI("hi"), ENGLISH("en") }

data class NormalizedCommand(
    val original: String,
    val normalized: String,
    val language: SupportedLanguage,
    val mixed: Boolean
)

object LanguageNormalizer {
    private val gujarati = Regex("""[\u0A80-\u0AFF]""")
    private val devanagari = Regex("""[\u0900-\u097F]""")
    private val latin = Regex("""[A-Za-z]""")
    private val guWords = setOf("kholo", "khol", "muk", "muki", "kari", "kar", "de", "nu", "ne", "ane", "hamna", "kaale", "savare", "vage", "vagye", "bandh", "rok")
    private val hiWords = setOf("khol", "kholo", "lagao", "laga", "do", "abhi", "kal", "subah", "baje", "aur", "band", "roko", "ruko")
    private val stopWords = setOf(
        "stop", "cancel", "cancel karo", "cancel kar", "cancel kro",
        "રોક", "રોકો", "બંધ", "બંધ કર", "બંધ કરો",
        "roko", "ruko", "रुको", "रोको"
    )

    fun normalize(text: String): NormalizedCommand {
        val original = text.trim()
        val hasGu = gujarati.containsMatchIn(original)
        val hasHi = devanagari.containsMatchIn(original)
        val hasLatin = latin.containsMatchIn(original)
        val lower = original.lowercase()
        val tokens = lower.split(Regex("""\s+"""))
        val guScore = if (hasGu) 4 else tokens.count { it in guWords }
        val hiScore = if (hasHi) 4 else tokens.count { it in hiWords }
        val language = when {
            guScore > hiScore && guScore > 0 -> SupportedLanguage.GUJARATI
            hiScore > guScore && hiScore > 0 -> SupportedLanguage.HINDI
            hasGu -> SupportedLanguage.GUJARATI
            hasHi -> SupportedLanguage.HINDI
            else -> SupportedLanguage.ENGLISH
        }
        return NormalizedCommand(
            original,
            normalizeTransliteration(lower),
            language,
            (hasGu && hasLatin) || (hasHi && hasLatin) || (guScore > 0 && hiScore > 0)
        )
    }

    fun isCancellation(text: String): Boolean {
        val compact = text.lowercase()
            .trim()
            .replace(Regex("""[.!?,;:]+$"""), "")
            .replace(Regex("""\s+"""), " ")
        return compact in stopWords
    }

    private fun normalizeTransliteration(text: String): String = text
        // Normalize script-based command words before the router matches intents.
        .replace("હમણાં", "now")
        .replace("કાલે", "tomorrow")
        .replace("સવારે", "morning")
        .replace("વાગ્યે", "at")
        .replace("વાગે", "at")
        .replace("ખોલો", "open")
        .replace("ખોલ", "open")
        .replace("બંધ કરો", "cancel")
        .replace("બંધ કર", "cancel")
        .replace("રોકો", "stop")
        .replace("રોક", "stop")
        .replace("अभी", "now")
        .replace("कल", "tomorrow")
        .replace("सुबह", "morning")
        .replace("बजे", "at")
        .replace("खोलो", "open")
        .replace("खोल", "open")
        .replace("लगा दो", "set")
        .replace("लगाओ", "set")
        .replace("रुको", "stop")
        .replace("रोको", "stop")
        .replace("nu alarm", "alarm")
        .replace("nu timer", "timer")
        .replace("alarm muk", "set alarm")
        .replace("kholo", "open")
        .replace("khol", "open")
        .replace("abhi", "now")
        .replace("hamna", "now")
        .replace("kaale", "tomorrow")
        .replace("savare", "morning")
        .replace("vagye", "at")
        .replace("vage", "at")
        .replace("ane", "and")
        .replace("aur", "and")
        .replace("muki de", "set")
        .replace("laga do", "set")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
