package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppSettingsManager
import com.example.data.DefaultMusicApp
import com.example.data.ThemePreference
import com.example.data.UserMemoryManager
import com.example.repository.ConnectionManager
import com.example.repository.ConnectionTestResult
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Psychology
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val currentTheme by AppSettingsManager.themePreference.collectAsState()
    val isDynamicColor by AppSettingsManager.isDynamicColor.collectAsState()
    val savedCustomApiKey by AppSettingsManager.customApiKey.collectAsState()

    var inputApiKey by remember(savedCustomApiKey) { mutableStateOf(savedCustomApiKey) }
    var isKeyVisible by remember { mutableStateOf(false) }
    var isTestingKey by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    var saveConfirmationMessage by remember { mutableStateOf<String?>(null) }

    val isMemoryEnabled by UserMemoryManager.isMemoryEnabled.collectAsState()
    val memories by UserMemoryManager.memories.collectAsState()
    var showMemoryDialog by remember { mutableStateOf(false) }

    val defaultMusicApp by AppSettingsManager.defaultMusicApp.collectAsState()

    val batteryLevel by com.example.data.BatteryOptimizationManager.batteryLevel.collectAsState()
    val isCharging by com.example.data.BatteryOptimizationManager.isCharging.collectAsState()
    val isLowBatteryActive by com.example.data.BatteryOptimizationManager.isLowBatteryActive.collectAsState()
    val batteryModePref by com.example.data.BatteryOptimizationManager.modePreference.collectAsState()
    val isNetworkOnline by com.example.network.NetworkConnectivityManager.isNetworkAvailable.collectAsState()
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to previous screen"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // ==========================================
            // SECTION 1: THEME & APPEARANCE PREFERENCES
            // ==========================================
            Text(
                text = "Theme & Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("theme_preferences_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Theme Mode",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Choose between Light, Dark, or System default",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Segmented Theme Mode Options
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeOptionTile(
                            title = "System",
                            icon = Icons.Rounded.BrightnessAuto,
                            isSelected = currentTheme == ThemePreference.SYSTEM,
                            onClick = { AppSettingsManager.setThemePreference(ThemePreference.SYSTEM) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("theme_option_system")
                        )

                        ThemeOptionTile(
                            title = "Light",
                            icon = Icons.Rounded.LightMode,
                            isSelected = currentTheme == ThemePreference.LIGHT,
                            onClick = { AppSettingsManager.setThemePreference(ThemePreference.LIGHT) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("theme_option_light")
                        )

                        ThemeOptionTile(
                            title = "Dark",
                            icon = Icons.Rounded.DarkMode,
                            isSelected = currentTheme == ThemePreference.DARK,
                            onClick = { AppSettingsManager.setThemePreference(ThemePreference.DARK) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("theme_option_dark")
                        )
                    }

                    // Dynamic Color (Material You) Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { AppSettingsManager.setDynamicColor(!isDynamicColor) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Dynamic Color",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Harmonize colors with system wallpaper (Android 12+)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isDynamicColor,
                            onCheckedChange = { AppSettingsManager.setDynamicColor(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("dynamic_color_switch")
                        )
                    }
                }
            }

            // ==========================================
            // SECTION 2: GEMINI API KEY CONFIGURATION
            // ==========================================
            Text(
                text = "Gemini API Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("api_key_configuration_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Google Gemini API Key",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Required to power voice commands, intent parsing & chat",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Current Active Key Status Badge
                    val activeKey = AppSettingsManager.getActiveApiKey()
                    val hasCustom = AppSettingsManager.isCustomKeyActive()

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (activeKey.isNotBlank())
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (activeKey.isNotBlank()) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                                contentDescription = null,
                                tint = if (activeKey.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    hasCustom -> "Custom API Key Active (Length: ${activeKey.length})"
                                    activeKey.isNotBlank() -> "Default Environment Key Active"
                                    else -> "No API Key configured. Please enter one below."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (activeKey.isNotBlank()) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    // API Key Input Field
                    OutlinedTextField(
                        value = inputApiKey,
                        onValueChange = {
                            inputApiKey = it
                            saveConfirmationMessage = null
                            testResult = null
                        },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("AIzaSy...") },
                        singleLine = true,
                        visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                keyboardController?.hide()
                                AppSettingsManager.setCustomApiKey(inputApiKey)
                                saveConfirmationMessage = "API Key saved successfully!"
                            }
                        ),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (inputApiKey.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            inputApiKey = ""
                                            AppSettingsManager.clearCustomApiKey()
                                            saveConfirmationMessage = "API Key cleared"
                                            testResult = null
                                        },
                                        modifier = Modifier.testTag("clear_key_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Clear,
                                            contentDescription = "Clear input",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { isKeyVisible = !isKeyVisible },
                                    modifier = Modifier.testTag("toggle_key_visibility")
                                ) {
                                    Icon(
                                        imageVector = if (isKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (isKeyVisible) "Hide key" else "Show key",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("api_key_input")
                    )

                    // Action buttons: Paste & Save
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).text?.toString() ?: ""
                                    if (text.isNotBlank()) {
                                        inputApiKey = text.trim()
                                        AppSettingsManager.setCustomApiKey(text.trim())
                                        saveConfirmationMessage = "Pasted and saved API Key!"
                                        testResult = null
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("paste_key_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Paste")
                        }

                        Button(
                            onClick = {
                                keyboardController?.hide()
                                AppSettingsManager.setCustomApiKey(inputApiKey)
                                saveConfirmationMessage = "API Key saved successfully!"
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .testTag("save_api_key_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Key")
                        }
                    }

                    saveConfirmationMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Test Connection Button
                    FilledTonalButton(
                        onClick = {
                            coroutineScope.launch {
                                keyboardController?.hide()
                                isTestingKey = true
                                testResult = null
                                // If input changed, also save it
                                if (inputApiKey != savedCustomApiKey) {
                                    AppSettingsManager.setCustomApiKey(inputApiKey)
                                }
                                val result = ConnectionManager.testConnection(inputApiKey.ifBlank { null })
                                testResult = result
                                isTestingKey = false
                            }
                        },
                        enabled = !isTestingKey,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("test_api_key_button")
                    ) {
                        if (isTestingKey) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Verifying Key with Gemini...")
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test API Key Connection")
                        }
                    }

                    // Test result feedback banner
                    testResult?.let { res ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (res.isSuccess)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("test_result_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = if (res.isSuccess) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                                    contentDescription = null,
                                    tint = if (res.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (res.isSuccess) "Connection Verified!" else "Connection Failed",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (res.isSuccess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = res.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (res.isSuccess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }

                    // Instructions & External link to Google AI Studio
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("get_api_key_link_button")
                    ) {
                        Text("Get a Free Gemini API Key")
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = "Open Google AI Studio",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // ==========================================
            // SECTION 3: VOICE & CONVERSATION INTERACTION
            // ==========================================
            val isTtsEnabled by AppSettingsManager.isTtsEnabled.collectAsState()
            val isAutoLanguageEnabled by AppSettingsManager.isAutoLanguageEnabled.collectAsState()

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Voice Interaction",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Voice capture starts only after a microphone tap or Android assistant invocation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Text-to-Speech Output Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Spoken Audio Responses (TTS)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Read assistant answers aloud via Android Text-to-Speech",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isTtsEnabled,
                            onCheckedChange = { AppSettingsManager.setTtsEnabled(it) },
                            modifier = Modifier.testTag("tts_enabled_switch")
                        )
                    }

                    // Multilingual Auto-Detection Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Multilingual Auto-Detection",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Automatically detect spoken language and respond in kind",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isAutoLanguageEnabled,
                            onCheckedChange = { AppSettingsManager.setAutoLanguageEnabled(it) },
                            modifier = Modifier.testTag("auto_language_switch")
                        )
                    }
                }
            }

            // ==========================================
            // SECTION 3.5: DEFAULT MUSIC PLAYER
            // ==========================================
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("music_settings_card")
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Default Music Player",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Choose player for voice commands like 'Play [song]'",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DefaultMusicApp.values().forEach { appOption ->
                            val isSelected = defaultMusicApp == appOption
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { AppSettingsManager.setDefaultMusicApp(appOption) }
                                    .testTag("music_pref_${appOption.name}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = when (appOption) {
                                                DefaultMusicApp.AUTO -> "Auto Detect"
                                                DefaultMusicApp.SPOTIFY -> "Spotify"
                                                DefaultMusicApp.YOUTUBE_MUSIC -> "YouTube Music"
                                                DefaultMusicApp.YOUTUBE -> "YouTube"
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = when (appOption) {
                                                DefaultMusicApp.AUTO -> "Uses Spotify if installed, else YouTube Music or YouTube"
                                                DefaultMusicApp.SPOTIFY -> "Direct MediaStore playback intent with web fallback"
                                                DefaultMusicApp.YOUTUBE_MUSIC -> "Direct YouTube Music search and playback"
                                                DefaultMusicApp.YOUTUBE -> "Native YouTube video playback or search"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { AppSettingsManager.setDefaultMusicApp(appOption) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 4: ACCESSIBILITY & SYSTEM ACTIONS
            // ==========================================
            val isAccessibilityEnabled = com.example.AssistantService.isAccessibilitySettingsEnabled(context)

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            tint = if (isAccessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Accessibility & System Actions",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isAccessibilityEnabled) "Service is Active" else "Service is Disabled (Optional)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isAccessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = "Allows MJ to execute global navigation commands like 'Go Home', 'Go Back', or 'Open Notifications' hands-free.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("configure_accessibility_button")
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isAccessibilityEnabled) "Manage Accessibility Settings" else "Enable in Accessibility Settings")
                    }
                }
            }

            // ==========================================
            // SECTION 5: VISION & PRIVACY GUARANTEES
            // ==========================================
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vision_privacy_card")
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Vision & Image Privacy",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Strict zero-retention policy for camera snapshots and screenshots",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Zero storage badge
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Zero Image Storage Mandate",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "All images are processed transiently in device memory only. No photos or screen captures are ever saved to disk, gallery, or remote storage.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }

                    // Vision Feature Highlights
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.CameraAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Camera Snapshot: Real-time visual scene explanation",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Screenshot,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "On-Screen Capture: Describe app interfaces & screen contents",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.DocumentScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Text Extraction: Live OCR for signs, titles, and documents",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.QrCodeScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "QR & Barcode: Instant link and code decoding with plain explanation",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 6: USER-APPROVED MEMORY & PREFERENCES
            // ==========================================
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("memory_settings_card")
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "User-Approved Memory",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Preferences remembered only when you ask",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Master Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Personalized Memory",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isMemoryEnabled)
                                    "${memories.size} preference${if (memories.size != 1) "s" else ""} remembered"
                                else
                                    "Personalized memory is disabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isMemoryEnabled,
                            onCheckedChange = { UserMemoryManager.setMemoryEnabled(it) },
                            modifier = Modifier.testTag("memory_master_switch")
                        )
                    }

                    // Privacy summary banner
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Zero passive tracking. Never stores sensitive credentials (passwords, PINs, cards) without explicit consent. Stored locally on this device.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                lineHeight = 14.sp
                            )
                        }
                    }

                    // Action buttons: "Manage Memories" & "Forget All"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showMemoryDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("manage_memories_button")
                        ) {
                            Icon(imageVector = Icons.Rounded.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Manage Memories")
                        }

                        if (memories.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { UserMemoryManager.clearAllMemories() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("clear_memories_settings_button")
                            ) {
                                Icon(imageVector = Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Forget All")
                            }
                        }
                    }

                    Text(
                        text = "Voice Controls: You can say \"What do you remember?\" or \"Forget [item]\" anytime to MJ.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // ==========================================
            // SECTION 7: BATTERY & OFFLINE RESILIENCE
            // ==========================================
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth().testTag("battery_settings_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Battery & Offline Modes",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Power throttling & offline fault-tolerance",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isLowBatteryActive) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "🔋 $batteryLevel%${if (isCharging) " ⚡" else ""}",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Low battery mode status banner
                    if (isLowBatteryActive) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚡ Low battery optimization active. Heavy visual effects are throttled to preserve power.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Text(
                        text = "Low Battery Mode Policy",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        com.example.data.BatteryModePreference.values().forEach { mode ->
                            val isSelected = batteryModePref == mode
                            val label = when (mode) {
                                com.example.data.BatteryModePreference.AUTOMATIC -> "Auto (<=20%)"
                                com.example.data.BatteryModePreference.ALWAYS_ON -> "Always On"
                                com.example.data.BatteryModePreference.OFF -> "Off"
                            }
                            Surface(
                                onClick = { com.example.data.BatteryOptimizationManager.setModePreference(mode) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.weight(1f).height(44.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    // Network status indicator
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (isNetworkOnline) "🌐 Network: Online" else "⚠️ Network: Offline",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isNetworkOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "Offline Fallback Engine: Active (Local actions & memories ready)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SECTION 8: SYSTEM DIAGNOSTICS & LOGS
            // ==========================================
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth().testTag("diagnostics_settings_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "System Diagnostics & Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Real-time production logs, telemetry ring buffer, and crash-recovery reporting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = { showDiagnosticsDialog = true },
                        modifier = Modifier.fillMaxWidth().testTag("open_diagnostics_button")
                    ) {
                        Icon(imageVector = Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View System Diagnostics & Live Logs")
                    }
                }
            }

            // ==========================================
            // SECTION 9: APP INFO
            // ==========================================
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "About MJ Assistant",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "Version 1.0 • Powered by Google Gemini 3.8 Flash • Secure local storage for all keys and preferences.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        if (showMemoryDialog) {
            MemoryManagementDialog(onDismiss = { showMemoryDialog = false })
        }

        if (showDiagnosticsDialog) {
            DiagnosticsLogsDialog(onDismiss = { showDiagnosticsDialog = false })
        }
    }
}

@Composable
private fun ThemeOptionTile(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surface,
        border = if (isSelected)
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.height(72.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
