package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
                    var currentScreen by remember { mutableStateOf("chat") }
                    var previousScreen by remember { mutableStateOf("chat") }
                    var errorEvent by remember { mutableStateOf<AssistantErrorEvent?>(null) }

                    LaunchedEffect(assistantInvocationToken) {
                        if (assistantInvocationToken > 0) currentScreen = "chat"
                    }

                    fun handleGlobalError(category: ErrorCategory, message: String) {
                        errorEvent = AssistantErrorEvent(category, message)
                        val formattedError = "${errorEvent?.category?.name}: ${errorEvent?.message}"
                        AssistantLogger.e("MainActivity", formattedError)
                    }

                    when (currentScreen) {
                        "chat" -> AssistantOverlayScreen(
                            viewModel = chatViewModel,
                            assistantInvocationToken = assistantInvocationToken,
                            onBack = { currentScreen = "setup" },
                            onOpenSettings = {
                                previousScreen = "chat"
                                currentScreen = "settings"
                            }
                        )
                        "setup" -> AssistantSetupScreen(
                            onOpenChat = { currentScreen = "chat" },
                            onOpenSettings = {
                                previousScreen = "setup"
                                currentScreen = "settings"
                            }
                        )
                        "settings" -> SettingsScreen(
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
