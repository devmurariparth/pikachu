package com.example.action

import android.content.Context
import com.example.IntentManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout

class ActionRuntime(
    private val gateFactory: (Context, ActionContext) -> PermissionPolicyGate = { context, actionContext ->
        PermissionPolicyGate(context, actionContext)
    },
    private val cancellationRegistry: ActionCancellationRegistry = ActionCancellationRegistry()
) {
    suspend fun execute(
        context: Context,
        request: ActionRequest<out ActionParameters>,
        actionContext: ActionContext = ActionContext()
    ): ActionResult {
        val gate = gateFactory(context, actionContext)
        val policy = PermissionPolicyGate.policyFor(request.name)

        when (val decision = gate.evaluate(policy)) {
            is PolicyDecision.Blocked -> return ActionResult.Blocked(request.id, request.name, decision.error)
            PolicyDecision.Allowed -> Unit
        }

        val job = currentCoroutineContext().job
        cancellationRegistry.register(request.id, job)
        return try {
            currentCoroutineContext().ensureActive()
            val legacyResult = withTimeout(ACTION_TIMEOUT_MS) { IntentManager.executeAction(
                context = context,
                action = request.name.name,
                payload = request.parameters.toLegacyPayload()
            ) }
            currentCoroutineContext().ensureActive()

            if (legacyResult.isSuccess) {
                ActionResult.Started(request.id, request.name, "Action started.")
            } else {
                ActionResult.Failure(
                    request.id,
                    request.name,
                    ActionError(
                        ActionErrorCode.EXECUTION_FAILED,
                        legacyResult.exceptionOrNull()?.message ?: "Action failed."
                    )
                )
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            ActionResult.TimedOut(
                request.id,
                request.name,
                ActionError(ActionErrorCode.EXECUTION_FAILED, "Action timed out.")
            )
        } catch (_: CancellationException) {
            ActionResult.Cancelled(request.id, request.name)
        } catch (t: Throwable) {
            ActionResult.Failure(
                request.id,
                request.name,
                ActionError(ActionErrorCode.EXECUTION_FAILED, "Action execution failed.", t)
            )
        } finally {
            cancellationRegistry.clear(request.id)
        }
    }

    fun cancel(actionId: String): Boolean = cancellationRegistry.cancel(actionId)
    fun cancelAll() = cancellationRegistry.cancelAll()

    companion object { private const val ACTION_TIMEOUT_MS = 20_000L }

    private fun ActionParameters.toLegacyPayload(): String? = when (this) {
        is ActionParameters.OpenApp -> packageName
        is ActionParameters.SearchWeb -> query
        is ActionParameters.OpenUrl -> url
        is ActionParameters.PlayMusic -> query
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
}
