package com.example.action

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

enum class ActionName {
    OPEN_APP, SEARCH_WEB, OPEN_URL, PLAY_MUSIC, CALL, SEND_SMS, OPEN_SETTINGS,
    NAVIGATE, SET_ALARM, SET_TIMER, GO_HOME, GO_BACK, OPEN_NOTIFICATIONS,
    RECENT_APPS, TOGGLE_WIFI, TOGGLE_BLUETOOTH, SET_BRIGHTNESS, OPEN_QUICK_SETTINGS,
    SEND_WHATSAPP, CREATE_TASK, COMPLETE_TASK, REMEMBER_PREFERENCE, SHOW_MEMORIES,
    FORGET_MEMORY, CHAT
}

sealed interface ActionParameters {
    data class OpenApp(val packageName: String) : ActionParameters
    data class SearchWeb(val query: String) : ActionParameters
    data class OpenUrl(val url: String) : ActionParameters
    data class PlayMusic(val query: String) : ActionParameters
    data class Call(val target: String) : ActionParameters
    data class SendSms(val targetAndMessage: String) : ActionParameters
    data object OpenSettings : ActionParameters
    data class Navigate(val destination: String) : ActionParameters
    data class SetAlarm(val hour: Int) : ActionParameters
    data class SetTimer(val minutes: Int) : ActionParameters
    data object GoHome : ActionParameters
    data object GoBack : ActionParameters
    data object OpenNotifications : ActionParameters
    data object RecentApps : ActionParameters
    data class ToggleWifi(val state: String) : ActionParameters
    data class ToggleBluetooth(val state: String) : ActionParameters
    data class SetBrightness(val percent: Int) : ActionParameters
    data object OpenQuickSettings : ActionParameters
    data class SendWhatsApp(val targetAndMessage: String) : ActionParameters
    data class CreateTask(val value: String) : ActionParameters
    data class CompleteTask(val title: String) : ActionParameters
    data class RememberPreference(val value: String) : ActionParameters
    data object ShowMemories : ActionParameters
    data class ForgetMemory(val value: String) : ActionParameters
    data object Chat : ActionParameters
}

data class ActionRequest<P : ActionParameters>(
    val id: String,
    val name: ActionName,
    val parameters: P
)

enum class VerificationStatus { NOT_STARTED, STARTED, VERIFIED, FAILED }

enum class ActionErrorCode {
    POLICY_BLOCKED, UNSUPPORTED, PERMISSION_REQUIRED, PRECONDITION_FAILED,
    EXECUTION_FAILED, VERIFICATION_FAILED, CANCELLED, INVALID_PARAMETERS
}

data class ActionError(
    val code: ActionErrorCode,
    val message: String,
    val cause: Throwable? = null
)

sealed interface ActionResult {
    val actionId: String
    val action: ActionName
    val verification: VerificationStatus
    val error: ActionError?

    data class Success(
        override val actionId: String,
        override val action: ActionName,
        val message: String,
        override val verification: VerificationStatus = VerificationStatus.VERIFIED
    ) : ActionResult { override val error: ActionError? = null }

    data class Started(
        override val actionId: String,
        override val action: ActionName,
        val message: String,
        override val verification: VerificationStatus = VerificationStatus.STARTED
    ) : ActionResult { override val error: ActionError? = null }

    data class Failure(
        override val actionId: String,
        override val action: ActionName,
        override val error: ActionError,
        override val verification: VerificationStatus = VerificationStatus.FAILED
    ) : ActionResult

    data class Blocked(
        override val actionId: String,
        override val action: ActionName,
        override val error: ActionError
    ) : ActionResult { override val verification = VerificationStatus.FAILED }

    data class PermissionRequired(
        override val actionId: String,
        override val action: ActionName,
        override val error: ActionError
    ) : ActionResult { override val verification = VerificationStatus.FAILED }

    data class Unsupported(
        override val actionId: String,
        override val action: ActionName,
        override val error: ActionError
    ) : ActionResult { override val verification = VerificationStatus.FAILED }

    data class TimedOut(
        override val actionId: String,
        override val action: ActionName,
        override val error: ActionError
    ) : ActionResult { override val verification = VerificationStatus.FAILED }

    data class Cancelled(
        override val actionId: String,
        override val action: ActionName,
        val message: String = "Action cancelled."
    ) : ActionResult {
        override val verification = VerificationStatus.FAILED
        override val error = ActionError(ActionErrorCode.CANCELLED, message)
    }
}

enum class AccessibilityUse { NONE, LIMITED_GLOBAL_NAVIGATION }

data class ActionPolicy(
    val minApi: Int = 24,
    val requiredPermissions: Set<String> = emptySet(),
    val requiredSystemRole: String? = null,
    val accessibilityUse: AccessibilityUse = AccessibilityUse.NONE,
    val allowed: Boolean = true
)

data class ActionContext(
    val accessibilityAllowed: Boolean = false,
    val requiredRoleHeld: (String) -> Boolean = { false }
)

interface AssistantTool<P : ActionParameters> {
    val name: ActionName
    val policy: ActionPolicy
    fun precondition(context: android.content.Context, request: ActionRequest<P>): ActionError? = null
    suspend fun execute(context: android.content.Context, request: ActionRequest<P>): ActionResult
    suspend fun verify(context: android.content.Context, request: ActionRequest<P>, started: ActionResult): ActionResult = started
}

class ActionCancellationRegistry {
    private val jobs = ConcurrentHashMap<String, Job>()

    fun register(actionId: String, job: Job) { jobs[actionId] = job }
    fun cancel(actionId: String): Boolean =
        jobs.remove(actionId)?.let {
            it.cancel(CancellationException("Cancelled by user"))
            true
        } ?: false

    fun cancelAll() {
        jobs.values.forEach { it.cancel(CancellationException("Cancelled by user")) }
        jobs.clear()
    }

    fun clear(actionId: String) { jobs.remove(actionId) }
    fun isRunning(actionId: String): Boolean = jobs[actionId]?.isActive == true
}
