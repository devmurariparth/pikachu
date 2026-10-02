package com.example.agent

import com.example.action.ActionName
import com.example.action.ActionParameters
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalIntentRouterTest {
    private fun handled(text: String): LocalRoute.Handled =
        LocalIntentRouter.route(text) as LocalRoute.Handled

    @Test fun gujarati_youtube_maps_to_open_app() {
        assertEquals(ActionName.OPEN_APP, handled("હમણાં YouTube ખોલ").request.name)
    }

    @Test fun hindi_youtube_maps_to_open_app() {
        assertEquals(ActionName.OPEN_APP, handled("अभी YouTube खोलो").request.name)
    }

    @Test fun english_youtube_maps_to_open_app() {
        assertEquals(ActionName.OPEN_APP, handled("Open YouTube now").request.name)
    }

    @Test fun app_first_youtube_open_maps_to_open_app() {
        assertEquals(ActionName.OPEN_APP, handled("YouTube open").request.name)
    }

    @Test fun transliterated_app_first_youtube_kholo_maps_to_open_app() {
        assertEquals(ActionName.OPEN_APP, handled("YouTube kholo").request.name)
    }

    @Test fun hindi_script_app_first_youtube_kholo_maps_to_open_app() {
        assertEquals(ActionName.OPEN_APP, handled("YouTube खोलो").request.name)
    }

    @Test fun verb_first_open_youtube_maps_to_open_app() {
        assertEquals(ActionName.OPEN_APP, handled("open YouTube").request.name)
    }

    @Test fun explicit_youtube_music_open_uses_its_own_app_package() {
        val route = handled("open YouTube Music")
        assertEquals(ActionName.OPEN_APP, route.request.name)
        assertEquals("com.google.android.apps.youtube.music", (route.request.parameters as ActionParameters.OpenApp).packageName)
    }

    @Test fun explicit_spotify_open_is_an_app_launch_not_a_music_search() {
        val route = handled("open Spotify")
        assertEquals(ActionName.OPEN_APP, route.request.name)
        assertEquals("com.spotify.music", (route.request.parameters as ActionParameters.OpenApp).packageName)
    }

    @Test fun video_search_is_local_and_never_routed_as_music() {
        val route = handled("play a MrBeast video")
        assertEquals(ActionName.PLAY_VIDEO, route.request.name)
        assertEquals("mrbeast", (route.request.parameters as ActionParameters.PlayVideo).query)
    }

    @Test fun youtube_search_is_a_video_request() {
        val route = handled("search YouTube for cats")
        assertEquals(ActionName.PLAY_VIDEO, route.request.name)
        assertEquals("cats", (route.request.parameters as ActionParameters.PlayVideo).query)
    }

    @Test fun gujarati_and_hindi_video_commands_stay_on_the_local_route() {
        assertEquals(ActionName.PLAY_VIDEO, handled("MrBeast નો video બતાવો").request.name)
        assertEquals(ActionName.PLAY_VIDEO, handled("MrBeast वीडियो दिखाओ").request.name)
    }

    @Test fun generic_web_search_stays_local() {
        val route = handled("search the web for android assistant APIs")
        assertEquals(ActionName.SEARCH_WEB, route.request.name)
        assertEquals("android assistant apis", (route.request.parameters as ActionParameters.SearchWeb).query)
    }

    @Test fun mixed_gujarati_alarm_uses_local_path() {
        val route = handled("કાલે 8 AM nu alarm set kar")
        assertEquals(ActionName.SET_ALARM, route.request.name)
        assertEquals(8, (route.request.parameters as ActionParameters.SetAlarm).hour)
    }

    @Test fun hindi_alarm_uses_local_path() {
        val route = handled("कल सुबह 8 बजे alarm लगा दो")
        assertEquals(ActionName.SET_ALARM, route.request.name)
        assertEquals(8, (route.request.parameters as ActionParameters.SetAlarm).hour)
    }

    @Test fun alarm_hour_can_precede_alarm_word() {
        val route = handled("8 AM alarm set")
        assertEquals(ActionName.SET_ALARM, route.request.name)
        assertEquals(8, (route.request.parameters as ActionParameters.SetAlarm).hour)
    }

    @Test fun transliterated_hindi_alarm_uses_local_path() {
        val route = handled("8 baje alarm laga do")
        assertEquals(ActionName.SET_ALARM, route.request.name)
        assertEquals(8, (route.request.parameters as ActionParameters.SetAlarm).hour)
    }

    @Test fun alarm_hour_can_follow_alarm_word() {
        val route = handled("alarm set 8")
        assertEquals(ActionName.SET_ALARM, route.request.name)
        assertEquals(8, (route.request.parameters as ActionParameters.SetAlarm).hour)
    }

    @Test fun unknown_command_falls_back_to_ai() {
        assertEquals(LocalRoute.FallbackToAi, LocalIntentRouter.route("Explain quantum computing"))
    }

    @Test fun song_play_uses_local_music_route() {
        val route = handled("Play Believer")
        assertEquals(ActionName.PLAY_MUSIC, route.request.name)
        assertEquals("Believer", (route.request.parameters as ActionParameters.PlayMusic).query)
    }

    @Test fun explicit_spotify_song_stays_on_spotify() {
        val route = handled("Play Believer on Spotify")
        assertEquals(ActionName.PLAY_MUSIC, route.request.name)
        assertEquals("Believer on spotify", (route.request.parameters as ActionParameters.PlayMusic).query)
    }

    @Test fun explicit_video_never_uses_music_route() {
        val route = handled("Play MrBeast video")
        assertEquals(ActionName.PLAY_VIDEO, route.request.name)
        assertEquals("mrbeast", (route.request.parameters as ActionParameters.PlayVideo).query)
    }

    @Test fun video_games_song_stays_on_the_music_route() {
        val route = handled("Play Video Games")
        assertEquals(ActionName.PLAY_MUSIC, route.request.name)
        assertEquals("Video Games", (route.request.parameters as ActionParameters.PlayMusic).query)
    }

    @Test fun youtube_video_search_uses_video_route() {
        val route = handled("search YouTube for MrBeast latest video")
        assertEquals(ActionName.PLAY_VIDEO, route.request.name)
    }

    @Test fun generic_search_uses_local_search_route() {
        val route = handled("search for weather tomorrow")
        assertEquals(ActionName.SEARCH_WEB, route.request.name)
        assertEquals("weather tomorrow", (route.request.parameters as ActionParameters.SearchWeb).query)
    }

    @Test fun whatsapp_message_uses_local_route() {
        val route = handled("send whatsapp to Mom saying hello")
        assertEquals(ActionName.SEND_WHATSAPP, route.request.name)
        assertEquals("Mom|hello", (route.request.parameters as ActionParameters.SendWhatsApp).targetAndMessage)
    }

    @Test fun gujarati_song_uses_local_music_route() {
        val route = handled("ગીત વગાડો Believer")
        assertEquals(ActionName.PLAY_MUSIC, route.request.name)
    }

    @Test fun hindi_video_uses_local_video_route() {
        val route = handled("वीडियो चलाओ MrBeast")
        assertEquals(ActionName.PLAY_VIDEO, route.request.name)
    }

    @Test fun timer_uses_local_path() {
        val route = handled("set a timer for 5 minutes")
        assertEquals(ActionName.SET_TIMER, route.request.name)
    }

    @Test fun notification_and_brightness_commands_use_the_policy_gated_pipeline() {
        assertEquals(ActionName.OPEN_NOTIFICATIONS, handled("open notifications").request.name)
        val brightness = handled("set brightness to 42 percent")
        assertEquals(ActionName.SET_BRIGHTNESS, brightness.request.name)
        assertEquals(42, (brightness.request.parameters as ActionParameters.SetBrightness).percent)
    }

    @Test fun wifi_commands_open_android_controls_without_claiming_a_silent_toggle() {
        val route = handled("turn off wifi")
        assertEquals(ActionName.TOGGLE_WIFI, route.request.name)
        assertEquals("off", (route.request.parameters as ActionParameters.ToggleWifi).state)
    }
}
