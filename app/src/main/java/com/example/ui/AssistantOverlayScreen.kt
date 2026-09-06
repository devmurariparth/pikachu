package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.AssistantService
import com.example.data.AppSettingsManager
import com.example.data.UserMemoryManager
import com.example.viewmodel.ChatMessage
import com.example.viewmodel.ChatViewModel
import com.example.viewmodel.MessageSender
import com.example.vision.VisionAnalyzer
import com.example.vision.VisionTaskType
import com.example.voice.VoiceState
import kotlinx.coroutines.launch

/**
 * Premium MJ AI Assistant flagship interface.
 *
 * Design Architecture:
 * - Upper-center prominent 3D MJ Voice Orb with organic reactive illumination.
 * - Live speech recognition typography streaming seamlessly beneath the orb.
 * - Spacious, minimal conversation stream with high-contrast, clean typography.
 * - Minimalist floating glass pill input dock.
 * - Zero visual clutter; elegant dark & light modes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantOverlayScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Core Assistant State
    val messages by viewModel.messages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val liveTranscription by viewModel.liveTranscription.collectAsState()
    val audioRmsLevel by viewModel.audioRmsLevel.collectAsState()
    val isWakeWordEnabled by AppSettingsManager.isWakeWordEnabled.collectAsState()

    // System Telemetry & Memory
    val isNetworkAvailable by viewModel.isNetworkAvailable.collectAsState()
    val isLowBatteryActive by viewModel.isLowBatteryActive.collectAsState()
    val batteryLevel by viewModel.batteryLevel.collectAsState()
    val memories by UserMemoryManager.memories.collectAsState()
    val pendingSensitiveMemory by viewModel.pendingSensitiveMemory.collectAsState()

    // Dialog & Sheet States
    var showMemoryDialog by remember { mutableStateOf(false) }
    var showMediaSheet by remember { mutableStateOf(false) }
    val mediaSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Vision Capture State
    var pendingVisionBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingVisionSourceTitle by remember { mutableStateOf("Camera Snapshot") }
    var showVisionConsentDialog by remember { mutableStateOf(false) }

    // Microphone Permission
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            viewModel.startListening()
        }
    }

    // Camera Permission & Launcher
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val takePicturePreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            pendingVisionBitmap = bitmap
            pendingVisionSourceTitle = "Camera Snapshot"
            showVisionConsentDialog = true
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            takePicturePreviewLauncher.launch(null)
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Camera permission is needed to analyze images.")
            }
        }
    }

    // Photo Picker Launcher (Zero-permission Android Photo Picker)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = VisionAnalyzer.decodeUriToBitmap(inputStream)
                if (bitmap != null) {
                    pendingVisionBitmap = bitmap
                    pendingVisionSourceTitle = "Selected Image"
                    showVisionConsentDialog = true
                }
            } catch (e: Exception) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Could not load image.")
                }
            }
        }
    }

    // Screen Capture trigger
    fun triggerScreenCapture() {
        val service = AssistantService.instance
        if (service != null) {
            service.captureScreenAsync(
                onSuccess = { screenBitmap ->
                    pendingVisionBitmap = screenBitmap
                    pendingVisionSourceTitle = "On-Screen Capture"
                    showVisionConsentDialog = true
                },
                onError = { error ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Screen capture: $error")
                    }
                }
            )
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Enable MJ Assistant in Accessibility settings to capture on-screen content.")
            }
        }
    }

    // Voice manager init
    LaunchedEffect(Unit) {
        viewModel.initVoiceManager(context)
    }

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll on new message
    LaunchedEffect(messages.size, isProcessing) {
        val targetIndex = if (isProcessing) messages.size else (messages.size - 1).coerceAtLeast(0)
        if (targetIndex >= 0) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    // Dynamic background brush: deep obsidian with luminous subtle aura in dark mode, clean porcelain in light mode
    val backgroundBrush = if (isDark) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF060911),
                Color(0xFF090D18),
                Color(0xFF0B1120)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF8FAFC),
                Color(0xFFF1F5F9)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. MINIMAL LUXURY TOP BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Go Back",
                        tint = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Center Identity Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isDark) Color(0x221E293B) else Color(0x110F172A),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = if (isDark) Color(0x3338BDF8) else Color(0x224F46E5)
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Live Status Indicator Dot
                        val statusDotColor = when {
                            !isNetworkAvailable -> Color(0xFFF59E0B) // Amber
                            voiceState is VoiceState.Listening -> Color(0xFF06B6D4) // Cyan
                            voiceState is VoiceState.Speaking -> Color(0xFF10B981) // Emerald
                            isProcessing -> Color(0xFF8B5CF6) // Violet
                            else -> Color(0xFF10B981) // Green / Online
                        }
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "MJ",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            ),
                            color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                        )
                    }
                }

                // Right Utility Controls: User Memory & Settings
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showMemoryDialog = true },
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("open_memory_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Psychology,
                            contentDescription = "User Memory Preferences",
                            tint = if (memories.isNotEmpty()) {
                                if (isDark) Color(0xFF38BDF8) else Color(0xFF4F46E5)
                            } else {
                                if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 2. MAIN MJ VOICE ORB HERO (Upper-Center Focus)
            Spacer(modifier = Modifier.height(10.dp))

            MjVoiceOrb(
                voiceState = voiceState,
                audioRmsLevel = audioRmsLevel,
                isProcessing = isProcessing,
                onClick = {
                    if (!hasMicPermission) {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (voiceState is VoiceState.Speaking) {
                        // Instant interruption (barge-in)
                        viewModel.interruptSpeech()
                        viewModel.startListening()
                    } else if (voiceState is VoiceState.Listening) {
                        viewModel.stopListening()
                    } else {
                        viewModel.startListening()
                    }
                },
                size = 175.dp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 3. LIVE SPEECH RECOGNITION & STATUS TEXT BENEATH ORB
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .heightIn(min = 36.dp),
                contentAlignment = Alignment.Center
            ) {
                if (liveTranscription.isNotBlank()) {
                    // Streaming live speech recognition
                    Text(
                        text = "\"$liveTranscription\"",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Medium
                        ),
                        color = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    val statusText = when {
                        !hasMicPermission -> "Microphone permission required • Tap orb to allow"
                        voiceState is VoiceState.Listening -> {
                            val listeningState = voiceState as VoiceState.Listening
                            if (listeningState.isWakeWordActive) "Listening for 'Hey MJ'..." else "Listening to you..."
                        }
                        voiceState is VoiceState.Speaking -> "MJ is speaking • Tap orb to pause"
                        voiceState is VoiceState.Thinking || isProcessing -> "MJ is thinking..."
                        isLowBatteryActive -> "Low Battery Mode • Tap orb to speak"
                        !isNetworkAvailable -> "Offline Mode • Local commands active"
                        else -> if (isWakeWordEnabled) "Say 'Hey MJ' or tap orb" else "Tap orb to speak"
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Normal,
                            letterSpacing = 0.3.sp
                        ),
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Subtle Status Banners (Offline / Low Battery)
            if (!isNetworkAvailable) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp, bottom = 4.dp)
                        .testTag("offline_banner")
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDark) Color(0x33EF4444) else Color(0x19EF4444),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, Color(0x66EF4444))
                    ) {
                        Text(
                            text = "Offline Mode • Apps, alarms & memories work locally",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = if (isDark) Color(0xFFFCA5A5) else Color(0xFFDC2626),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            if (isLowBatteryActive) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp, bottom = 4.dp)
                        .testTag("low_battery_banner")
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDark) Color(0x33F59E0B) else Color(0x19F59E0B),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, Color(0x66F59E0B))
                    ) {
                        Text(
                            text = "Low Battery Mode ($batteryLevel%) • Power saver active",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = if (isDark) Color(0xFFFCD34D) else Color(0xFFD97706),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. CONVERSATION AREA (Clean, Minimalist Stream)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("chat_messages_list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // When empty, display 3 subtle flagship suggestion prompts
                if (messages.isEmpty() && !isProcessing) {
                    item(key = "empty_state_prompts") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val flagshipPrompts = listOf(
                                "What can you do?",
                                "What do you remember?",
                                "Describe what's on my screen"
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("suggested_prompts_row"),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                            ) {
                                flagshipPrompts.forEach { prompt ->
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (isDark) Color(0x221E293B) else Color(0x110F172A),
                                        border = androidx.compose.foundation.BorderStroke(
                                            width = 1.dp,
                                            color = if (isDark) Color(0x3338BDF8) else Color(0x224F46E5)
                                        ),
                                        modifier = Modifier
                                            .clickable {
                                                if (prompt == "Describe what's on my screen") {
                                                    triggerScreenCapture()
                                                } else {
                                                    viewModel.sendMessage(context, prompt)
                                                }
                                            }
                                            .testTag("chip_$prompt")
                                    ) {
                                        Text(
                                            text = prompt,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                                            color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                items(messages, key = { it.id }) { message ->
                    PremiumChatMessageItem(message = message, isDark = isDark)
                }

                if (isProcessing) {
                    item(key = "shimmer_thinking_bubble") {
                        ShimmerChatResponseBubble(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                        )
                    }
                }
            }

            // 5. MINIMALIST FLOATING INPUT DOCK
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = if (isDark) Color(0xDD111827) else Color(0xEEFFFFFF),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (isDark) Color(0x3338BDF8) else Color(0x224F46E5)
                ),
                shadowElevation = if (isDark) 0.dp else 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attachment Trigger Button (Opens camera/gallery/screen options)
                    IconButton(
                        onClick = { showMediaSheet = true },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .testTag("vision_camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddPhotoAlternate,
                            contentDescription = "Visual Input Options",
                            tint = if (isDark) Color(0xFF38BDF8) else Color(0xFF4F46E5),
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    // Clean Text Field
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = {
                            Text(
                                text = "Ask MJ anything...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_input_field"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A),
                            unfocusedTextColor = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (textInput.isNotBlank() && !isProcessing) {
                                    val query = textInput
                                    textInput = ""
                                    keyboardController?.hide()
                                    viewModel.sendMessage(context, query)
                                }
                            }
                        )
                    )

                    // Send or Mic Button
                    if (textInput.isNotBlank()) {
                        IconButton(
                            onClick = {
                                if (!isProcessing) {
                                    val query = textInput
                                    textInput = ""
                                    keyboardController?.hide()
                                    viewModel.sendMessage(context, query)
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0xFF38BDF8) else Color(0xFF4F46E5))
                                .testTag("send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = "Send Message",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        // Quick Mic Trigger
                        val isListening = voiceState is VoiceState.Listening
                        val isSpeaking = voiceState is VoiceState.Speaking

                        IconButton(
                            onClick = {
                                if (!hasMicPermission) {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else if (isSpeaking) {
                                    viewModel.interruptSpeech()
                                    viewModel.startListening()
                                } else if (isListening) {
                                    viewModel.stopListening()
                                } else {
                                    viewModel.startListening()
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isListening -> Color(0x3306B6D4)
                                        isSpeaking -> Color(0x3310B981)
                                        else -> if (isDark) Color(0x221E293B) else Color(0x110F172A)
                                    }
                                )
                                .testTag("voice_mic_button")
                        ) {
                            Icon(
                                imageVector = when {
                                    isListening -> Icons.Rounded.Stop
                                    isSpeaking -> Icons.Rounded.Stop
                                    else -> Icons.Rounded.Mic
                                },
                                contentDescription = if (isListening) "Stop Listening" else "Start Voice Input",
                                tint = when {
                                    isListening -> Color(0xFF06B6D4)
                                    isSpeaking -> Color(0xFF10B981)
                                    else -> if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // Notification Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 76.dp)
        )

        // 6. MINIMALIST VISUAL ACTIONS BOTTOM SHEET (Camera, Screen, Gallery)
        if (showMediaSheet) {
            ModalBottomSheet(
                onDismissRequest = { showMediaSheet = false },
                sheetState = mediaSheetState,
                containerColor = if (isDark) Color(0xFF111827) else Color.White,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Visual Intelligence",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                    )
                    Text(
                        text = "MJ analyzes photos, screens, and documents with explicit consent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Option 1: Take Camera Photo
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showMediaSheet = false
                                if (hasCameraPermission) {
                                    takePicturePreviewLauncher.launch(null)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp)
                            .testTag("vision_camera_option"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0x3338BDF8) else Color(0x194F46E5)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CameraAlt,
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF38BDF8) else Color(0xFF4F46E5),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Take Camera Photo",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                            )
                            Text(
                                text = "Capture documents, objects, or text",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            )
                        }
                    }

                    // Option 2: Analyze On-Screen Content
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showMediaSheet = false
                                triggerScreenCapture()
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp)
                            .testTag("vision_screenshot_button"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0x3306B6D4) else Color(0x1906B6D4)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Screenshot,
                                contentDescription = null,
                                tint = Color(0xFF06B6D4),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Analyze On-Screen Content",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                            )
                            Text(
                                text = "Ask questions about what's currently on your screen",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            )
                        }
                    }

                    // Option 3: Choose from Gallery
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showMediaSheet = false
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp)
                            .testTag("vision_gallery_button"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0x338B5CF6) else Color(0x198B5CF6)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddPhotoAlternate,
                                contentDescription = null,
                                tint = Color(0xFF8B5CF6),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Choose from Photos",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                            )
                            Text(
                                text = "Select an existing picture or screenshot",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // 7. EXPLICIT CONSENT DIALOGS (100% Preserved)
        if (showVisionConsentDialog && pendingVisionBitmap != null) {
            VisionConsentDialog(
                bitmap = pendingVisionBitmap!!,
                sourceTitle = pendingVisionSourceTitle,
                isProcessing = isProcessing,
                onConfirm = { taskType, customQuestion ->
                    val bitmapToAnalyze = pendingVisionBitmap!!
                    val source = pendingVisionSourceTitle
                    showVisionConsentDialog = false
                    pendingVisionBitmap = null

                    viewModel.analyzeVision(
                        context = context,
                        bitmap = bitmapToAnalyze,
                        taskType = taskType,
                        customPrompt = customQuestion,
                        sourceLabel = source
                    )
                },
                onDismiss = {
                    showVisionConsentDialog = false
                    pendingVisionBitmap = null
                }
            )
        }

        if (showMemoryDialog) {
            MemoryManagementDialog(
                onDismiss = { showMemoryDialog = false }
            )
        }

        pendingSensitiveMemory?.let { pending ->
            SensitiveMemoryConsentDialog(
                pending = pending,
                onConfirm = { consent ->
                    viewModel.confirmPendingSensitiveMemory(consent)
                }
            )
        }
    }
}

/**
 * Clean, modern chat message item adhering to the premium MJ aesthetic.
 */
@Composable
fun PremiumChatMessageItem(
    message: ChatMessage,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val isUser = message.sender == MessageSender.USER

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(if (isUser) "user_message_${message.id}" else "ai_message_${message.id}"),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = if (isUser) 20.dp else 6.dp,
                topEnd = if (isUser) 6.dp else 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    if (isDark) Color(0xFF1E293B) else Color(0xFF4F46E5)
                } else {
                    if (isDark) Color(0xFF111827) else Color(0xFFFFFFFF)
                }
            ),
            border = if (!isUser) {
                androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = if (isDark) Color(0x3338BDF8) else Color(0x194F46E5)
                )
            } else null,
            elevation = CardDefaults.cardElevation(defaultElevation = if (isUser) 0.dp else 2.dp),
            modifier = Modifier.fillMaxWidth(0.86f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Image preview if present
                message.thumbnailBitmap?.let { bmp ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0xFF090D16) else Color(0xFFF1F5F9))
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Analyzed image preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Minimalist Badges
                message.visionTaskLabel?.let { label ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isUser) Color.White.copy(alpha = 0.2f) else Color(0x1F38BDF8),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when (label) {
                                    VisionTaskType.EXTRACT_TEXT.label -> Icons.Rounded.DocumentScanner
                                    VisionTaskType.READ_QR.label -> Icons.Rounded.QrCodeScanner
                                    else -> Icons.Rounded.Visibility
                                },
                                contentDescription = null,
                                tint = if (isUser) Color.White else Color(0xFF0284C7),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = if (isUser) Color.White else Color(0xFF0284C7)
                            )
                        }
                    }
                }

                if (message.isMemoryAction) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isUser) Color.White.copy(alpha = 0.2f) else Color(0x1F8B5CF6),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Psychology,
                                contentDescription = null,
                                tint = if (isUser) Color.White else Color(0xFF8B5CF6),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Memory & Preferences",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = if (isUser) Color.White else Color(0xFF8B5CF6)
                            )
                        }
                    }
                }

                if (message.isOfflineAction) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isUser) Color.White.copy(alpha = 0.2f) else Color(0x1F10B981),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Security,
                                contentDescription = null,
                                tint = if (isUser) Color.White else Color(0xFF10B981),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Local Action",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = if (isUser) Color.White else Color(0xFF10B981)
                            )
                        }
                    }
                }

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = if (isUser) {
                        Color.White
                    } else {
                        if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A)
                    }
                )

                if (!isUser && (message.isSpoken || message.language.isNotBlank())) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (message.isSpoken) {
                            Icon(
                                imageVector = Icons.Rounded.VolumeUp,
                                contentDescription = "Spoken by MJ",
                                tint = if (isDark) Color(0xFF38BDF8) else Color(0xFF4F46E5),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        if (message.language.isNotBlank() && message.language != "unknown") {
                            Text(
                                text = message.language.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = if (isDark) Color(0xFF38BDF8) else Color(0xFF4F46E5),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
