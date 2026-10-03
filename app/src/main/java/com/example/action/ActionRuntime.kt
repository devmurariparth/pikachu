package com.example.action

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout

class ActionRuntime(
    private val gateFactory: (Context, ActionContext) -> PermissionPolicyGate = { context, actionContext ->
        PermissionPolicyGate(context, actionContext)
    },
    private val cancellationRegistry: ActionCancellationRegistry = ActionCancellationRegistry(),
    val toolRegistry: ToolRegistry = ToolRegistry.existingCapabilities()
) {
    suspend fun execute(
        context: Context,
        request: ActionRequest<out ActionParameters>,
        actionContext: ActionContext = ActionContext()
    ): ActionResult {
        val tool = toolRegistry.find(request.name)
            ?: return ActionResult.Unsupported(
                request.id,
                request.name,
                ActionError(ActionErrorCode.UNSUPPORTED, "This action has no registered tool.")
            )
        if (!toolRegistry.accepts(request.name, request.parameters)) {
            return ActionResult.Failure(
                request.id,
                request.name,
                ActionError(ActionErrorCode.INVALID_PARAMETERS, "Action parameters do not match the registered tool.")
            )
        }

        val gate = gateFactory(context, actionContext)

        when (val decision = gate.evaluate(tool.policy)) {
            is PolicyDecision.Blocked -> return when (decision.error.code) {
                ActionErrorCode.PERMISSION_REQUIRED -> ActionResult.PermissionRequired(request.id, request.name, decision.error)
                ActionErrorCode.UNSUPPORTED -> ActionResult.Unsupported(request.id, request.name, decision.error)
                else -> ActionResult.Blocked(request.id, request.name, decision.error)
            }
            PolicyDecision.Allowed -> Unit
        }

        invokeTool(tool, context, request)?.let { preconditionError ->
            return ActionResult.Failure(request.id, request.name, preconditionError)
        }

        val job = currentCoroutineContext().job
        cancellationRegistry.register(request.id, job)
        return try {
            currentCoroutineContext().ensureActive()
            val actionResult = withTimeout(ACTION_TIMEOUT_MS) {
                val started = executeTool(tool, context, request)
                if (started.actionId != request.id || started.action != request.name) {
                    ActionResult.Failure(
                        request.id,
                        request.name,
                        ActionError(ActionErrorCode.VERIFICATION_FAILED, "Tool execution returned a mismatched action result.")
                    )
                } else if (started is ActionResult.Started || started is ActionResult.Success) {
                    val verified = verifyTool(tool, context, request, started)
                    if (verified.actionId != request.id || verified.action != request.name) {
                        ActionResult.Failure(
                            request.id,
                            request.name,
                            ActionError(ActionErrorCode.VERIFICATION_FAILED, "Tool verification returned a mismatched action result.")
                        )
                    } else {
                        verified
                    }
                } else started
            }
            currentCoroutineContext().ensureActive()
            actionResult
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            ActionResult.TimedOut(
                request.id,
                request.name,
                ActionError(ActionErrorCode.TIMEOUT, "Action timed out.")
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

    private fun invokeTool(
        tool: AssistantTool<out ActionParameters>,
        context: Context,
        request: ActionRequest<out ActionParameters>
    ): ActionError? {
        val (typedTool, typedRequest) = typedToolAndRequest(tool, request)
        return typedTool.precondition(context, typedRequest)
    }

    private suspend fun executeTool(
        tool: AssistantTool<out ActionParameters>,
        context: Context,
        request: ActionRequest<out ActionParameters>
    ): ActionResult {
        val (typedTool, typedRequest) = typedToolAndRequest(tool, request)
        return typedTool.execute(context, typedRequest)
    }

    private suspend fun verifyTool(
        tool: AssistantTool<out ActionParameters>,
        context: Context,
        request: ActionRequest<out ActionParameters>,
        started: ActionResult
    ): ActionResult {
        val (typedTool, typedRequest) = typedToolAndRequest(tool, request)
        return typedTool.verify(context, typedRequest, started)
    }

    @Suppress("UNCHECKED_CAST")
    private fun typedToolAndRequest(
        tool: AssistantTool<out ActionParameters>,
        request: ActionRequest<out ActionParameters>
    ): Pair<AssistantTool<ActionParameters>, ActionRequest<ActionParameters>> {
        check(tool.parameterType.isInstance(request.parameters))
        return (tool as AssistantTool<ActionParameters>) to (request as ActionRequest<ActionParameters>)
    }

    companion object { private const val ACTION_TIMEOUT_MS = 20_000L }
}
