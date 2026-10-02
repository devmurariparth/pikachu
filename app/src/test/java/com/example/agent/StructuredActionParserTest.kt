package com.example.agent

import com.example.action.ActionName
import com.example.action.ActionParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredActionParserTest {
    private fun response(action: String, payload: String = "null", lang: String = "en", extra: String = "") =
        """{"action":"$action","payload":$payload,"speech":"ready","lang":"$lang"$extra}"""

    @Test fun valid_video_action_maps_to_video_parameters_and_detected_language() {
        val result = StructuredActionParser.parse(response("PLAY_VIDEO", "\"MrBeast\"", "hi"), "gu")

        assertTrue(result.isSuccess)
        assertEquals("gu", result.getOrThrow().language)
        assertEquals(ActionName.PLAY_VIDEO, com.example.action.PlannedActionMapper.map(result.getOrThrow(), "test").getOrThrow().name)
        assertEquals("MrBeast", (com.example.action.PlannedActionMapper.map(result.getOrThrow(), "test").getOrThrow().parameters as ActionParameters.PlayVideo).query)
    }

    @Test fun malformed_json_is_a_safe_failure() {
        val result = StructuredActionParser.parse("{not-json", "en")
        assertTrue(result.isFailure)
        assertEquals(AiProviderErrorCategory.INVALID_API_RESPONSE, (result.exceptionOrNull() as AiProviderException).category)
    }

    @Test fun unknown_action_is_rejected() {
        val result = StructuredActionParser.parse(response("RUN_SHELL"), "en")
        assertTrue(result.isFailure)
        assertEquals(AiProviderErrorCategory.ACTION_ERROR, (result.exceptionOrNull() as AiProviderException).category)
    }

    @Test fun invalid_language_and_extra_fields_are_rejected() {
        assertTrue(StructuredActionParser.parse(response("CHAT", lang = "fr"), "en").isFailure)
        assertTrue(StructuredActionParser.parse(response("CHAT", extra = ",\"debug\":\"value\""), "en").isFailure)
    }

    @Test fun payload_is_checked_against_action_and_music_is_separate_from_video() {
        assertTrue(StructuredActionParser.parse(response("SET_BRIGHTNESS", "\"300\""), "en").isFailure)
        assertTrue(StructuredActionParser.parse(response("PLAY_MUSIC", "\"MrBeast video\""), "en").isFailure)
        assertTrue(StructuredActionParser.parse(response("PLAY_MUSIC", "\"Video Games by Lana Del Rey\""), "en").isSuccess)
        assertTrue(StructuredActionParser.parse(response("PLAY_MUSIC", "\"Believer on YouTube\""), "en").isSuccess)
    }
}
