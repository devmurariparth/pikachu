package com.example.action

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActionRuntimeTest {
    private open class SearchTool : AssistantTool<ActionParameters.SearchWeb> {
        override val name = ActionName.SEARCH_WEB
        override val policy = ActionPolicy()
        override val parameterType = ActionParameters.SearchWeb::class
        override suspend fun execute(
            context: Context,
            request: ActionRequest<ActionParameters.SearchWeb>
        ): ActionResult =
            ActionResult.Started(request.id, request.name, "Dispatched")
    }

    @Test fun typed_tool_executes_then_verifies_the_result() = runTest {
        var executed = false
        var verified = false
        val tool = object : SearchTool() {
            override suspend fun execute(context: Context, request: ActionRequest<ActionParameters.SearchWeb>): ActionResult {
                executed = true
                return ActionResult.Started(request.id, request.name, "Dispatched")
            }
            override suspend fun verify(
                context: Context,
                request: ActionRequest<ActionParameters.SearchWeb>,
                started: ActionResult
            ): ActionResult {
                verified = true
                return ActionResult.Success(request.id, request.name, "Verified")
            }
        }
        val runtime = ActionRuntime(toolRegistry = ToolRegistry(listOf(tool)))
        val result = runtime.execute(
            ApplicationProvider.getApplicationContext(),
            ActionRequest("search-1", ActionName.SEARCH_WEB, ActionParameters.SearchWeb("weather"))
        )

        assertTrue(executed)
        assertTrue(verified)
        assertTrue(result is ActionResult.Success)
    }

    @Test fun a_tool_name_with_the_wrong_parameter_type_is_rejected_before_execution() = runTest {
        var executed = false
        val tool = object : SearchTool() {
            override suspend fun execute(context: Context, request: ActionRequest<ActionParameters.SearchWeb>): ActionResult {
                executed = true
                return ActionResult.Success(request.id, request.name, "Should not run")
            }
        }
        val runtime = ActionRuntime(toolRegistry = ToolRegistry(listOf(tool)))
        val result = runtime.execute(
            ApplicationProvider.getApplicationContext(),
            ActionRequest("wrong-type", ActionName.SEARCH_WEB, ActionParameters.PlayVideo("video"))
        )

        assertEquals(ActionErrorCode.INVALID_PARAMETERS, result.error?.code)
        assertTrue(!executed)
    }

    @Test fun policy_permission_denial_prevents_the_tool_from_running() = runTest {
        var executed = false
        val callTool = object : AssistantTool<ActionParameters.Call> {
            override val name = ActionName.CALL
            override val policy = ActionPolicy(requiredPermissions = setOf(Manifest.permission.CALL_PHONE))
            override val parameterType = ActionParameters.Call::class
            override suspend fun execute(context: Context, request: ActionRequest<ActionParameters.Call>): ActionResult {
                executed = true
                return ActionResult.Success(request.id, request.name, "Should not run")
            }
        }
        val runtime = ActionRuntime(toolRegistry = ToolRegistry(listOf(callTool)))
        val result = runtime.execute(
            ApplicationProvider.getApplicationContext(),
            ActionRequest("call-1", ActionName.CALL, ActionParameters.Call("Mom"))
        )

        assertTrue(result is ActionResult.PermissionRequired)
        assertTrue(!executed)
    }

    @Test fun execution_timeout_is_a_typed_failure() = runTest {
        val slowTool = object : SearchTool() {
            override suspend fun execute(context: Context, request: ActionRequest<ActionParameters.SearchWeb>): ActionResult {
                delay(30_000)
                return ActionResult.Success(request.id, request.name, "Too late")
            }
        }
        val result = ActionRuntime(toolRegistry = ToolRegistry(listOf(slowTool))).execute(
            ApplicationProvider.getApplicationContext(),
            ActionRequest("slow-1", ActionName.SEARCH_WEB, ActionParameters.SearchWeb("weather"))
        )

        assertTrue(result is ActionResult.TimedOut)
        assertEquals(ActionErrorCode.TIMEOUT, result.error?.code)
    }

    @Test fun mismatched_result_identity_is_rejected() = runTest {
        val invalidTool = object : SearchTool() {
            override suspend fun execute(context: Context, request: ActionRequest<ActionParameters.SearchWeb>): ActionResult =
                ActionResult.Success("different-id", request.name, "Incorrectly reported")
        }
        val result = ActionRuntime(toolRegistry = ToolRegistry(listOf(invalidTool))).execute(
            ApplicationProvider.getApplicationContext(),
            ActionRequest("search-1", ActionName.SEARCH_WEB, ActionParameters.SearchWeb("weather"))
        )

        assertEquals(ActionErrorCode.VERIFICATION_FAILED, result.error?.code)
    }
}
