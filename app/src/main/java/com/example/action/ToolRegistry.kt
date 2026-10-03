package com.example.action

import android.content.Context
import com.example.AssistantException
import com.example.ErrorCategory
import com.example.IntentManager
import kotlin.reflect.KClass

/** Central, allow-listed registry of typed tools backed by existing MJ capabilities. */
class ToolRegistry(tools: Collection<AssistantTool<out ActionParameters>>) {
    private val byName = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "Tool names must be unique." }
    }

    fun find(name: ActionName): AssistantTool<out ActionParameters>? = byName[name]

    fun accepts(name: ActionName, parameters: ActionParameters): Boolean =
        byName[name]?.parameterType?.isInstance(parameters) == true

    fun isRetrySafe(name: ActionName): Boolean = byName[name]?.retrySafe == true

    fun registeredNames(): Set<ActionName> = byName.keys

    companion object {
        fun existingCapabilities(): ToolRegistry = ToolRegistry(buildList {
            add(legacy(ActionName.OPEN_APP, ActionParameters.OpenApp::class, retrySafe = true))
            add(legacy(ActionName.SEARCH_WEB, ActionParameters.SearchWeb::class, retrySafe = true))
            add(legacy(ActionName.OPEN_URL, ActionParameters.OpenUrl::class, retrySafe = true))
            add(legacy(ActionName.PLAY_MUSIC, ActionParameters.PlayMusic::class))
            add(legacy(ActionName.PLAY_VIDEO, ActionParameters.PlayVideo::class))
            add(legacy(ActionName.CALL, ActionParameters.Call::class))
            add(legacy(ActionName.SEND_SMS, ActionParameters.SendSms::class))
            add(legacy(ActionName.OPEN_SETTINGS, ActionParameters.OpenSettings::class, retrySafe = true))
            add(legacy(ActionName.NAVIGATE, ActionParameters.Navigate::class))
            add(legacy(ActionName.SET_ALARM, ActionParameters.SetAlarm::class))
            add(legacy(ActionName.SET_TIMER, ActionParameters.SetTimer::class))
            add(legacy(ActionName.GO_HOME, ActionParameters.GoHome::class, retrySafe = true))
            add(legacy(ActionName.GO_BACK, ActionParameters.GoBack::class))
            add(legacy(ActionName.OPEN_NOTIFICATIONS, ActionParameters.OpenNotifications::class))
            add(legacy(ActionName.RECENT_APPS, ActionParameters.RecentApps::class))
            add(legacy(ActionName.TOGGLE_WIFI, ActionParameters.ToggleWifi::class))
            add(legacy(ActionName.TOGGLE_BLUETOOTH, ActionParameters.ToggleBluetooth::class))
            add(legacy(ActionName.SET_BRIGHTNESS, ActionParameters.SetBrightness::class))
            add(legacy(ActionName.OPEN_QUICK_SETTINGS, ActionParameters.OpenQuickSettings::class))
            add(legacy(ActionName.SEND_WHATSAPP, ActionParameters.SendWhatsApp::class))
            add(legacy(ActionName.CREATE_TASK, ActionParameters.CreateTask::class))
            add(legacy(ActionName.COMPLETE_TASK, ActionParameters.CompleteTask::class))
            add(legacy(ActionName.REMEMBER_PREFERENCE, ActionParameters.RememberPreference::class))
            add(legacy(ActionName.SHOW_MEMORIES, ActionParameters.ShowMemories::class))
            add(legacy(ActionName.FORGET_MEMORY, ActionParameters.ForgetMemory::class))
            add(ChatTool())
        })

        private fun <P : ActionParameters> legacy(
            name: ActionName,
            type: KClass<P>,
            retrySafe: Boolean = false
        ): AssistantTool<P> = LegacyIntentAssistantTool(name, type, PermissionPolicyGate.policyFor(name), retrySafe)
    }
}

private class LegacyIntentAssistantTool<P : ActionParameters>(
    override val name: ActionName,
    override val parameterType: KClass<P>,
    override val policy: ActionPolicy,
    override val retrySafe: Boolean
) : AssistantTool<P> {
    override suspend fun execute(context: Context, request: ActionRequest<P>): ActionResult {
        val legacyResult = IntentManager.executeAction(context, name.name, request.parameters.toLegacyPayload())
        if (legacyResult.isSuccess) {
            return ActionResult.Started(request.id, name, "Android accepted the action request.")
        }

        val cause = legacyResult.exceptionOrNull()
        val assistantError = cause as? AssistantException
        if (assistantError?.category == ErrorCategory.PERMISSION_ERROR) {
            return ActionResult.PermissionRequired(
                request.id,
                name,
                ActionError(ActionErrorCode.PERMISSION_REQUIRED, "Permission is required for this action.")
            )
        }
        val code = if (assistantError?.canRetry == true) ActionErrorCode.TRANSIENT_FAILURE else ActionErrorCode.EXECUTION_FAILED
        return ActionResult.Failure(
            request.id,
            name,
            ActionError(code, assistantError?.message ?: "Action execution failed.", cause)
        )
    }
}

private class ChatTool : AssistantTool<ActionParameters.Chat> {
    override val name = ActionName.CHAT
    override val policy = ActionPolicy()
    override val parameterType = ActionParameters.Chat::class

    override suspend fun execute(context: Context, request: ActionRequest<ActionParameters.Chat>): ActionResult =
        ActionResult.Success(request.id, name, "The response is ready.")
}

private fun ActionParameters.toLegacyPayload(): String? = when (this) {
    is ActionParameters.OpenApp -> packageName
    is ActionParameters.SearchWeb -> query
    is ActionParameters.OpenUrl -> url
    is ActionParameters.PlayMusic -> query
    is ActionParameters.PlayVideo -> query
    is ActionParameters.Call -> target
    is ActionParameters.SendSms -> targetAndMessage
    ActionParameters.OpenSettings -> null
    is ActionParameters.Navigate -> destination
    is ActionParameters.SetAlarm -> hour.toString()
    is ActionParameters.SetTimer -> minutes.toString()
    ActionParameters.GoHome -> null
    ActionParameters.GoBack -> null
    ActionParameters.OpenNotifications -> null
    ActionParameters.RecentApps -> null
    is ActionParameters.ToggleWifi -> state
    is ActionParameters.ToggleBluetooth -> state
    is ActionParameters.SetBrightness -> percent.toString()
    ActionParameters.OpenQuickSettings -> null
    is ActionParameters.SendWhatsApp -> targetAndMessage
    is ActionParameters.CreateTask -> value
    is ActionParameters.CompleteTask -> title
    is ActionParameters.RememberPreference -> value
    ActionParameters.ShowMemories -> null
    is ActionParameters.ForgetMemory -> value
    ActionParameters.Chat -> null
}
