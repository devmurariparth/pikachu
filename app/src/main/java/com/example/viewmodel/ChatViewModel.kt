package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ActionPlanner
import com.example.AssistantLogger
import com.example.IntentManager
import com.example.OfflineActionHandler
import com.example.WhatsAppManager
import com.example.data.BatteryOptimizationManager
import com.example.data.ForgetMemoryResult
import com.example.data.SaveMemoryResult
import com.example.data.UserMemoryManager
import com.example.data.task.TaskItem
import com.example.data.task.TaskManager
import com.example.data.task.TaskVoiceResult
import com.example.device.DeviceControlManager
import com.example.network.NetworkConnectivityManager
import com.example.voice.VoiceInteractionManager
import com.example.voice.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSpoken: Boolean = false,
    val language: String = "en",
    val thumbnailBitmap: android.graphics.Bitmap? = null,
    val visionTaskLabel: String? = null,
    val isMemoryAction: Boolean = false,
    val isSensitiveWarning: Boolean = false,
    val isOfflineAction: Boolean = false
)

enum class MessageSender {
    USER,
    AI
}

data class PendingSensitiveMemory(
    val candidateText: String,
    val sensitiveType: String,
    val warning: String
)

class ChatViewModel : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                sender = MessageSender.AI,
                text = "Hello! I'm MJ, your intelligent personal assistant. You can speak to me by saying \"Hey MJ\", tapping the microphone, or typing your request."
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _lastPlannedAction = MutableStateFlow<String?>(null)
    val lastPlannedAction: StateFlow<String?> = _lastPlannedAction.asStateFlow()

    private val _pendingSensitiveMemory = MutableStateFlow<PendingSensitiveMemory?>(null)
    val pendingSensitiveMemory: StateFlow<PendingSensitiveMemory?> = _pendingSensitiveMemory.asStateFlow()

    val isNetworkAvailable: StateFlow<Boolean> = NetworkConnectivityManager.isNetworkAvailable
    val isLowBatteryActive: StateFlow<Boolean> = BatteryOptimizationManager.isLowBatteryActive
    val batteryLevel: StateFlow<Int> = BatteryOptimizationManager.batteryLevel

    var voiceManager: VoiceInteractionManager? = null
        private set

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _liveTranscription = MutableStateFlow("")
    val liveTranscription: StateFlow<String> = _liveTranscription.asStateFlow()

    private val _audioRmsLevel = MutableStateFlow(0f)
    val audioRmsLevel: StateFlow<Float> = _audioRmsLevel.asStateFlow()

    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()

    init {
        viewModelScope.launch {
            TaskManager.tasksFlow?.collect {
                _tasks.value = it
            }
        }
    }

    fun toggleTaskComplete(task: TaskItem) {
        viewModelScope.launch {
            TaskManager.setTaskCompleted(task.id, !task.isCompleted)
        }
    }

    fun deleteTask(task: TaskItem) {
        viewModelScope.launch {
            TaskManager.deleteTask(task)
        }
    }

    fun createTask(title: String, reminderTimeMillis: Long? = null) {
        viewModelScope.launch {
            TaskManager.createTask(title = title, reminderTimeMillis = reminderTimeMillis)
        }
    }

    fun initVoiceManager(context: Context) {
        TaskManager.init(context)
        viewModelScope.launch {
            TaskManager.tasksFlow?.collect {
                _tasks.value = it
            }
        }
        if (voiceManager == null) {
            voiceManager = VoiceInteractionManager(
                context = context.applicationContext,
                onCommandRecognized = { command, wasWakeWord ->
                    AssistantLogger.i("ChatViewModel", "Voice command received (wakeWord=$wasWakeWord): '$command'")
                    sendMessage(context, command, isSpokenInput = true)
                }
            ).also { vm ->
                viewModelScope.launch {
                    vm.voiceState.collect { _voiceState.value = it }
                }
                viewModelScope.launch {
                    vm.liveTranscription.collect { _liveTranscription.value = it }
                }
                viewModelScope.launch {
                    vm.audioRmsLevel.collect { _audioRmsLevel.value = it }
                }
            }
        }
    }

    fun startListening(isWakeWordMode: Boolean = false) {
        voiceManager?.startListening(isWakeWordMode)
    }

    fun stopListening() {
        voiceManager?.stopListening()
    }

    fun interruptSpeech() {
        voiceManager?.interrupt()
    }

    fun confirmPendingSensitiveMemory(consentGiven: Boolean) {
        val pending = _pendingSensitiveMemory.value ?: return
        _pendingSensitiveMemory.value = null

        if (consentGiven) {
            val result = UserMemoryManager.rememberPreference(
                rawText = pending.candidateText,
                allowSensitiveIfConsented = true
            )
            val msgText = when (result) {
                is SaveMemoryResult.Success -> "With your explicit consent, I have securely remembered: \"${result.item.text}\"."
                is SaveMemoryResult.AlreadyExists -> "I already have that preference saved in your memory."
                is SaveMemoryResult.Disabled -> "Memory is currently disabled in Settings."
                else -> "Your sensitive preference has been remembered with your explicit consent."
            }
            _messages.value = _messages.value + ChatMessage(
                sender = MessageSender.AI,
                text = msgText,
                isMemoryAction = true
            )
            voiceManager?.speak(msgText)
        } else {
            val msgText = "Discarded. The sensitive data was not saved to memory."
            _messages.value = _messages.value + ChatMessage(
                sender = MessageSender.AI,
                text = msgText,
                isMemoryAction = true
            )
            voiceManager?.speak(msgText)
        }
    }

    private fun isWhatDoYouRememberQuery(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower == "what do you remember" ||
               lower == "what do you remember?" ||
               lower.startsWith("what do you remember about") ||
               lower == "what do you know about me" ||
               lower == "what do you know about me?" ||
               lower == "show memories" ||
               lower == "show my memories" ||
               lower == "list memories" ||
               lower == "list my memories" ||
               lower == "what memories do you have" ||
               lower == "what are my memories" ||
               lower == "tell me what you remember"
    }

    private fun isForgetQuery(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.startsWith("forget ") ||
               lower == "forget" ||
               lower.startsWith("delete memory ") ||
               lower.startsWith("remove memory ") ||
               lower.startsWith("clear memories") ||
               lower.startsWith("clear all memories") ||
               lower == "forget everything" ||
               lower == "forget all"
    }

    private fun isRememberQuery(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.startsWith("remember that ") ||
               lower.startsWith("remember to ") ||
               lower.startsWith("remember my ") ||
               lower.startsWith("remember me as ") ||
               (lower.startsWith("remember ") && !lower.startsWith("remember?")) ||
               lower.startsWith("please remember ") ||
               lower.startsWith("note that ") ||
               lower.startsWith("keep in mind that ") ||
               lower.startsWith("don't forget that ") ||
               lower.startsWith("dont forget that ") ||
               lower.startsWith("save preference ")
    }

    fun sendMessage(context: Context, userText: String, isSpokenInput: Boolean = false) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty() || _isProcessing.value) return

        val userMessage = ChatMessage(
            sender = MessageSender.USER,
            text = trimmed,
            isSpoken = isSpokenInput
        )
        _messages.value = _messages.value + userMessage

        // =========================================================================
        // FAST DIRECT MEMORY CONTROLS (Zero network latency, 100% reliable)
        // =========================================================================
        if (isWhatDoYouRememberQuery(trimmed)) {
            val summary = UserMemoryManager.getMemoriesSummaryText()
            _messages.value = _messages.value + ChatMessage(
                sender = MessageSender.AI,
                text = summary,
                isMemoryAction = true
            )
            voiceManager?.speak(summary)
            return
        }

        if (isForgetQuery(trimmed)) {
            val lower = trimmed.lowercase().trim()
            val target = when {
                lower == "forget all" || lower == "forget everything" || lower == "clear memories" || lower == "clear all memories" -> "all"
                lower.startsWith("forget ") -> trimmed.substring("forget ".length).trim()
                lower.startsWith("delete memory ") -> trimmed.substring("delete memory ".length).trim()
                lower.startsWith("remove memory ") -> trimmed.substring("remove memory ".length).trim()
                else -> trimmed
            }

            val result = UserMemoryManager.forgetMemory(target)
            val replyText = when (result) {
                is ForgetMemoryResult.ItemRemoved -> result.message
                is ForgetMemoryResult.AllCleared -> result.message
                is ForgetMemoryResult.NotFound -> result.message
                is ForgetMemoryResult.EmptyMemory -> "I don't have any remembered preferences to forget."
            }

            _messages.value = _messages.value + ChatMessage(
                sender = MessageSender.AI,
                text = replyText,
                isMemoryAction = true
            )
            voiceManager?.speak(replyText)
            return
        }

        if (isRememberQuery(trimmed)) {
            val result = UserMemoryManager.rememberPreference(rawText = trimmed)
            when (result) {
                is SaveMemoryResult.RequiresSensitiveConsent -> {
                    _pendingSensitiveMemory.value = PendingSensitiveMemory(
                        candidateText = result.candidateText,
                        sensitiveType = result.sensitiveType,
                        warning = result.warning
                    )
                    val warningText = "⚠️ Sensitive Data Warning: You asked me to remember information that appears to contain ${result.sensitiveType}.\n\nFor your security and privacy, MJ never stores sensitive credentials without your explicit consent. Would you like to confirm saving this?"
                    _messages.value = _messages.value + ChatMessage(
                        sender = MessageSender.AI,
                        text = warningText,
                        isMemoryAction = true,
                        isSensitiveWarning = true
                    )
                    voiceManager?.speak("This appears to contain sensitive information. Storing sensitive data requires your explicit consent.")
                }
                is SaveMemoryResult.Success -> {
                    _messages.value = _messages.value + ChatMessage(
                        sender = MessageSender.AI,
                        text = "${result.message}\nYou can say 'What do you remember?' or 'Forget this' anytime.",
                        isMemoryAction = true
                    )
                    voiceManager?.speak(result.message)
                }
                is SaveMemoryResult.AlreadyExists -> {
                    val msg = "I already have that saved in my memory: \"${result.existing.text}\"."
                    _messages.value = _messages.value + ChatMessage(
                        sender = MessageSender.AI,
                        text = msg,
                        isMemoryAction = true
                    )
                    voiceManager?.speak(msg)
                }
                is SaveMemoryResult.Disabled -> {
                    _messages.value = _messages.value + ChatMessage(
                        sender = MessageSender.AI,
                        text = result.message,
                        isMemoryAction = true
                    )
                    voiceManager?.speak(result.message)
                }
                is SaveMemoryResult.Empty -> {
                    val msg = "What would you like me to remember?"
                    _messages.value = _messages.value + ChatMessage(
                        sender = MessageSender.AI,
                        text = msg
                    )
                    voiceManager?.speak(msg)
                }
            }
            return
        }

        // =========================================================================
        // FAST DIRECT WHATSAPP SHARING INTENT
        // =========================================================================
        val whatsAppMatch = WhatsAppManager.parseWhatsAppVoiceCommand(trimmed)
        if (whatsAppMatch != null) {
            val res = WhatsAppManager.sendWhatsApp(context, whatsAppMatch.first, whatsAppMatch.second)
            _messages.value = _messages.value + ChatMessage(
                sender = MessageSender.AI,
                text = res.spokenMessage
            )
            voiceManager?.speak(res.spokenMessage)
            return
        }

        // =========================================================================
        // FAST DIRECT SMS MESSAGING INTENT
        // =========================================================================
        val smsMatch = com.example.sms.SmsActionManager.parseSmsCommand(trimmed)
        if (smsMatch != null) {
            val res = com.example.sms.SmsActionManager.sendSms(context, smsMatch.first, smsMatch.second)
            _messages.value = _messages.value + ChatMessage(
                sender = MessageSender.AI,
                text = res.spokenMessage
            )
            voiceManager?.speak(res.spokenMessage)
            return
        }

        // =========================================================================
        // FAST DIRECT TASKS & REMINDERS (Room Database + WorkManager)
        // =========================================================================
        if (trimmed.startsWith("remind me", ignoreCase = true) ||
            trimmed.startsWith("add task", ignoreCase = true) ||
            trimmed.startsWith("create task", ignoreCase = true) ||
            trimmed.startsWith("new task", ignoreCase = true) ||
            trimmed.startsWith("show tasks", ignoreCase = true) ||
            trimmed.startsWith("show my tasks", ignoreCase = true) ||
            trimmed.startsWith("what are my tasks", ignoreCase = true) ||
            trimmed.startsWith("list tasks", ignoreCase = true) ||
            trimmed.startsWith("complete task", ignoreCase = true) ||
            trimmed.startsWith("finish task", ignoreCase = true) ||
            trimmed.startsWith("delete task", ignoreCase = true)
        ) {
            viewModelScope.launch {
                val taskRes = TaskManager.handleVoiceQuery(trimmed)
                if (taskRes !is TaskVoiceResult.NotHandled) {
                    val speech = when (taskRes) {
                        is TaskVoiceResult.Created -> taskRes.message
                        is TaskVoiceResult.Completed -> taskRes.message
                        is TaskVoiceResult.Deleted -> taskRes.message
                        is TaskVoiceResult.Listed -> taskRes.message
                        else -> "Task updated."
                    }
                    _messages.value = _messages.value + ChatMessage(
                        sender = MessageSender.AI,
                        text = speech
                    )
                    voiceManager?.speak(speech)
                }
            }
            return
        }

        // =========================================================================
        // FAST DIRECT DEVICE CONTROLS (Accessibility Service & System Controls)
        // =========================================================================
        val lower = trimmed.lowercase().trim()
        if (lower.contains("wifi") || lower.contains("wi-fi") ||
            lower.contains("bluetooth") ||
            lower.contains("brightness") ||
            lower == "quick settings" || lower == "open quick settings"
        ) {
            val devResult = when {
                lower.contains("wifi") || lower.contains("wi-fi") -> {
                    val on = if (lower.contains("off") || lower.contains("disable")) false else true
                    DeviceControlManager.toggleWifi(context, on)
                }
                lower.contains("bluetooth") -> {
                    val on = if (lower.contains("off") || lower.contains("disable")) false else true
                    DeviceControlManager.toggleBluetooth(context, on)
                }
                lower.contains("brightness") -> {
                    val percent = "(\\d{1,3})".toRegex().find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 80
                    DeviceControlManager.setBrightness(context, percent)
                }
                else -> DeviceControlManager.openQuickSettings()
            }
            _messages.value = _messages.value + ChatMessage(
                sender = MessageSender.AI,
                text = devResult.spokenMessage
            )
            voiceManager?.speak(devResult.spokenMessage)
            return
        }

        // =========================================================================
        // GENERAL ASSISTANT / GEMINI ACTION PLANNING WITH OFFLINE FALLBACKS
        // =========================================================================
        _isProcessing.value = true
        voiceManager?.setThinkingState()

        // 1. Instant offline execution if network is completely unavailable
        if (!NetworkConnectivityManager.isNetworkAvailable.value) {
            viewModelScope.launch {
                val offlineResult = OfflineActionHandler.handleOfflineCommand(context, trimmed)
                val replyText = if (offlineResult.handled) {
                    "${offlineResult.spokenResponse}\n(Executed in Offline Mode)"
                } else {
                    offlineResult.spokenResponse
                }
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.AI,
                    text = replyText,
                    isOfflineAction = true
                )
                voiceManager?.speak(offlineResult.spokenResponse)
                _isProcessing.value = false
            }
            return
        }

        viewModelScope.launch {
            try {
                val planResult = ActionPlanner.plan(trimmed)
                if (planResult.isSuccess) {
                    val plan = planResult.getOrThrow()
                    _lastPlannedAction.value = plan.action

                    val isMem = plan.action == "SHOW_MEMORIES" || plan.action == "FORGET_MEMORY" || plan.action == "REMEMBER_PREFERENCE"

                    // Execute system intent or navigation
                    val execResult = IntentManager.executeAction(context, plan.action, plan.payload)
                    val responseText = if (execResult.isSuccess) {
                        plan.speechResponse
                    } else {
                        "${plan.speechResponse}\n(Note: ${execResult.exceptionOrNull()?.message ?: "Action failed"})"
                    }

                    val aiMessage = ChatMessage(
                        sender = MessageSender.AI,
                        text = responseText,
                        language = plan.language,
                        isMemoryAction = isMem
                    )
                    _messages.value = _messages.value + aiMessage

                    // Speak response with automatic language adaptation
                    voiceManager?.speak(plan.speechResponse, plan.language)
                } else {
                    val error = planResult.exceptionOrNull()
                    AssistantLogger.w("ChatViewModel", "AI service failure: ${error?.message}. Attempting offline fallback.")

                    // Try offline fallback handler
                    val offlineFallback = OfflineActionHandler.handleOfflineCommand(context, trimmed)
                    if (offlineFallback.handled) {
                        val fallbackResponse = "${offlineFallback.spokenResponse}\n(Executed via offline fallback)"
                        _messages.value = _messages.value + ChatMessage(
                            sender = MessageSender.AI,
                            text = fallbackResponse,
                            isOfflineAction = true
                        )
                        voiceManager?.speak(offlineFallback.spokenResponse)
                    } else {
                        val friendlyError = "I couldn't reach the AI service (${error?.message ?: "Connection issue"}). You can still launch apps, adjust device settings, set alarms, or ask 'What do you remember?' offline."
                        _messages.value = _messages.value + ChatMessage(
                            sender = MessageSender.AI,
                            text = friendlyError,
                            isOfflineAction = true
                        )
                        voiceManager?.speak("I'm unable to connect to the AI service right now. Local device controls and memories are still available.")
                    }
                }
            } catch (e: Exception) {
                AssistantLogger.e("ChatViewModel", "Unexpected error processing message", e)
                val offlineFallback = OfflineActionHandler.handleOfflineCommand(context, trimmed)
                if (offlineFallback.handled) {
                    _messages.value = _messages.value + ChatMessage(
                        sender = MessageSender.AI,
                        text = "${offlineFallback.spokenResponse}\n(Recovered via offline fallback)",
                        isOfflineAction = true
                    )
                    voiceManager?.speak(offlineFallback.spokenResponse)
                } else {
                    val fallbackText = "Sorry, something went wrong. Offline controls like opening apps and viewing preferences remain available."
                    _messages.value = _messages.value + ChatMessage(
                        sender = MessageSender.AI,
                        text = fallbackText,
                        isOfflineAction = true
                    )
                    voiceManager?.speak(fallbackText)
                }
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * Executes vision analysis with explicit user consent.
     * The input bitmap is kept strictly in transient memory during processing and never saved to persistent storage.
     */
    fun analyzeVision(
        context: Context,
        bitmap: android.graphics.Bitmap,
        taskType: com.example.vision.VisionTaskType,
        customPrompt: String? = null,
        sourceLabel: String = "Captured Image"
    ) {
        if (_isProcessing.value) return

        val userPromptSummary = when (taskType) {
            com.example.vision.VisionTaskType.DESCRIBE -> "Describe what is visible in this $sourceLabel"
            com.example.vision.VisionTaskType.EXTRACT_TEXT -> "Extract text and explain this $sourceLabel"
            com.example.vision.VisionTaskType.READ_QR -> "Read QR code or barcodes in this $sourceLabel"
            com.example.vision.VisionTaskType.CUSTOM -> customPrompt ?: "Explain what's in this $sourceLabel"
        }

        // Add user visual input to the conversation history
        val userMsg = ChatMessage(
            sender = MessageSender.USER,
            text = userPromptSummary,
            thumbnailBitmap = bitmap,
            visionTaskLabel = taskType.label
        )
        _messages.value = _messages.value + userMsg
        _isProcessing.value = true
        voiceManager?.setThinkingState()

        viewModelScope.launch {
            try {
                val result = com.example.vision.VisionAnalyzer.analyzeImage(
                    bitmap = bitmap,
                    taskType = taskType,
                    customPrompt = customPrompt
                )

                if (result.isSuccess) {
                    val visionData = result.getOrThrow()
                    val aiMsg = ChatMessage(
                        sender = MessageSender.AI,
                        text = visionData.explanation,
                        language = "en",
                        visionTaskLabel = taskType.label
                    )
                    _messages.value = _messages.value + aiMsg
                    voiceManager?.speak(visionData.explanation, "en")
                } else {
                    val error = result.exceptionOrNull()
                    val errorMsg = "Vision analysis could not be completed: ${error?.message ?: "Please check your network connection and API key."}"
                    _messages.value = _messages.value + ChatMessage(
                        sender = MessageSender.AI,
                        text = errorMsg
                    )
                    voiceManager?.speak("I had trouble analyzing that image. Please verify your connection or Gemini API key.")
                }
            } catch (e: Exception) {
                AssistantLogger.e("ChatViewModel", "Error in vision analysis", e)
                val fallback = "Sorry, an unexpected error occurred while analyzing the image."
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.AI,
                    text = fallback
                )
                voiceManager?.speak(fallback)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager?.destroy()
        voiceManager = null
    }
}
