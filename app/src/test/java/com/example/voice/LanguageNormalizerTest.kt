package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageNormalizerTest {
    @Test fun gujarati_script_is_detected() {
        val result = LanguageNormalizer.normalize("હમણાં YouTube ખોલ")
        assertEquals(SupportedLanguage.GUJARATI, result.language)
        assertTrue(result.normalized.contains("youtube"))
    }

    @Test fun hindi_script_is_detected() {
        val result = LanguageNormalizer.normalize("अभी YouTube खोलो")
        assertEquals(SupportedLanguage.HINDI, result.language)
        assertTrue(result.normalized.contains("youtube"))
    }

    @Test fun english_is_detected() {
        assertEquals(SupportedLanguage.ENGLISH, LanguageNormalizer.normalize("Open YouTube now").language)
    }

    @Test fun mixed_gujarati_english_is_supported() {
        val result = LanguageNormalizer.normalize("કાલે 8 AM nu alarm set kar")
        assertEquals(SupportedLanguage.GUJARATI, result.language)
        assertTrue(result.mixed)
    }

    @Test fun transliterated_hindi_is_supported() {
        val result = LanguageNormalizer.normalize("kal subah alarm laga do")
        assertEquals(SupportedLanguage.HINDI, result.language)
    }

    @Test fun cancellation_phrases_are_shared() {
        assertTrue(LanguageNormalizer.isCancellation("Stop"))
        assertTrue(LanguageNormalizer.isCancellation("રોક"))
        assertTrue(LanguageNormalizer.isCancellation("रुको"))
    }
}
