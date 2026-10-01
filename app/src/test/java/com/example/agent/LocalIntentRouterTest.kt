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
