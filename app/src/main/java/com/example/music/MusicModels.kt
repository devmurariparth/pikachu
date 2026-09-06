package com.example.music

enum class MusicPlatform(val displayName: String, val packageName: String?) {
    SPOTIFY("Spotify", "com.spotify.music"),
    YOUTUBE("YouTube", "com.google.android.youtube"),
    YOUTUBE_MUSIC("YouTube Music", "com.google.android.apps.youtube.music"),
    AUTO("Auto", null)
}

data class MusicCommand(
    val song: String,
    val artist: String? = null,
    val platform: MusicPlatform? = null,
    val rawQuery: String = ""
)

sealed class MusicExecutionResult {
    data class Success(
        val spokenResponse: String,
        val song: String,
        val artist: String?,
        val platform: MusicPlatform,
        val isInstalledApp: Boolean,
        val webFallback: Boolean = false,
        val actionTaken: String = "PLAY_MUSIC"
    ) : MusicExecutionResult()

    data class NeedsSongPrompt(
        val spokenResponse: String = "What song would you like me to play?"
    ) : MusicExecutionResult()

    data class Error(
        val spokenResponse: String,
        val errorDetails: String
    ) : MusicExecutionResult()
}

data class MusicActionInfo(
    val song: String,
    val artist: String? = null,
    val platform: MusicPlatform,
    val isInstalled: Boolean = true,
    val webFallback: Boolean = false
)
