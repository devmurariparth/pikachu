package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageNormalizerTest {
    @Test fun gujarati_script_is_detected() {
        val result = LanguageNormalizer.normalize("હમણાં YouTube ખોલ")
        assertEquals(SupportedLanguage.GUJARATI, result.language)
        assertTrue(result.normalized.contains("youtube", ignoreCase = true))
    }

    @Test fun hindi_script_is_detected() {
        val result = LanguageNormalizer.normalize("अभी YouTube खोलो")
        assertEquals(SupportedLanguage.HINDI, result.language)
        assertTrue(result.normalized.contains("youtube", ignoreCase = true))
    }

    @Test fun english_is_detected() {
        assertEquals(SupportedLanguage.ENGLISH, LanguageNormalizer.normalize("Open YouTube now").language)
    }

    @Test fun mixed_gujarati_english_is_supported() {
        val result = LanguageNormalizer.normalize("કાલે 8 AM nu alarm set kar")
        assertEquals(SupportedLanguage.GUJARATI, result.language)
        assertTrue(result.mixed)
    }

    @Test fun mixed_hindi_english_is_supported() {
        val result = LanguageNormalizer.normalize("कल सुबह 8 बजे alarm लगा दो")
        assertEquals(SupportedLanguage.HINDI, result.language)
        assertTrue(result.mixed)
    }

    @Test fun transliterated_hindi_is_supported() {
        assertEquals(SupportedLanguage.HINDI, LanguageNormalizer.normalize("kal subah alarm laga do").language)
    }

    @Test fun transliterated_gujarati_mixed_request_normalizes_phrase_and_language() {
        val result = LanguageNormalizer.normalize("Spotify ma Arijit nu song vagadvo")
        assertEquals(SupportedLanguage.GUJARATI, result.language)
        assertTrue(result.normalized.contains("play"))
        assertTrue(result.normalized.contains("spotify", ignoreCase = true))
    }

    @Test fun transliterated_hindi_message_language_is_detected() {
        assertEquals(SupportedLanguage.HINDI, LanguageNormalizer.normalize("Mom ko WhatsApp message bhejo").language)
    }

    @Test fun cancellation_phrases_are_shared() {
        listOf(
            "Stop", "રોક", "રોકો", "બંધ", "બંધ કર", "Cancel",
            "cancel karo", "cancel kar", "रुको", "रोको", "cancel",
            "  STOP  ", "  cancel   karo  "
        ).forEach { phrase ->
            assertTrue("Expected cancellation for: $phrase", LanguageNormalizer.isCancellation(phrase))
        }
    }

    @Test fun structured_result_reports_language_script_confidence_and_entities() {
        val result = LanguageNormalizer.understand("Spotify ma Arijit nu song vagad")
        assertEquals("Spotify ma Arijit nu song vagad", result.originalText)
        assertEquals(SupportedLanguage.GUJARATI, result.detectedLanguage)
        assertEquals(DetectedScript.LATIN, result.detectedScript)
        assertEquals(InputConfidence.MEDIUM, result.confidence)
        assertEquals(InputCategory.TRANSLITERATED, result.category)
        assertTrue(result.mixedLanguage)
        assertTrue(result.normalizedText.contains("Spotify"))
        assertTrue(result.normalizedText.contains("Arijit"))
        assertTrue(result.normalizedText.contains("play"))
        assertTrue(result.preservedEntities.any { it.text == "Spotify" && it.type == PreservedEntityType.APP_NAME })
        assertTrue(result.preservedEntities.any { it.text == "Arijit" })
    }

    @Test fun quoted_message_url_number_and_app_name_survive_normalization() {
        val text = "Mom ne WhatsApp par 'I'm coming home' mokal https://example.com 7"
        val result = LanguageNormalizer.understand(text)
        assertEquals(SupportedLanguage.GUJARATI, result.detectedLanguage)
        assertTrue(result.normalizedText.contains("'I'm coming home'"))
        assertTrue(result.normalizedText.contains("https://example.com"))
        assertTrue(result.normalizedText.contains("7"))
        assertTrue(result.preservedEntities.any { it.text == "Mom" })
        assertTrue(result.preservedEntities.any { it.text == "WhatsApp" && it.type == PreservedEntityType.APP_NAME })
        assertTrue(result.preservedEntities.any { it.text == "'I'm coming home'" && it.type == PreservedEntityType.QUOTED_TEXT })
        assertTrue(result.preservedEntities.any { it.text == "https://example.com" && it.type == PreservedEntityType.URL })
        assertFalse(result.errors.contains(MultilingualErrorCode.INVALID_INPUT))
    }

    @Test fun common_transliterated_hindi_and_gujarati_variants_canonicalize_as_whole_words() {
        val gu = LanguageNormalizer.understand("mare YouTube ma Naruto search karvu che")
        assertEquals(SupportedLanguage.GUJARATI, gu.detectedLanguage)
        assertTrue(gu.normalizedText.contains("YouTube"))
        assertTrue(gu.normalizedText.contains("Naruto"))
        assertTrue(gu.normalizedText.contains("search"))

        val hi = LanguageNormalizer.understand("kal subah 7 baje alarm laga")
        assertEquals(SupportedLanguage.HINDI, hi.detectedLanguage)
        assertTrue(hi.normalizedText.contains("tomorrow"))
        assertTrue(hi.normalizedText.contains("7"))
        assertTrue(hi.normalizedText.contains("alarm"))

        val stem = LanguageNormalizer.understand("Karo KaroPlay")
        assertTrue(stem.normalizedText.contains("KaroPlay"))
    }

    @Test fun supported_script_verb_variants_share_canonical_intents() {
        listOf("ખોલ", "ખોલો", "ખોલજે").forEach {
            assertTrue("Gujarati open variant: $it", LanguageNormalizer.understand(it).normalizedText.contains("open"))
        }
        listOf("વગાડ", "વગાડો", "વગાડજે").forEach {
            assertTrue("Gujarati play variant: $it", LanguageNormalizer.understand(it).normalizedText.contains("play"))
        }
        listOf("શોધ", "શોધો", "શોધજે").forEach {
            assertTrue("Gujarati search variant: $it", LanguageNormalizer.understand(it).normalizedText.contains("search"))
        }
        listOf("खोल", "खोलो", "खोलना").forEach {
            assertTrue("Hindi open variant: $it", LanguageNormalizer.understand(it).normalizedText.contains("open"))
        }
        listOf("बजा", "बजाओ", "चलाओ").forEach {
            assertTrue("Hindi play variant: $it", LanguageNormalizer.understand(it).normalizedText.contains("play"))
        }
    }

    @Test fun mixed_scripts_empty_unsupported_and_malformed_unicode_are_safe_and_typed() {
        val mixed = LanguageNormalizer.understand("ગુજરાતી हिंदी English")
        assertEquals(DetectedScript.MIXED, mixed.detectedScript)
        assertTrue(mixed.errors.contains(MultilingualErrorCode.AMBIGUOUS_LANGUAGE))
        assertEquals(InputConfidence.LOW, mixed.confidence)

        assertTrue(LanguageNormalizer.understand("").errors.contains(MultilingualErrorCode.INVALID_INPUT))
        assertTrue(LanguageNormalizer.understand("مرحبا").errors.contains(MultilingualErrorCode.UNSUPPORTED_LANGUAGE))
        assertTrue(LanguageNormalizer.understand("bad\uD800input").errors.contains(MultilingualErrorCode.INVALID_INPUT))
    }
}
