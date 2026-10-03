package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.data.AppSettingsManager
import com.example.data.BatteryOptimizationManager
import com.example.data.ThemePreference
import com.example.data.UserMemoryManager
import com.example.network.NetworkConnectivityManager
import com.example.ui.AssistantOverlayScreen
import com.example.ui.AssistantSetupScreen
import com.example.ui.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.ChatViewModel

private enum class AppScreen {
    CHAT,
    SETUP,
    SETTINGS,
}

data class AssistantErrorEvent(val category: ErrorCategory, val message: String)

class MainActivity : ComponentActivity() {
    private val chatViewModel: ChatViewModel by viewModels()
    private var assistantInvocationToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null &&
            com.example.voice.SystemAssistantInvocation.isAssistantInvocation(intent)
        ) {
            assistantInvocationToken = 1
        }
        CrashPreventionManager.init(applicationContext)
        NetworkConnectivityManager.init(applicationContext)
        BatteryOptimizationManager.init(applicationContext)
        AppSettingsManager.init(applicationContext)
        UserMemoryManager.init(applicationContext)
        com.example.data.task.TaskManager.init(applicationContext)
        enableEdgeToEdge()

        setContent {
            val themePreference by AppSettingsManager.themePreference.collectAsState()
            val isDynamicColor by AppSettingsManager.isDynamicColor.collectAsState()

            val isDarkTheme = when (themePreference) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }

            MyApplicationTheme(
                darkTheme = isDarkTheme,
                dynamicColor = isDynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.CHAT) }
                    var previousScreen by rememberSaveable { mutableStateOf(AppScreen.CHAT) }
                    var errorEvent by remember { mutableStateOf<AssistantErrorEvent?>(null) }

                    LaunchedEffect(assistantInvocationToken) {
                        if (assistantInvocationToken > 0) currentScreen = AppScreen.CHAT
                    }

                    fun handleGlobalError(category: ErrorCategory, message: String) {
                        val event = AssistantErrorEvent(category, message)
                        errorEvent = event
                        AssistantLogger.e("MainActivity", "${event.category.name}: ${event.message}")
                    }

                    when (currentScreen) {
                        AppScreen.CHAT -> AssistantOverlayScreen(
                            viewModel = chatViewModel,
                            assistantInvocationToken = assistantInvocationToken,
                            onBack = { currentScreen = AppScreen.SETUP },
                            onOpenSettings = {
                                previousScreen = AppScreen.CHAT
                                currentScreen = AppScreen.SETTINGS
                            }
                        )
                        AppScreen.SETUP -> AssistantSetupScreen(
                            onOpenChat = { currentScreen = AppScreen.CHAT },
                            onOpenSettings = {
                                previousScreen = AppScreen.SETUP
                                currentScreen = AppScreen.SETTINGS
                            }
                        )
                        AppScreen.SETTINGS -> SettingsScreen(
                            onBack = { currentScreen = previousScreen }
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        chatViewModel.voiceManager?.pause()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (com.example.voice.SystemAssistantInvocation.isAssistantInvocation(intent)) {
            assistantInvocationToken += 1
        }
    }
}
