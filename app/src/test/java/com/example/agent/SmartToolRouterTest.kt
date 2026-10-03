package com.example.agent

import com.example.action.ActionName
import com.example.action.ActionParameters
import com.example.action.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartToolRouterTest {
    private val router = SmartToolRouter()

    private fun direct(text: String, action: ActionName): RouteDecision.DirectTool {
        val decision = router.route(text)
        assertTrue("Expected direct $action for '$text', got $decision", decision is RouteDecision.DirectTool)
        return decision as RouteDecision.DirectTool
    }

    private fun clarify(text: String, action: ActionName? = null): RouteDecision.ClarificationRequired {
        val decision = router.route(text)
        assertTrue("Expected clarification for '$text', got $decision", decision is RouteDecision.ClarificationRequired)
        return decision as RouteDecision.ClarificationRequired
    }

    @Test fun english_requests_select_registered_typed_tools() {
        assertEquals("com.spotify.music", (direct("open Spotify", ActionName.OPEN_APP).request.parameters as ActionParameters.OpenApp).packageName)
        assertEquals("Believer", (direct("play Believer", ActionName.PLAY_MUSIC).request.parameters as ActionParameters.PlayMusic).query.substringBefore(" on "))
        assertEquals(ActionName.PLAY_VIDEO, direct("play Naruto video on YouTube", ActionName.PLAY_VIDEO).request.name)
        assertEquals("cats", (direct("search the web for cats", ActionName.SEARCH_WEB).request.parameters as ActionParameters.SearchWeb).query)
        assertEquals("Mom", (direct("call Mom", ActionName.CALL).request.parameters as ActionParameters.Call).target)
        assertEquals("Mom|hello", (direct("send WhatsApp to Mom saying hello", ActionName.SEND_WHATSAPP).request.parameters as ActionParameters.SendWhatsApp).targetAndMessage)
        assertEquals(7, (direct("set alarm for 7", ActionName.SET_ALARM).request.parameters as ActionParameters.SetAlarm).hour)
        assertEquals(5, (direct("set a timer for 5 minutes", ActionName.SET_TIMER).request.parameters as ActionParameters.SetTimer).minutes)
    }

    @Test fun gujarati_and_hindi_app_media_call_message_and_alarm_route_safely() {
        assertEquals(ActionName.OPEN_APP, direct("Spotify ખોલ", ActionName.OPEN_APP).request.name)
        assertTrue((direct("મારે Spotify પર Arijit nu song વગાડવું છે", ActionName.PLAY_MUSIC).request.parameters as ActionParameters.PlayMusic).query.contains("Arijit", ignoreCase = true))
        assertEquals(ActionName.PLAY_VIDEO, direct("YouTube પર Naruto video શોધ", ActionName.PLAY_VIDEO).request.name)
        assertEquals(ActionName.CALL, direct("મમ્મીને ફોન કર", ActionName.CALL).request.name)
        assertEquals(ActionName.SET_ALARM, direct("कल सुबह 7 बजे alarm लगा दो", ActionName.SET_ALARM).request.name)
        assertEquals(ActionName.OPEN_APP, direct("Spotify खोलो", ActionName.OPEN_APP).request.name)
        assertEquals(ActionName.PLAY_MUSIC, direct("गाना बजाओ Kesariya", ActionName.PLAY_MUSIC).request.name)
        assertEquals(ActionName.PLAY_VIDEO, direct("वीडियो खोजो Naruto on YouTube", ActionName.PLAY_VIDEO).request.name)
        assertTrue(clarify("Mom ko WhatsApp message bhejo").action == ActionName.SEND_WHATSAPP)
        assertEquals(5, (direct("5 मिनट का टाइमर लगाओ", ActionName.SET_TIMER).request.parameters as ActionParameters.SetTimer).minutes)
    }

    @Test fun transliterated_mixed_requests_route_locally_or_ask_for_required_details() {
        assertEquals(ActionName.PLAY_MUSIC, direct("Spotify ma Arijit nu song vagadvo", ActionName.PLAY_MUSIC).request.name)
        assertEquals(ActionName.PLAY_VIDEO, direct("YouTube ma Naruto search kar", ActionName.PLAY_VIDEO).request.name)
        val message = clarify("Mom ne WhatsApp msg mokal")
        assertEquals(ActionName.SEND_WHATSAPP, message.action)
        assertEquals("Mom", message.partialParameters["target"])
        assertEquals(ActionName.SET_ALARM, clarify("kal alarm laga").action)
    }

    @Test fun incomplete_and_sensitive_targets_are_never_guessed() {
        assertEquals(null, clarify("play it").action)
        assertEquals(null, clarify("play something").action)
        assertEquals(ActionName.CALL, clarify("call her").action)
        assertEquals(null, clarify("message him").action)
        assertEquals(ActionName.OPEN_APP, clarify("open that").action)
    }

    @Test fun malformed_url_and_unsupported_registry_tool_are_typed_decisions() {
        val invalidUrl = clarify("open ftp://example.com")
        assertEquals(RouteReason.INVALID_PARAMETER, invalidUrl.reason)
        assertEquals(RouteReason.INVALID_PARAMETER, clarify("open com.invalid..package").reason)
        val unsupported = SmartToolRouter(ToolRegistry(emptyList())).route("open Spotify")
        assertTrue(unsupported is RouteDecision.Unsupported)
        assertEquals(ActionName.OPEN_APP, (unsupported as RouteDecision.Unsupported).action)
    }

    @Test fun conflicting_media_search_and_web_search_are_clarified() {
        val conflict = clarify("search the web for a video about Naruto")
        assertEquals(RouteReason.AMBIGUOUS_REFERENCE, conflict.reason)
        assertEquals(ActionName.PLAY_VIDEO, clarify("find a video").action)
        assertEquals(ActionName.PLAY_MUSIC, clarify("find a song").action)
        assertEquals(ActionName.SEARCH_WEB, direct("search the web for weather", ActionName.SEARCH_WEB).request.name)
    }

    @Test fun natural_reasoning_and_safe_context_require_planner_without_raw_tool_output() {
        assertTrue(router.route("compare Spotify and YouTube Music") is RouteDecision.PlannerRequired)
        val contextual = router.route("tell me more about it", safeFollowUpContext = "search the web for cats")
        assertTrue(contextual is RouteDecision.PlannerRequired)
        assertFalse(contextual is RouteDecision.DirectTool)
        assertTrue(router.route("play it", safeFollowUpContext = "play music Believer") is RouteDecision.PlannerRequired)
        assertTrue(router.route("call her", safeFollowUpContext = "open Spotify") is RouteDecision.ClarificationRequired)
        assertTrue(router.route("nonsense") is RouteDecision.NoMatch)
    }

}
