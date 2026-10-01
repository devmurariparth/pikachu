package com.example.action

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision
    data class Blocked(val error: ActionError) : PolicyDecision
}

class PermissionPolicyGate(
    private val context: Context,
    private val actionContext: ActionContext = ActionContext()
) {
    fun evaluate(policy: ActionPolicy): PolicyDecision {
        if (!policy.allowed) {
            return PolicyDecision.Blocked(
                ActionError(ActionErrorCode.POLICY_BLOCKED, "This action is disabled by MJ policy.")
            )
        }

        if (Build.VERSION.SDK_INT < policy.minApi) {
            return PolicyDecision.Blocked(
                ActionError(
                    ActionErrorCode.UNSUPPORTED,
                    "This action is not supported on Android API ${Build.VERSION.SDK_INT}."
                )
            )
        }

        val missing = policy.requiredPermissions.filterNot(::hasPermission)
        if (missing.isNotEmpty()) {
            return PolicyDecision.Blocked(
                ActionError(ActionErrorCode.PERMISSION_REQUIRED, "Required Android permission is not granted.")
            )
        }

        policy.requiredSystemRole?.let { role ->
            if (!isRoleHeld(role)) {
                return PolicyDecision.Blocked(
                    ActionError(ActionErrorCode.POLICY_BLOCKED, "Required Android system role is not active.")
                )
            }
        }

        if (policy.accessibilityUse != AccessibilityUse.NONE && !actionContext.accessibilityAllowed) {
            return PolicyDecision.Blocked(
                ActionError(
                    ActionErrorCode.POLICY_BLOCKED,
                    "Accessibility is not an allowed execution path for this action."
                )
            )
        }

        return PolicyDecision.Allowed
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isRoleHeld(roleName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleHeld(roleName) || actionContext.requiredRoleHeld(roleName)
    }

    companion object {
        fun policyFor(name: ActionName): ActionPolicy = when (name) {
            ActionName.CALL -> ActionPolicy(requiredPermissions = setOf(Manifest.permission.CALL_PHONE))
            ActionName.SEND_SMS -> ActionPolicy()
            ActionName.OPEN_APP, ActionName.OPEN_URL, ActionName.SEARCH_WEB,
            ActionName.OPEN_SETTINGS, ActionName.NAVIGATE, ActionName.SET_ALARM,
            ActionName.SET_TIMER, ActionName.PLAY_MUSIC, ActionName.CREATE_TASK,
            ActionName.COMPLETE_TASK, ActionName.REMEMBER_PREFERENCE,
            ActionName.SHOW_MEMORIES, ActionName.FORGET_MEMORY, ActionName.CHAT -> ActionPolicy()
            ActionName.GO_HOME -> ActionPolicy()
            ActionName.GO_BACK, ActionName.OPEN_NOTIFICATIONS,
            ActionName.RECENT_APPS, ActionName.OPEN_QUICK_SETTINGS -> ActionPolicy(
                accessibilityUse = AccessibilityUse.LIMITED_GLOBAL_NAVIGATION
            )
            ActionName.TOGGLE_WIFI, ActionName.TOGGLE_BLUETOOTH,
            ActionName.SET_BRIGHTNESS, ActionName.SEND_WHATSAPP -> ActionPolicy()
        }
    }
}
