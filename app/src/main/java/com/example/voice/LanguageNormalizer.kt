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
    private val latin = Regex("[A-Za-z]")
    private val guWords = setOf("kholo","khol","muk","muki","kari","kar","de","nu","ne","ane","hamna","kaale","savare","vage","vagye","bandh","rok")
    private val hiWords = setOf("khol","kholo","lagao","laga","do","abhi","kal","subah","baje","aur","band","roko","ruko")
    private val stopWords = setOf("stop","cancel","roko","ruko","રોક","બંધ","બંધ કર","cancel karo","cancel kar")

    fun normalize(text: String): NormalizedCommand {
        val original = text.trim()
        val hasGu = gujarati.containsMatchIn(original)
        val hasHi = devanagari.containsMatchIn(original)
        val hasLatin = latin.containsMatchIn(original)
        val lower = original.lowercase()
        val guScore = if (hasGu) 4 else lower.split(Regex("""\s+""")).count { it in guWords }
        val hiScore = if (hasHi) 4 else lower.split(Regex("\s+")).count { it in hiWords }
        val language = when {
            guScore > hiScore && guScore > 0 -> SupportedLanguage.GUJARATI
            hiScore > guScore && hiScore > 0 -> SupportedLanguage.HINDI
            hasGu -> SupportedLanguage.GUJARATI
            hasHi -> SupportedLanguage.HINDI
            else -> SupportedLanguage.ENGLISH
        }
        return NormalizedCommand(original, normalizeTransliteration(lower), language, (hasGu && hasLatin) || (hasHi && hasLatin) || (guScore > 0 && hiScore > 0))
    }

    fun isCancellation(text: String): Boolean {
        val compact = text.trim().lowercase().replace(Regex("\s+"), " ")
        return compact in stopWords
    }

    private fun normalizeTransliteration(text: String): String = text
        .replace("nu alarm", "alarm")
        .replace("nu timer", "timer")
        .replace("alarm muk", "set alarm")
        .replace("kholo", "open")
        .replace("khol", "open")
        .replace("खोलो", "open")
        .replace("खोल", "open")
        .replace("abhi", "now")
        .replace("hamna", "now")
        .replace("kal", "tomorrow")
        .replace("kaale", "tomorrow")
        .replace("ane", "and")
        .replace("aur", "and")
        .replace("muki de", "set")
        .replace("laga do", "set")
        .trim()
}
