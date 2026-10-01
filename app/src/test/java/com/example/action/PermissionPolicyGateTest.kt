package com.example.action

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionPolicyGateTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun unsupported_api_is_blocked_before_execution() {
        val gate = PermissionPolicyGate(context)
        val decision = gate.evaluate(ActionPolicy(minApi = 100))
        assertTrue(decision is PolicyDecision.Blocked)
        assertTrue((decision as PolicyDecision.Blocked).error.code == ActionErrorCode.UNSUPPORTED)
    }

    @Test
    fun call_policy_declares_call_permission() {
        val policy = PermissionPolicyGate.policyFor(ActionName.CALL)
        assertTrue(Manifest.permission.CALL_PHONE in policy.requiredPermissions)
    }

    @Test
    fun accessibility_actions_are_policy_gated() {
        val gate = PermissionPolicyGate(context, ActionContext(accessibilityAllowed = false))
        val decision = gate.evaluate(PermissionPolicyGate.policyFor(ActionName.GO_BACK))
        assertTrue(decision is PolicyDecision.Blocked)
        assertTrue((decision as PolicyDecision.Blocked).error.code == ActionErrorCode.POLICY_BLOCKED)
    }

    @Test
    fun home_uses_the_android_home_intent_without_requiring_accessibility() {
        val gate = PermissionPolicyGate(context, ActionContext(accessibilityAllowed = false))
        assertTrue(gate.evaluate(PermissionPolicyGate.policyFor(ActionName.GO_HOME)) is PolicyDecision.Allowed)
    }
}
