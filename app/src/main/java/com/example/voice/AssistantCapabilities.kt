package com.example.voice

import android.annotation.SuppressLint
import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.AssistantService

enum class CapabilityState {
    AVAILABLE,
    NEEDS_PERMISSION,
    NEEDS_ROLE,
    UNSUPPORTED,
    BLOCKED
}

data class CapabilityStatus(
    val state: CapabilityState,
    val detail: String
)

data class AssistantCapabilityReport(
    val voiceInteraction: CapabilityStatus,
    val assistantRole: CapabilityStatus,
    val microphone: CapabilityStatus,
    val notifications: CapabilityStatus,
    val accessibility: CapabilityStatus,
    val contacts: CapabilityStatus,
    val calling: CapabilityStatus,
    val media: CapabilityStatus,
    val androidVersion: CapabilityStatus
)

/** Facts are split from Android service lookups so API edge cases can be tested without a device. */
data class AssistantCapabilityFacts(
    val apiLevel: Int,
    val voiceInteractionServiceDeclared: Boolean,
    val assistantRoleAvailable: Boolean,
    val assistantRoleHeld: Boolean,
    val hasMicrophone: Boolean,
    val microphonePermissionGranted: Boolean,
    val notificationsPermissionGranted: Boolean,
    val accessibilityServiceDeclared: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityBlockedByPolicy: Boolean,
    val contactsPermissionGranted: Boolean,
    val hasTelephony: Boolean,
    val callPermissionGranted: Boolean,
    val mediaIntentsSupported: Boolean,
    val minimumSupportedApi: Int = 24
)

object AssistantCapabilityDetector {
    private const val ROLE_ASSISTANT = "android.app.role.ASSISTANT"

    @SuppressLint("NewApi") // RoleManager is only queried after the API 29 version check below.
    fun detect(context: Context): AssistantCapabilityReport {
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val apiLevel = Build.VERSION.SDK_INT
        val roleManager = if (apiLevel >= Build.VERSION_CODES.Q) {
            getRoleManager(appContext)
        } else null
        val voiceServiceDeclared = runCatching {
            packageManager.resolveService(
                Intent().setClass(appContext, MjVoiceInteractionService::class.java),
                PackageManager.MATCH_DEFAULT_ONLY
            ) != null
        }.getOrDefault(false)
        val accessibilityDeclared = runCatching {
            packageManager.getServiceInfo(
                android.content.ComponentName(appContext, AssistantService::class.java),
                0
            )
            true
        }.getOrDefault(false)
        val postNotificationsGranted = apiLevel < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        return evaluate(
            AssistantCapabilityFacts(
                apiLevel = apiLevel,
                voiceInteractionServiceDeclared = voiceServiceDeclared,
                assistantRoleAvailable = roleManager?.isRoleAvailable(ROLE_ASSISTANT) == true,
                assistantRoleHeld = roleManager?.isRoleHeld(ROLE_ASSISTANT) == true,
                hasMicrophone = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
                microphonePermissionGranted = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED,
                notificationsPermissionGranted = postNotificationsGranted,
                accessibilityServiceDeclared = accessibilityDeclared,
                accessibilityEnabled = accessibilityDeclared && AssistantService.isAccessibilitySettingsEnabled(appContext),
                accessibilityBlockedByPolicy = false,
                contactsPermissionGranted = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED,
                hasTelephony = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
                callPermissionGranted = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED,
                mediaIntentsSupported = apiLevel >= Build.VERSION_CODES.LOLLIPOP
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getRoleManager(context: Context): RoleManager? =
        context.getSystemService(RoleManager::class.java)

    fun evaluate(facts: AssistantCapabilityFacts): AssistantCapabilityReport {
        fun status(state: CapabilityState, detail: String) = CapabilityStatus(state, detail)
        val role = when {
            facts.apiLevel < Build.VERSION_CODES.Q -> status(CapabilityState.UNSUPPORTED, "Assistant role requires Android 10 or newer.")
            !facts.assistantRoleAvailable -> status(CapabilityState.UNSUPPORTED, "Android does not offer the assistant role on this device.")
            facts.assistantRoleHeld -> status(CapabilityState.AVAILABLE, "MJ holds the assistant role.")
            else -> status(CapabilityState.NEEDS_ROLE, "Choose MJ in Android's assistant-role confirmation screen.")
        }
        val microphone = when {
            !facts.hasMicrophone -> status(CapabilityState.UNSUPPORTED, "This device has no microphone feature.")
            facts.microphonePermissionGranted -> status(CapabilityState.AVAILABLE, "Microphone permission is granted.")
            else -> status(CapabilityState.NEEDS_PERMISSION, "Microphone permission must be granted in the app.")
        }
        val accessibility = when {
            facts.accessibilityBlockedByPolicy -> status(CapabilityState.BLOCKED, "Accessibility is blocked by device policy.")
            !facts.accessibilityServiceDeclared -> status(CapabilityState.BLOCKED, "The limited MJ accessibility service is not declared.")
            facts.accessibilityEnabled -> status(CapabilityState.AVAILABLE, "The user enabled MJ's limited accessibility service.")
            else -> status(CapabilityState.NEEDS_PERMISSION, "Enable the limited service in Android Accessibility settings if desired.")
        }
        return AssistantCapabilityReport(
            voiceInteraction = when {
                facts.apiLevel < Build.VERSION_CODES.LOLLIPOP -> status(CapabilityState.UNSUPPORTED, "Voice interaction requires Android 5 or newer.")
                facts.voiceInteractionServiceDeclared -> status(CapabilityState.AVAILABLE, "MJ declares an Android VoiceInteractionService.")
                else -> status(CapabilityState.BLOCKED, "MJ's VoiceInteractionService is not declared.")
            },
            assistantRole = role,
            microphone = microphone,
            notifications = if (facts.notificationsPermissionGranted) {
                status(CapabilityState.AVAILABLE, "MJ can post notifications.")
            } else {
                status(CapabilityState.NEEDS_PERMISSION, "Allow MJ to post notifications in Android settings.")
            },
            accessibility = accessibility,
            contacts = if (facts.contactsPermissionGranted) {
                status(CapabilityState.AVAILABLE, "Contacts permission is granted.")
            } else {
                status(CapabilityState.NEEDS_PERMISSION, "Contacts permission is requested only when a contact action needs it.")
            },
            calling = when {
                !facts.hasTelephony -> status(CapabilityState.UNSUPPORTED, "This device has no telephony feature.")
                facts.callPermissionGranted -> status(CapabilityState.AVAILABLE, "Direct calling permission is granted.")
                else -> status(CapabilityState.NEEDS_PERMISSION, "Direct calls require CALL_PHONE permission; the dialer can still be opened for confirmation.")
            },
            media = if (facts.mediaIntentsSupported) {
                status(CapabilityState.AVAILABLE, "Explicit media intents are supported; available apps determine playback.")
            } else {
                status(CapabilityState.UNSUPPORTED, "Android media intents are not supported on this version.")
            },
            androidVersion = if (facts.apiLevel >= facts.minimumSupportedApi) {
                status(CapabilityState.AVAILABLE, "Android API ${facts.apiLevel} meets MJ's minimum API ${facts.minimumSupportedApi}.")
            } else {
                status(CapabilityState.UNSUPPORTED, "Android API ${facts.apiLevel} is below MJ's minimum API ${facts.minimumSupportedApi}.")
            }
        )
    }
}

enum class AssistantRoleRequestStatus { GRANTED, NEEDS_REQUEST, DENIED, UNAVAILABLE }

object AssistantRoleManager {
    private const val ROLE_ASSISTANT = "android.app.role.ASSISTANT"

    @SuppressLint("NewApi") // Android 10 role APIs are reached only after the SDK guard.
    fun status(context: Context): AssistantRoleRequestStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return AssistantRoleRequestStatus.UNAVAILABLE
        val roleManager = context.getSystemService(RoleManager::class.java)
            ?: return AssistantRoleRequestStatus.UNAVAILABLE
        return runCatching {
            evaluateStatus(
                apiLevel = Build.VERSION.SDK_INT,
                roleAvailable = roleManager.isRoleAvailable(ROLE_ASSISTANT),
                roleHeld = roleManager.isRoleHeld(ROLE_ASSISTANT)
            )
        }.getOrDefault(AssistantRoleRequestStatus.UNAVAILABLE)
    }

    fun evaluateStatus(apiLevel: Int, roleAvailable: Boolean, roleHeld: Boolean): AssistantRoleRequestStatus = when {
        apiLevel < Build.VERSION_CODES.Q || !roleAvailable -> AssistantRoleRequestStatus.UNAVAILABLE
        roleHeld -> AssistantRoleRequestStatus.GRANTED
        else -> AssistantRoleRequestStatus.NEEDS_REQUEST
    }

    @SuppressLint("NewApi") // Role request flow is only offered when status confirms Android 10+ support.
    fun createRequestIntent(context: Context): Intent? {
        if (status(context) != AssistantRoleRequestStatus.NEEDS_REQUEST) return null
        return runCatching {
            context.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(ROLE_ASSISTANT)
        }.getOrNull()
    }

    @SuppressLint("NewApi") // Role state is queried only after the SDK version check.
    fun resultAfterRequest(context: Context, resultCode: Int): AssistantRoleRequestStatus {
        val roleManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
        } else null
        val isAvailable = runCatching { roleManager?.isRoleAvailable(ROLE_ASSISTANT) == true }.getOrDefault(false)
        val isHeld = runCatching { roleManager?.isRoleHeld(ROLE_ASSISTANT) == true }.getOrDefault(false)
        return evaluateRequestResult(Build.VERSION.SDK_INT, isAvailable, isHeld, resultCode)
    }

    fun evaluateRequestResult(
        apiLevel: Int,
        roleAvailable: Boolean,
        roleHeld: Boolean,
        resultCode: Int
    ): AssistantRoleRequestStatus = when {
        apiLevel < Build.VERSION_CODES.Q || !roleAvailable -> AssistantRoleRequestStatus.UNAVAILABLE
        roleHeld -> AssistantRoleRequestStatus.GRANTED
        resultCode == android.app.Activity.RESULT_CANCELED -> AssistantRoleRequestStatus.DENIED
        else -> AssistantRoleRequestStatus.NEEDS_REQUEST
    }
}
