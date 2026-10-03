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
    private val guWords = setOf("kholo", "khol", "muk", "muki", "kari", "kar", "de", "nu", "ne", "ane", "hamna", "kaale", "savare", "vage", "vagye", "bandh", "rok", "ma", "par", "vagad", "vagado", "vagadvo", "moklo", "mokal")
    private val hiWords = setOf("khol", "kholo", "lagao", "laga", "do", "abhi", "kal", "subah", "baje", "aur", "band", "roko", "ruko", "ko", "bhejo", "karo")
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
        .replace("ફોન કરો", "call")
        .replace("ફોન કર", "call")
        .replace("વીડિયો શોધો", "video search")
        .replace("વીડિયો શોધ", "video search")
        .replace("ગીત વગાડવું", "song play")
        .replace("ગીત વગાડો", "song play")
        .replace("ગીત વગાડ", "song play")
        .replace("એલાર્મ", "alarm")
        .replace("ટાઈમર", "timer")
        .replace("મિનિટ", "minutes")
        .replace("મૂકી દો", "set")
        .replace("મૂકી દે", "set")
        .replace("ગીત", "song")
        .replace("વગાડો", "play")
        .replace("વગાડ", "play")
        .replace("બજાવો", "play")
        .replace("ચાલુ કરો", "play")
        .replace("વિડિયો", "video")
        .replace("વિડીયો", "video")
        .replace("યૂટ્યુબ મ્યુઝિક", "youtube music")
        .replace("યુટ્યુબ મ્યુઝિક", "youtube music")
        .replace("યૂટ્યુબ", "youtube")
        .replace("યુટ્યુબ", "youtube")
        .replace("સ્પોટિફાઈ", "spotify")
        .replace("સ્પોટિફાઇ", "spotify")
        .replace("વોટ્સએપ", "whatsapp")
        .replace("વ્હોટ્સએપ", "whatsapp")
        .replace("મેસેજ", "message")
        .replace("મોકલ", "send")
        .replace("મોકલો", "send")
        .replace("પર", "on")
        .replace(Regex("([\\p{L}]+)ને"), "$1 to")
        .replace("ને", "to")
        .replace("શોધો", "search")
        .replace("શોધ", "search")
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
        .replace("गाना", "song")
        .replace("गाने", "song")
        .replace("बजाओ", "play")
        .replace("चलाओ", "play")
        .replace("वीडियो", "video")
        .replace("यूट्यूब म्यूजिक", "youtube music")
        .replace("यूट्यूब", "youtube")
        .replace("स्पॉटिफाई", "spotify")
        .replace("व्हाट्सएप", "whatsapp")
        .replace("व्हाट्सऐप", "whatsapp")
        .replace("मैसेज", "message")
        .replace("भेजो", "send")
        .replace("पर", "on")
        .replace(Regex("([\\p{L}]+)को"), "$1 to")
        .replace("को", "to")
        .replace("खोजो", "search")
        .replace("ढूंढो", "search")
        .replace("कल", "tomorrow")
        .replace("सुबह", "morning")
        .replace("बजे", "at")
        .replace("खोलो", "open")
        .replace("खोल", "open")
        .replace("लगा दो", "set")
        .replace("लगाओ", "set")
        .replace("रुको", "stop")
        .replace("रोको", "stop")
        .replace("अलार्म", "alarm")
        .replace("टाइमर", "timer")
        .replace("मिनट", "minutes")
        .replace("कॉल करो", "call")
        .replace("कॉल कर", "call")
        .replace("फोन करो", "call")
        .replace("फोन कर", "call")
        .replace("वीडियो खोजो", "video search")
        .replace("वीडियो खोज", "video search")
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
        .replace(Regex("\\blaga\\b"), "set")
        .replace(Regex("\\b(?:vagadvo|vagado|vagad)\\b"), "play")
        .replace(Regex("\\b(?:moklo|mokal|mokle)\\b"), "send")
        .replace(Regex("\\bmsg\\b"), "message")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
