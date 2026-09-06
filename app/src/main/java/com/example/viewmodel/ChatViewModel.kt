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
    val isOfflineAction: Boolean = false,
    val callOptions: List<com.example.contact.CallContactOption>? = null,
    val musicAction: com.example.music.MusicActionInfo? = null
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

    // Call Action & Contact Disambiguation State
    private val _callDisambiguation = MutableStateFlow<com.example.contact.CallDisambiguation?>(null)
    val callDisambiguation: StateFlow<com.example.contact.CallDisambiguation?> = _callDisambiguation.asStateFlow()

    private val _pendingCallOption = MutableStateFlow<com.example.contact.CallContactOption?>(null)
    val pendingCallOption: StateFlow<com.example.contact.CallContactOption?> = _pendingCallOption.asStateFlow()

    private val _pendingContactQuery = MutableStateFlow<String?>(null)

    private val _requestCallPermissionEvent = MutableStateFlow(false)
    val requestCallPermissionEvent: StateFlow<Boolean> = _requestCallPermissionEvent.asStateFlow()

    private val _requestContactsPermissionEvent = MutableStateFlow(false)
    val requestContactsPermissionEvent: StateFlow<Boolean> = _requestContactsPermissionEvent.asStateFlow()

    fun consumeCallPermissionEvent() {
        _requestCallPermissionEvent.value = false
    }

    fun consumeContactsPermissionEvent() {
        _requestContactsPermissionEvent.value = false
    }

    fun onCallPermissionGranted(context: Context) {
        val pending = _pendingCallOption.value
        _pendingCallOption.value = null
        if (pending != null) {
            val result = com.example.contact.CallActionManager.executeCall(context, pending.displayName, pending.phoneNumber)
            if (result is com.example.contact.CallExecutionResult.Failed) {
                val errorMsg = "Could not place call: ${result.reason}"
                _messages.value = _messages.value + ChatMessage(sender = MessageSender.AI, text = errorMsg)
                voiceManager?.speak(errorMsg)
            }
        }
    }

    fun onCallPermissionDenied(context: Context) {
        val pending = _pendingCallOption.value
        _pendingCallOption.value = null
        if (pending != null) {
            val msg = "Call permission was not granted. Opening phone dialer for ${pending.displayName}."
            _messages.value = _messages.value + ChatMessage(sender = MessageSender.AI, text = msg)
            voiceManager?.speak(msg)
            com.example.contact.CallActionManager.openDialerFallback(context, pending.phoneNumber)
        }
    }

    fun onContactsPermissionGranted(context: Context) {
        val query = _pendingContactQuery.value
        _pendingContactQuery.value = null
        if (!query.isNullOrBlank()) {
            handleDirectCallCommand(context, query)
        }
    }

    fun selectCallOption(option: com.example.contact.CallContactOption, context: Context) {
        _callDisambiguation.value = null
        placeCallToResolvedOption(context, option)
    }

    fun dismissCallDisambiguation() {
        _callDisambiguation.value = null
    }

    fun handleDirectCallCommand(context: Context, rawTarget: String) {
        val target = rawTarget.trim()
        if (target.isEmpty()) {
            val prompt = "Who would you like me to call?"
            _messages.value = _messages.value + ChatMessage(
                sender = MessageSender.AI,
                text = prompt
            )
            voiceManager?.speak(prompt)
            return
        }

        // Direct numeric phone number check
        val digitsOnly = target.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length >= 3 && target.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }) {
            placeCallToResolvedOption(
                context,
                com.example.contact.CallContactOption(
                    displayName = target,
                    phoneNumber = digitsOnly,
                    typeLabel = "Direct"
                )
            )
            return
        }

        // Check contacts permission
        if (!com.example.contact.ContactsManager.hasContactsPermission(context)) {
            _pendingContactQuery.value = target
            _requestContactsPermissionEvent.value = true
            val msg = "Contact permission is needed to look up $target in your contacts."
            _messages.value = _messages.value + ChatMessage(
                sender = MessageSender.AI,
                text = msg
            )
            voiceManager?.speak(msg)
            return
        }

        when (val outcome = com.example.contact.ContactsManager.lookupContact(context, target)) {
            is com.example.contact.ContactLookupOutcome.SingleMatch -> {
                val option = com.example.contact.CallContactOption(
                    displayName = outcome.entry.displayName,
                    phoneNumber = outcome.entry.phoneNumber,
                    typeLabel = outcome.entry.typeLabel
                )
                placeCallToResolvedOption(context, option)
            }
            is com.example.contact.ContactLookupOutcome.DirectNumber -> {
                val option = com.example.contact.CallContactOption(
                    displayName = outcome.phoneNumber,
                    phoneNumber = outcome.phoneNumber,
                    typeLabel = "Direct"
                )
                placeCallToResolvedOption(context, option)
            }
            is com.example.contact.ContactLookupOutcome.MultipleContacts -> {
                val options = outcome.contacts.map {
                    com.example.contact.CallContactOption(
                        displayName = it.displayName,
                        phoneNumber = it.phoneNumber,
                        typeLabel = it.typeLabel
                    )
                }
                val promptText = "I found multiple contacts named $target. Which one should I call?"
                val disambiguation = com.example.contact.CallDisambiguation(
                    title = "Multiple Contacts Found",
                    prompt = promptText,
                    options = options
                )
                _callDisambiguation.value = disambiguation
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.AI,
                    text = promptText,
                    callOptions = options
                )
                voiceManager?.speak(promptText)
            }
            is com.example.contact.ContactLookupOutcome.MultipleNumbersForContact -> {
                val options = outcome.numbers.map {
                    com.example.contact.CallContactOption(
                        displayName = it.displayName,
                        phoneNumber = it.phoneNumber,
                        typeLabel = it.typeLabel
                    )
                }
                val distinctLabels = outcome.numbers.map { it.typeLabel.lowercase() }.distinct()
                val labelsSpoken = if (distinctLabels.size == 2) {
                    "${distinctLabels[0]} or ${distinctLabels[1]}"
                } else {
                    distinctLabels.joinToString(", or ")
                }
                val promptText = "Which number should I call: $labelsSpoken?"
                val disambiguation = com.example.contact.CallDisambiguation(
                    title = "Select Phone Number",
                    prompt = promptText,
                    options = options
                )
                _callDisambiguation.value = disambiguation
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.AI,
                    text = promptText,
                    callOptions = options
                )
                voiceManager?.speak(promptText)
            }
            is com.example.contact.ContactLookupOutcome.ContactHasNoNumber -> {
                val msg = "That contact doesn't have a phone number."
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.AI,
                    text = msg
                )
                voiceManager?.speak(msg)
            }
            is com.example.contact.ContactLookupOutcome.NotFound -> {
                val msg = "I couldn't find that contact."
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.AI,
                    text = msg
                )
                voiceManager?.speak(msg)
            }
            is com.example.contact.ContactLookupOutcome.PermissionRequired -> {
                _pendingContactQuery.value = target
                _requestContactsPermissionEvent.value = true
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.AI,
                    text = outcome.message
                )
                voiceManager?.speak(outcome.message)
            }
            is com.example.contact.ContactLookupOutcome.Error -> {
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.AI,
                    text = outcome.message
                )
                voiceManager?.speak(outcome.message)
            }
        }
    }

    fun placeCallToResolvedOption(context: Context, option: com.example.contact.CallContactOption) {
        val speech = "Calling ${option.displayName}."
        _messages.value = _messages.value + ChatMessage(
            sender = MessageSender.AI,
            text = speech
        )
        voiceManager?.speak(speech)

        if (com.example.contact.CallActionManager.hasCallPermission(context)) {
            val result = com.example.contact.CallActionManager.executeCall(context, option.displayName, option.phoneNumber)
            when (result) {
                is com.example.contact.CallExecutionResult.Started -> {
                    AssistantLogger.i("ChatViewModel", "Call placed to ${option.displayName}")
                }
                is com.example.contact.CallExecutionResult.DialerOpened -> {
                    AssistantLogger.w("ChatViewModel", "Dialer fallback used: ${result.reason}")
                }
                is com.example.contact.CallExecutionResult.Failed -> {
                    val errorMsg = "Could not place call: ${result.reason}"
                    _messages.value = _messages.value + ChatMessage(sender = MessageSender.AI, text = errorMsg)
                    voiceManager?.speak(errorMsg)
                }
                is com.example.contact.CallExecutionResult.PermissionRequired -> {
                    _pendingCallOption.value = option
                    _requestCallPermissionEvent.value = true
                }
            }
        } else {
            _pendingCallOption.value = option
            _requestCallPermissionEvent.value = true
        }
    }

    /**
     * Executes a music playback / search command locally with zero AI latency.
     */
    fun handleDirectMusicCommand(context: Context, command: com.example.music.MusicCommand) {
        AssistantLogger.i("ChatViewModel", "Executing direct music command: song='${command.song}', artist='${command.artist}', platform=${command.platform}")
        val result = com.example.music.MusicActionManager.executeMusicCommand(context, command)
        when (result) {
            is com.example.music.MusicExecutionResult.Success -> {
                val musicInfo = com.example.music.MusicActionInfo(
                    song = result.song,
                    artist = result.artist,
                    platform = result.platform,
                    isInstalled = result.isInstalledApp,
                    webFallback = result.webFallback
                )
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.AI,
                    text = result.spokenResponse,
                    musicAction = musicInfo
                )
                voiceManager?.speak(result.spokenResponse)
            }
            is com.example.music.MusicExecutionResult.NeedsSongPrompt -> {
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.AI,
                    text = result.spokenResponse
                )
                voiceManager?.speak(result.spokenResponse)
            }
            is com.example.music.MusicExecutionResult.Error -> {
                _messages.value = _messages.value + ChatMessage(
                    sender = MessageSender.AI,
                    text = result.spokenResponse
                )
                voiceManager?.speak(result.spokenResponse)
            }
        }
    }

    /**
     * Re-opens or switches a song to a specific platform (e.g. from the chat action buttons).
     */
    fun playMusicOnPlatform(context: Context, song: String, artist: String?, platform: com.example.music.MusicPlatform) {
        val cmd = com.example.music.MusicCommand(song = song, artist = artist, platform = platform)
        handleDirectMusicCommand(context, cmd)
    }

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
        // ACTIVE CALL DISAMBIGUATION RESPONSE (Select by Contact Name or Phone Type)
        // =========================================================================
        val currentDisambiguation = _callDisambiguation.value
        if (currentDisambiguation != null) {
            val lower = trimmed.lowercase().trim()
            if (lower == "cancel" || lower == "stop" || lower == "nevermind" || lower == "dismiss" || lower == "no") {
                _callDisambiguation.value = null
                val reply = "Call cancelled."
                _messages.value = _messages.value + ChatMessage(sender = MessageSender.AI, text = reply)
                voiceManager?.speak(reply)
                return
            }

            val matchedOption = currentDisambiguation.options.firstOrNull { opt ->
                lower == opt.displayName.lowercase() ||
                lower == opt.typeLabel.lowercase() ||
                lower.contains(opt.typeLabel.lowercase()) ||
                lower.contains(opt.displayName.lowercase()) ||
                opt.displayName.lowercase().contains(lower)
            }
            if (matchedOption != null) {
                _callDisambiguation.value = null
                placeCallToResolvedOption(context, matchedOption)
                return
            }
        }

        // =========================================================================
        // FAST DIRECT PHONE CALL INTENT (Deterministic, Local, Zero AI Latency)
        // =========================================================================
        val callTarget = com.example.contact.CallActionManager.parseCallCommand(trimmed)
        if (callTarget != null) {
            handleDirectCallCommand(context, callTarget)
            return
        }

        // =========================================================================
        // FAST DIRECT MUSIC / SONG PLAY INTENT (Deterministic, Local, Zero AI Latency)
        // =========================================================================
        val musicCommand = com.example.music.MusicActionManager.parseMusicCommand(trimmed)
        if (musicCommand != null) {
            handleDirectMusicCommand(context, musicCommand)
            return
        }

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
