package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.AssistantLogger
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

enum class DefaultMusicApp(val label: String) {
    AUTO("Auto (Spotify > YouTube Music > YouTube)"),
    SPOTIFY("Spotify"),
    YOUTUBE_MUSIC("YouTube Music"),
    YOUTUBE("YouTube")
}

object AppSettingsManager {
    private const val PREFS_NAME = "mj_assistant_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_CUSTOM_API_KEY = "custom_gemini_api_key"
    private const val KEY_CONTINUOUS_CONVERSATION = "continuous_conversation"
    private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
    private const val KEY_TTS_ENABLED = "tts_enabled"
    private const val KEY_AUTO_LANGUAGE_ENABLED = "auto_language_enabled"
    private const val KEY_DEFAULT_MUSIC_APP = "default_music_app"

    private lateinit var prefs: SharedPreferences
    private lateinit var secureSecretStore: SecureSecretStore

    private val _themePreference = MutableStateFlow(ThemePreference.SYSTEM)
    val themePreference: StateFlow<ThemePreference> = _themePreference.asStateFlow()

    private val _defaultMusicApp = MutableStateFlow(DefaultMusicApp.AUTO)
    val defaultMusicApp: StateFlow<DefaultMusicApp> = _defaultMusicApp.asStateFlow()

    private val _isDynamicColor = MutableStateFlow(true)
    val isDynamicColor: StateFlow<Boolean> = _isDynamicColor.asStateFlow()

    private val _customApiKey = MutableStateFlow("")
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    private val _isContinuousConversation = MutableStateFlow(true)
    val isContinuousConversation: StateFlow<Boolean> = _isContinuousConversation.asStateFlow()

    private val _isWakeWordEnabled = MutableStateFlow(true)
    val isWakeWordEnabled: StateFlow<Boolean> = _isWakeWordEnabled.asStateFlow()

    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private val _isAutoLanguageEnabled = MutableStateFlow(true)
    val isAutoLanguageEnabled: StateFlow<Boolean> = _isAutoLanguageEnabled.asStateFlow()

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            secureSecretStore = SecureSecretStore(context)
            loadSettings()
        }
    }

    private fun loadSettings() {
        val savedTheme = prefs.getString(KEY_THEME_MODE, ThemePreference.SYSTEM.name) ?: ThemePreference.SYSTEM.name
        _themePreference.value = try {
            ThemePreference.valueOf(savedTheme)
        } catch (e: Exception) {
            ThemePreference.SYSTEM
        }

        _isDynamicColor.value = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        val legacyPlaintextKey = prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
        if (legacyPlaintextKey.isNotBlank()) {
            secureSecretStore.put(KEY_CUSTOM_API_KEY, legacyPlaintextKey)
            secureSecretStore.remove(KEY_CUSTOM_API_KEY)
            prefs.edit().remove(KEY_CUSTOM_API_KEY).apply()
        }
        _customApiKey.value = secureSecretStore.get(KEY_CUSTOM_API_KEY)
        _isContinuousConversation.value = prefs.getBoolean(KEY_CONTINUOUS_CONVERSATION, true)
        _isWakeWordEnabled.value = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, true)
        _isTtsEnabled.value = prefs.getBoolean(KEY_TTS_ENABLED, true)
        _isAutoLanguageEnabled.value = prefs.getBoolean(KEY_AUTO_LANGUAGE_ENABLED, true)

        val savedMusicApp = prefs.getString(KEY_DEFAULT_MUSIC_APP, DefaultMusicApp.AUTO.name) ?: DefaultMusicApp.AUTO.name
        _defaultMusicApp.value = try {
            DefaultMusicApp.valueOf(savedMusicApp)
        } catch (e: Exception) {
            DefaultMusicApp.AUTO
        }
    }

    fun setDefaultMusicApp(app: DefaultMusicApp) {
        _defaultMusicApp.value = app
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_DEFAULT_MUSIC_APP, app.name).apply()
            AssistantLogger.i("Settings", "Default music app set to ${app.name}")
        }
    }

    fun setContinuousConversation(enabled: Boolean) {
        _isContinuousConversation.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_CONTINUOUS_CONVERSATION, enabled).apply()
            AssistantLogger.i("Settings", "Continuous conversation set to $enabled")
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        _isWakeWordEnabled.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
            AssistantLogger.i("Settings", "Wake word detection set to $enabled")
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        _isTtsEnabled.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()
            AssistantLogger.i("Settings", "TTS output set to $enabled")
        }
    }

    fun setAutoLanguageEnabled(enabled: Boolean) {
        _isAutoLanguageEnabled.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_AUTO_LANGUAGE_ENABLED, enabled).apply()
            AssistantLogger.i("Settings", "Auto language detection set to $enabled")
        }
    }

    fun setThemePreference(theme: ThemePreference) {
        _themePreference.value = theme
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_THEME_MODE, theme.name).apply()
            AssistantLogger.i("Settings", "Theme preference updated to $theme")
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        _isDynamicColor.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
            AssistantLogger.i("Settings", "Dynamic color updated to $enabled")
        }
    }

    fun setCustomApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        _customApiKey.value = trimmed
        if (::prefs.isInitialized) {
            secureSecretStore.put(KEY_CUSTOM_API_KEY, trimmed)
            prefs.edit().remove(KEY_CUSTOM_API_KEY).apply()
            AssistantLogger.i("Settings", "Custom API key updated")
        }
    }

    fun clearCustomApiKey() {
        _customApiKey.value = ""
        if (::prefs.isInitialized) {
            prefs.edit().remove(KEY_CUSTOM_API_KEY).apply()
            AssistantLogger.i("Settings", "Custom API key cleared")
        }
    }

    /**
     * Resolves the active API key to use.
     * Prioritizes custom key entered by the user; falls back to BuildConfig.GEMINI_API_KEY.
     */
    fun getActiveApiKey(): String {
        val custom = _customApiKey.value.trim()
        if (custom.isNotEmpty()) {
            return custom
        }
        return try {
            val key = BuildConfig.GEMINI_API_KEY.trim()
            if (key == "DEFAULT_API_KEY" || key == "YOUR_GEMINI_API_KEY" || key == "\"\"") "" else key
        } catch (e: Throwable) {
            ""
        }
    }

    fun isCustomKeyActive(): Boolean {
        return _customApiKey.value.isNotBlank()
    }

    fun hasAnyApiKey(): Boolean {
        val key = getActiveApiKey()
        return key.isNotBlank() && key != "DEFAULT_API_KEY" && key != "YOUR_GEMINI_API_KEY"
    }
}
