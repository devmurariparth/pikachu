package com.example.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCapabilitiesTest {
    private fun facts(api: Int = 36, overrides: AssistantCapabilityFacts.() -> AssistantCapabilityFacts = { this }) =
        AssistantCapabilityFacts(
            apiLevel = api,
            voiceInteractionServiceDeclared = true,
            assistantRoleAvailable = true,
            assistantRoleHeld = true,
            hasMicrophone = true,
            microphonePermissionGranted = true,
            notificationsPermissionGranted = true,
            accessibilityServiceDeclared = true,
            accessibilityEnabled = true,
            accessibilityBlockedByPolicy = false,
            contactsPermissionGranted = true,
            hasTelephony = true,
            callPermissionGranted = true,
            mediaIntentsSupported = true
        ).overrides()

    @Test fun reports_supported_and_granted_capabilities() {
        val report = AssistantCapabilityDetector.evaluate(facts())

        listOf(
            report.voiceInteraction, report.assistantRole, report.microphone,
            report.notifications, report.accessibility, report.contacts,
            report.calling, report.media, report.androidVersion
        ).forEach { assertEquals(CapabilityState.AVAILABLE, it.state) }
    }

    @Test fun reports_android_permission_and_role_requirements_without_bypassing_them() {
        val report = AssistantCapabilityDetector.evaluate(
            facts().copy(
                assistantRoleHeld = false,
                microphonePermissionGranted = false,
                notificationsPermissionGranted = false,
                contactsPermissionGranted = false,
                callPermissionGranted = false,
                accessibilityEnabled = false
            )
        )

        assertEquals(CapabilityState.NEEDS_ROLE, report.assistantRole.state)
        assertEquals(CapabilityState.NEEDS_PERMISSION, report.microphone.state)
        assertEquals(CapabilityState.NEEDS_PERMISSION, report.notifications.state)
        assertEquals(CapabilityState.NEEDS_PERMISSION, report.contacts.state)
        assertEquals(CapabilityState.NEEDS_PERMISSION, report.calling.state)
        assertEquals(CapabilityState.NEEDS_PERMISSION, report.accessibility.state)
    }

    @Test fun unsupported_android_and_missing_hardware_are_reported() {
        val report = AssistantCapabilityDetector.evaluate(
            facts(api = 23).copy(
                hasMicrophone = false,
                hasTelephony = false,
                mediaIntentsSupported = false,
                minimumSupportedApi = 24
            )
        )

        assertEquals(CapabilityState.UNSUPPORTED, report.assistantRole.state)
        assertEquals(CapabilityState.UNSUPPORTED, report.microphone.state)
        assertEquals(CapabilityState.UNSUPPORTED, report.calling.state)
        assertEquals(CapabilityState.UNSUPPORTED, report.media.state)
        assertEquals(CapabilityState.UNSUPPORTED, report.androidVersion.state)
        assertTrue(report.androidVersion.detail.contains("API 23"))
    }

    @Test fun broken_voice_declaration_and_policy_blocked_accessibility_are_explicit() {
        val report = AssistantCapabilityDetector.evaluate(
            facts().copy(
                voiceInteractionServiceDeclared = false,
                accessibilityBlockedByPolicy = true
            )
        )
        assertEquals(CapabilityState.BLOCKED, report.voiceInteraction.state)
        assertEquals(CapabilityState.BLOCKED, report.accessibility.state)
    }

    @Test fun assistant_role_detection_covers_granted_denied_and_unavailable() {
        assertEquals(
            AssistantRoleRequestStatus.GRANTED,
            AssistantRoleManager.evaluateStatus(34, roleAvailable = true, roleHeld = true)
        )
        assertEquals(
            AssistantRoleRequestStatus.NEEDS_REQUEST,
            AssistantRoleManager.evaluateStatus(34, roleAvailable = true, roleHeld = false)
        )
        assertEquals(
            AssistantRoleRequestStatus.UNAVAILABLE,
            AssistantRoleManager.evaluateStatus(28, roleAvailable = false, roleHeld = false)
        )
        assertEquals(
            AssistantRoleRequestStatus.DENIED,
            AssistantRoleManager.evaluateRequestResult(
                34,
                roleAvailable = true,
                roleHeld = false,
                resultCode = android.app.Activity.RESULT_CANCELED
            )
        )
    }
}
