package com.example.music

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import com.example.AssistantLogger
import com.example.data.AppSettingsManager

object MusicActionManager {

    private const val TAG = "MusicActionManager"

    // Recognized package names
    const val PACKAGE_SPOTIFY = "com.spotify.music"
    const val PACKAGE_YOUTUBE = "com.google.android.youtube"
    const val PACKAGE_YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

    /**
     * Parses a raw voice or text command into a [MusicCommand].
     * Returns null if the command is not a music playback/listening request.
     */
    fun parseMusicCommand(rawInput: String): MusicCommand? {
        if (rawInput.isBlank()) return null

        // 1. Normalize whitespace and trailing punctuation
        var cleaned = rawInput.trim()
            .replace("\\s+".toRegex(), " ")
            .trimEnd('?', '.', '!', ',', ';')

        // 2. Remove conversational wake-word prefixes ("hey mj", "ok mj", "mj", etc.)
        val wakeRegex = "^(?i)(hey\\s+mj|ok\\s+mj|okay\\s+mj|hi\\s+mj|mj)[,:\\s]*".toRegex()
        cleaned = cleaned.replace(wakeRegex, "").trim()

        // 3. Remove conversational politeness prefixes
        val politenessRegex = "^(?i)(can\\s+you\\s+please|could\\s+you\\s+please|would\\s+you\\s+please|please|can\\s+you|could\\s+you|would\\s+you|i\\s+want\\s+to|i'd\\s+like\\s+to|i\\s+would\\s+like\\s+to|let's|let\\s+us)\\s+".toRegex()
        cleaned = cleaned.replace(politenessRegex, "").trim()

        // 4. Check for music command triggers
        val lowerCleaned = cleaned.lowercase()

        // Explicit empty music commands
        if (lowerCleaned == "play" || lowerCleaned == "play music" || lowerCleaned == "play a song" ||
            lowerCleaned == "play song" || lowerCleaned == "listen to music" || lowerCleaned == "listen to a song" ||
            lowerCleaned == "put on music" || lowerCleaned == "stream music"
        ) {
            return MusicCommand(song = "", artist = null, platform = null, rawQuery = rawInput)
        }

        // Check trigger phrases
        val triggerPrefixes = listOf(
            "play the song ",
            "play the track ",
            "play song ",
            "play track ",
            "play the music ",
            "play music ",
            "play ",
            "listen to the song ",
            "listen to the track ",
            "listen to song ",
            "listen to track ",
            "listen to the music ",
            "listen to music ",
            "listen to ",
            "stream the song ",
            "stream the track ",
            "stream song ",
            "stream track ",
            "stream ",
            "put on the song ",
            "put on the track ",
            "put on song ",
            "put on track ",
            "put on "
        )

        val matchedTrigger = triggerPrefixes.firstOrNull { lowerCleaned.startsWith(it) }

        // Also check if prefix is "on spotify play ...", "on youtube play ..."
        val prefixPlatformMatch = extractPrefixPlatform(cleaned)

        val textAfterTrigger = when {
            prefixPlatformMatch != null -> prefixPlatformMatch.first
            matchedTrigger != null -> cleaned.substring(matchedTrigger.length).trim()
            else -> return null // Not a music command (e.g. "Who sings Believer?", "What is Believer?")
        }

        val initialPlatform = prefixPlatformMatch?.second

        // 5. Extract platform from suffix if not already detected from prefix
        val (textWithoutPlatform, platform) = if (initialPlatform != null) {
            textAfterTrigger to initialPlatform
        } else {
            extractSuffixPlatform(textAfterTrigger)
        }

        var candidate = textWithoutPlatform.trim()

        // Strip surrounding quotes
        if ((candidate.startsWith("\"") && candidate.endsWith("\"")) ||
            (candidate.startsWith("'") && candidate.endsWith("'")) ||
            (candidate.startsWith("“") && candidate.endsWith("”"))
        ) {
            candidate = candidate.substring(1, candidate.length - 1).trim()
        }

        // Strip leading "the song ", "song ", "the track ", "track ", "the music " if still present
        val songPrefixes = listOf(
            "the song ", "song ", "the track ", "track ", "the music ", "my favorite song ",
            "some music by ", "music by ", "some songs by ", "songs by ", "some tracks by ", "tracks by ",
            "some music from ", "music from ", "some songs from ", "songs from ",
            "some music ", "music ", "some songs ", "songs "
        )
        val lowerCandidate = candidate.lowercase()
        val matchedSongPrefix = songPrefixes.firstOrNull { lowerCandidate.startsWith(it) }
        var isArtistOnlyPrefix = false
        if (matchedSongPrefix != null) {
            if (matchedSongPrefix.endsWith("by ") || matchedSongPrefix.endsWith("from ")) {
                isArtistOnlyPrefix = true
            }
            candidate = candidate.substring(matchedSongPrefix.length).trim()
        }

        // 6. Filter out non-music collisions
        val lowerRemaining = candidate.lowercase()
        val nonMusicKeywords = listOf("store", "play store", "store app", "google play", "chess", "a game", "game", "games", "cards", "with me")
        if (nonMusicKeywords.contains(lowerRemaining)) {
            return null
        }

        val resolvedPlatform = platform ?: MusicPlatform.AUTO

        if (candidate.isBlank()) {
            return MusicCommand(song = "", artist = null, platform = resolvedPlatform, rawQuery = rawInput)
        }

        if (isArtistOnlyPrefix) {
            return MusicCommand(
                song = candidate,
                artist = candidate,
                platform = resolvedPlatform,
                rawQuery = rawInput
            )
        }

        // 7. Extract artist if " by [artist]" is present
        val byIndex = candidate.lastIndexOf(" by ", ignoreCase = true)
        val (finalSong, finalArtist) = if (byIndex > 0) {
            val potentialSong = candidate.substring(0, byIndex).trim()
            val potentialArtist = candidate.substring(byIndex + 4).trim()
            val lowerArtist = potentialArtist.lowercase()
            // Avoid false splits like "Stand by Me" or "Lean by Me"
            if (lowerArtist in listOf("me", "myself", "us", "you") || potentialSong.isBlank() || potentialArtist.isBlank()) {
                candidate to null
            } else {
                potentialSong to potentialArtist
            }
        } else {
            candidate to null
        }

        return MusicCommand(
            song = finalSong,
            artist = finalArtist,
            platform = resolvedPlatform,
            rawQuery = rawInput
        )
    }

    private fun extractPrefixPlatform(input: String): Pair<String, MusicPlatform>? {
        val lower = input.lowercase()
        if (lower.startsWith("on spotify play ") || lower.startsWith("in spotify play ")) {
            return input.substring("on spotify play ".length).trim() to MusicPlatform.SPOTIFY
        }
        if (lower.startsWith("on youtube music play ") || lower.startsWith("in youtube music play ") || lower.startsWith("on yt music play ")) {
            val len = if (lower.startsWith("on yt music play ")) "on yt music play ".length else "on youtube music play ".length
            return input.substring(len).trim() to MusicPlatform.YOUTUBE_MUSIC
        }
        if (lower.startsWith("on youtube play ") || lower.startsWith("in youtube play ")) {
            return input.substring("on youtube play ".length).trim() to MusicPlatform.YOUTUBE
        }
        return null
    }

    private fun extractSuffixPlatform(input: String): Pair<String, MusicPlatform?> {
        // YouTube Music must be checked before YouTube
        val ytmSuffixes = listOf(
            " on youtube music", " in youtube music", " using youtube music",
            " on yt music", " in yt music", " via youtube music"
        )
        for (suffix in ytmSuffixes) {
            if (input.endsWith(suffix, ignoreCase = true)) {
                return input.substring(0, input.length - suffix.length).trim() to MusicPlatform.YOUTUBE_MUSIC
            }
        }

        val spotifySuffixes = listOf(
            " on spotify", " in spotify", " using spotify", " with spotify", " via spotify"
        )
        for (suffix in spotifySuffixes) {
            if (input.endsWith(suffix, ignoreCase = true)) {
                return input.substring(0, input.length - suffix.length).trim() to MusicPlatform.SPOTIFY
            }
        }

        val ytSuffixes = listOf(
            " on youtube", " in youtube", " using youtube", " on yt", " in yt", " via youtube"
        )
        for (suffix in ytSuffixes) {
            if (input.endsWith(suffix, ignoreCase = true)) {
                return input.substring(0, input.length - suffix.length).trim() to MusicPlatform.YOUTUBE
            }
        }

        return input to null
    }

    /**
     * Checks whether an application is installed on the device.
     */
    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            AssistantLogger.w(TAG, "Error checking package $packageName: ${e.message}")
            false
        }
    }

    /**
     * Resolves the target platform to use based on user explicit request or configured priorities.
     */
    fun resolvePlatform(context: Context, requestedPlatform: MusicPlatform?): MusicPlatform {
        if (requestedPlatform != null && requestedPlatform != MusicPlatform.AUTO) {
            return requestedPlatform
        }

        // Priority 1: User's selected default music app from settings
        val userPreference = AppSettingsManager.defaultMusicApp.value
        when (userPreference) {
            com.example.data.DefaultMusicApp.SPOTIFY -> {
                if (isAppInstalled(context, PACKAGE_SPOTIFY)) return MusicPlatform.SPOTIFY
            }
            com.example.data.DefaultMusicApp.YOUTUBE_MUSIC -> {
                if (isAppInstalled(context, PACKAGE_YOUTUBE_MUSIC)) return MusicPlatform.YOUTUBE_MUSIC
            }
            com.example.data.DefaultMusicApp.YOUTUBE -> {
                if (isAppInstalled(context, PACKAGE_YOUTUBE)) return MusicPlatform.YOUTUBE
            }
            com.example.data.DefaultMusicApp.AUTO -> {
                // proceed to automatic detection priority
            }
        }

        // Priority 2: Spotify if installed
        if (isAppInstalled(context, PACKAGE_SPOTIFY)) {
            return MusicPlatform.SPOTIFY
        }

        // Priority 3: YouTube Music if installed
        if (isAppInstalled(context, PACKAGE_YOUTUBE_MUSIC)) {
            return MusicPlatform.YOUTUBE_MUSIC
        }

        // Priority 4: YouTube if installed
        if (isAppInstalled(context, PACKAGE_YOUTUBE)) {
            return MusicPlatform.YOUTUBE
        }

        // Priority 5: Browser fallback (default to YouTube search)
        return MusicPlatform.YOUTUBE
    }

    /**
     * Executes the music command immediately using Android deep-links / Intents.
     * Guaranteed never to crash.
     */
    fun executeMusicCommand(context: Context, command: MusicCommand): MusicExecutionResult {
        if (command.song.isBlank()) {
            return MusicExecutionResult.NeedsSongPrompt()
        }

        val platform = resolvePlatform(context, command.platform)
        val fullQuery = if (!command.artist.isNullOrBlank()) {
            "${command.song} ${command.artist}".trim()
        } else {
            command.song.trim()
        }

        val songDisplayName = if (!command.artist.isNullOrBlank()) {
            "${command.song} by ${command.artist}"
        } else {
            command.song
        }

        return when (platform) {
            MusicPlatform.SPOTIFY -> executeSpotify(context, command.song, command.artist, fullQuery, songDisplayName)
            MusicPlatform.YOUTUBE_MUSIC -> executeYouTubeMusic(context, command.song, command.artist, fullQuery, songDisplayName)
            MusicPlatform.YOUTUBE, MusicPlatform.AUTO -> executeYouTube(context, command.song, command.artist, fullQuery, songDisplayName)
        }
    }

    private fun executeSpotify(
        context: Context,
        song: String,
        artist: String?,
        fullQuery: String,
        songDisplayName: String
    ): MusicExecutionResult {
        val isInstalled = isAppInstalled(context, PACKAGE_SPOTIFY)

        if (isInstalled) {
            // 1. Try Spotify MediaStore search intent
            try {
                val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    setPackage(PACKAGE_SPOTIFY)
                    putExtra(SearchManager.QUERY, fullQuery)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, song)
                    if (!artist.isNullOrBlank()) {
                        putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (mediaIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(mediaIntent)
                    return MusicExecutionResult.Success(
                        spokenResponse = "Opening $songDisplayName on Spotify.",
                        song = song,
                        artist = artist,
                        platform = MusicPlatform.SPOTIFY,
                        isInstalledApp = true,
                        actionTaken = "OPEN_SPOTIFY"
                    )
                }
            } catch (e: Exception) {
                AssistantLogger.w(TAG, "Spotify media search intent failed: ${e.message}")
            }

            // 2. Direct Spotify search deep-link fallback
            try {
                val deepLinkUri = Uri.parse("spotify:search:" + Uri.encode(fullQuery))
                val deepLinkIntent = Intent(Intent.ACTION_VIEW, deepLinkUri).apply {
                    setPackage(PACKAGE_SPOTIFY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(deepLinkIntent)
                return MusicExecutionResult.Success(
                    spokenResponse = "Opening $songDisplayName on Spotify.",
                    song = song,
                    artist = artist,
                    platform = MusicPlatform.SPOTIFY,
                    isInstalledApp = true,
                    actionTaken = "OPEN_SPOTIFY"
                )
            } catch (e: Exception) {
                AssistantLogger.e(TAG, "Spotify deep link failed: ${e.message}", e)
            }
        }

        // 3. Fallback to Spotify Web Search in browser
        return try {
            val webUri = Uri.parse("https://open.spotify.com/search").buildUpon()
                .appendPath(fullQuery)
                .build()
            val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            val spoken = if (isInstalled) {
                "Opening $songDisplayName on Spotify."
            } else {
                "Spotify is not installed. Opening $songDisplayName on Spotify web."
            }
            MusicExecutionResult.Success(
                spokenResponse = spoken,
                song = song,
                artist = artist,
                platform = MusicPlatform.SPOTIFY,
                isInstalledApp = false,
                webFallback = true,
                actionTaken = "OPEN_SPOTIFY_WEB"
            )
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "Spotify web fallback failed: ${e.message}", e)
            MusicExecutionResult.Error(
                spokenResponse = "I couldn't open Spotify or a web browser to search for $song.",
                errorDetails = e.message ?: "Unknown browser error"
            )
        }
    }

    private fun executeYouTube(
        context: Context,
        song: String,
        artist: String?,
        fullQuery: String,
        songDisplayName: String
    ): MusicExecutionResult {
        val isInstalled = isAppInstalled(context, PACKAGE_YOUTUBE)
        val youtubeUri = Uri.parse("https://www.youtube.com/results").buildUpon()
            .appendQueryParameter("search_query", fullQuery)
            .build()

        if (isInstalled) {
            try {
                val ytIntent = Intent(Intent.ACTION_VIEW, youtubeUri).apply {
                    setPackage(PACKAGE_YOUTUBE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(ytIntent)
                return MusicExecutionResult.Success(
                    spokenResponse = "Opening $songDisplayName on YouTube.",
                    song = song,
                    artist = artist,
                    platform = MusicPlatform.YOUTUBE,
                    isInstalledApp = true,
                    actionTaken = "OPEN_YOUTUBE"
                )
            } catch (e: Exception) {
                AssistantLogger.w(TAG, "YouTube app intent failed: ${e.message}")
            }
        }

        // Fallback to browser search
        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW, youtubeUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            val spoken = if (isInstalled) {
                "Opening $songDisplayName on YouTube."
            } else {
                "YouTube is not installed. Opening $songDisplayName on YouTube in your browser."
            }
            MusicExecutionResult.Success(
                spokenResponse = spoken,
                song = song,
                artist = artist,
                platform = MusicPlatform.YOUTUBE,
                isInstalledApp = false,
                webFallback = true,
                actionTaken = "OPEN_YOUTUBE_WEB"
            )
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "YouTube web fallback failed: ${e.message}", e)
            MusicExecutionResult.Error(
                spokenResponse = "I couldn't open YouTube or a browser to search for $song.",
                errorDetails = e.message ?: "Unknown browser error"
            )
        }
    }

    private fun executeYouTubeMusic(
        context: Context,
        song: String,
        artist: String?,
        fullQuery: String,
        songDisplayName: String
    ): MusicExecutionResult {
        val isInstalled = isAppInstalled(context, PACKAGE_YOUTUBE_MUSIC)
        val ytmUri = Uri.parse("https://music.youtube.com/search").buildUpon()
            .appendQueryParameter("q", fullQuery)
            .build()

        if (isInstalled) {
            try {
                val ytmIntent = Intent(Intent.ACTION_VIEW, ytmUri).apply {
                    setPackage(PACKAGE_YOUTUBE_MUSIC)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(ytmIntent)
                return MusicExecutionResult.Success(
                    spokenResponse = "Opening $songDisplayName on YouTube Music.",
                    song = song,
                    artist = artist,
                    platform = MusicPlatform.YOUTUBE_MUSIC,
                    isInstalledApp = true,
                    actionTaken = "OPEN_YOUTUBE_MUSIC"
                )
            } catch (e: Exception) {
                AssistantLogger.w(TAG, "YouTube Music app intent failed: ${e.message}")
            }
        }

        // If YouTube Music is not installed, check YouTube app
        if (isAppInstalled(context, PACKAGE_YOUTUBE)) {
            try {
                val ytUri = Uri.parse("https://www.youtube.com/results").buildUpon()
                    .appendQueryParameter("search_query", fullQuery)
                    .build()
                val ytIntent = Intent(Intent.ACTION_VIEW, ytUri).apply {
                    setPackage(PACKAGE_YOUTUBE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(ytIntent)
                return MusicExecutionResult.Success(
                    spokenResponse = "YouTube Music is not installed. Opening $songDisplayName on YouTube.",
                    song = song,
                    artist = artist,
                    platform = MusicPlatform.YOUTUBE,
                    isInstalledApp = true,
                    actionTaken = "OPEN_YOUTUBE"
                )
            } catch (e: Exception) {
                AssistantLogger.w(TAG, "YouTube fallback failed: ${e.message}")
            }
        }

        // Browser fallback to YouTube Music web
        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW, ytmUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            MusicExecutionResult.Success(
                spokenResponse = "YouTube Music is not installed. Opening $songDisplayName on YouTube Music web.",
                song = song,
                artist = artist,
                platform = MusicPlatform.YOUTUBE_MUSIC,
                isInstalledApp = false,
                webFallback = true,
                actionTaken = "OPEN_YOUTUBE_MUSIC_WEB"
            )
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "YouTube Music browser fallback failed: ${e.message}", e)
            MusicExecutionResult.Error(
                spokenResponse = "I couldn't open YouTube Music or a web browser to search for $song.",
                errorDetails = e.message ?: "Unknown browser error"
            )
        }
    }
}
