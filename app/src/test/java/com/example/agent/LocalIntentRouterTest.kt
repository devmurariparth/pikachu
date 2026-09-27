package com.example.agent

import com.example.action.ActionName
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalIntentRouterTest {
    @Test fun gujarati_youtube_maps_to_open_app() {
        val route = LocalIntentRouter.route("હમણાં YouTube ખોલ")
        assertEquals(ActionName.OPEN_APP, (route as LocalRoute.Handled).request.name)
    }

    @Test fun hindi_youtube_maps_to_open_app() {
        val route = LocalIntentRouter.route("अभी YouTube खोलो")
        assertEquals(ActionName.OPEN_APP, (route as LocalRoute.Handled).request.name)
    }

    @Test fun english_youtube_maps_to_open_app() {
        val route = LocalIntentRouter.route("Open YouTube now")
        assertEquals(ActionName.OPEN_APP, (route as LocalRoute.Handled).request.name)
    }

    @Test fun unknown_command_falls_back_to_ai() {
        assertEquals(LocalRoute.FallbackToAi, LocalIntentRouter.route("Explain quantum computing"))
    }

    @Test fun timer_uses_local_path() {
        val route = LocalIntentRouter.route("set a timer for 5 minutes")
        assertEquals(ActionName.SET_TIMER, (route as LocalRoute.Handled).request.name)
    }
}
