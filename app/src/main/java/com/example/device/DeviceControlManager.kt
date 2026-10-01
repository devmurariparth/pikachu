package com.example.device

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.AssistantLogger
import com.example.AssistantService

data class DeviceControlResult(
    val success: Boolean,
    val spokenMessage: String,
    val details: String? = null
)

object DeviceControlManager {
    private const val TAG = "DeviceControlManager"

    /** Opens Android's own connectivity controls; Android does not permit apps to silently toggle Wi-Fi. */
    fun toggleWifi(context: Context, turnOn: Boolean? = null): DeviceControlResult {
        AssistantLogger.i(TAG, "Toggling Wi-Fi (requested: $turnOn)")

        // 1. On Android 10+ (API 29+), opening the system connectivity panel is the Google-recommended method
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryLaunchIntent(context, panelIntent)) {
                val stateText = when (turnOn) {
                    true -> "Turning on Wi-Fi."
                    false -> "Turning off Wi-Fi."
                    null -> "Opening Wi-Fi controls."
                }
                return DeviceControlResult(
                    success = true,
                    spokenMessage = stateText,
                    details = "Opened Android Internet connectivity panel."
                )
            }
        }

        // On versions without the connectivity panel, open Android's Wi-Fi settings.
        val wifiSettingsIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (tryLaunchIntent(context, wifiSettingsIntent)) {
            DeviceControlResult(
                success = true,
                spokenMessage = "Opening Wi-Fi settings.",
                details = "Navigated to system Wi-Fi settings."
            )
        } else {
            DeviceControlResult(
                success = false,
                spokenMessage = "Unable to open Wi-Fi controls.",
                details = "Failed to launch Android Wi-Fi controls."
            )
        }
    }

    /** Opens Android Bluetooth settings; MJ does not toggle the radio through hidden automation. */
    fun toggleBluetooth(context: Context, turnOn: Boolean? = null): DeviceControlResult {
        AssistantLogger.i(TAG, "Toggling Bluetooth (requested: $turnOn)")

        // 1. Android Bluetooth Settings
        val bluetoothPanelIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryLaunchIntent(context, bluetoothPanelIntent)) {
            val stateText = when (turnOn) {
                true -> "Turning on Bluetooth."
                false -> "Turning off Bluetooth."
                null -> "Opening Bluetooth controls."
            }
            return DeviceControlResult(
                success = true,
                spokenMessage = stateText,
                details = "Opened Android Bluetooth settings."
            )
        }

        // Keep the same user-controlled settings route as a fallback.
        val btSettingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (tryLaunchIntent(context, btSettingsIntent)) {
            DeviceControlResult(
                success = true,
                spokenMessage = "Opening Bluetooth settings.",
                details = "Navigated to system Bluetooth settings."
            )
        } else {
            DeviceControlResult(
                success = false,
                spokenMessage = "Unable to open Bluetooth controls.",
                details = "Failed to launch intent."
            )
        }
    }

    /** Adjusts brightness only with Android's write-settings access, otherwise opens user-controlled settings. */
    fun setBrightness(context: Context, percent: Int): DeviceControlResult {
        val clampedPercent = percent.coerceIn(0, 100)
        AssistantLogger.i(TAG, "Setting screen brightness to $clampedPercent%")

        val hasWritePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }

        if (hasWritePermission) {
            return try {
                val brightnessValue = ((clampedPercent / 100f) * 255).toInt().coerceIn(1, 255)
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightnessValue
                )
                DeviceControlResult(
                    success = true,
                    spokenMessage = "Screen brightness set to $clampedPercent%.",
                    details = "Directly modified system screen brightness."
                )
            } catch (e: Exception) {
                AssistantLogger.w(TAG, "Failed to write brightness")
                fallbackBrightness(context, clampedPercent)
            }
        } else {
            return fallbackBrightness(context, clampedPercent)
        }
    }

    private fun fallbackBrightness(context: Context, targetPercent: Int): DeviceControlResult {
        // Open Display Settings
        val displayIntent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (tryLaunchIntent(context, displayIntent)) {
            return DeviceControlResult(
                success = true,
                spokenMessage = "Opening Display settings for brightness.",
                details = "Prompted display settings."
            )
        }

        // Ask for write settings permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissionIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            tryLaunchIntent(context, permissionIntent)
        }

        return DeviceControlResult(
            success = false,
            spokenMessage = "Please grant system write permission to adjust brightness automatically.",
            details = "Write settings permission required."
        )
    }

    /**
     * Pulls down the system quick settings panel using the Accessibility Service API.
     */
    fun openQuickSettings(): DeviceControlResult {
        val service = AssistantService.instance
        return if (service != null && service.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
            DeviceControlResult(true, "Opening Quick Settings.")
        } else {
            DeviceControlResult(false, "Accessibility Service is required to open Quick Settings.")
        }
    }

    /**
     * Opens system notification shade using Accessibility Service API.
     */
    fun openNotifications(): DeviceControlResult {
        val service = AssistantService.instance
        return if (service != null && service.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) {
            DeviceControlResult(true, "Opening notifications.")
        } else {
            DeviceControlResult(false, "Accessibility Service is required to open notifications.")
        }
    }

    /**
     * Controls device flashlight / torch.
     */
    fun toggleFlashlight(context: Context, enable: Boolean): DeviceControlResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return DeviceControlResult(false, "Flashlight unavailable.")
            return try {
                val cameraId = cameraManager.cameraIdList.firstOrNull()
                    ?: return DeviceControlResult(false, "No camera flash found.")
                cameraManager.setTorchMode(cameraId, enable)
                val msg = if (enable) "Flashlight turned on." else "Flashlight turned off."
                DeviceControlResult(true, msg)
            } catch (e: Exception) {
                AssistantLogger.w(TAG, "Flashlight toggle failed")
                DeviceControlResult(false, "Failed to toggle flashlight.")
            }
        }
        return DeviceControlResult(false, "Flashlight control requires Android 6.0 or newer.")
    }

    /**
     * Adjusts device media/ring volume.
     */
    fun adjustVolume(context: Context, direction: Int): DeviceControlResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return DeviceControlResult(false, "Audio control unavailable.")

        audioManager.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
        val msg = when (direction) {
            AudioManager.ADJUST_RAISE -> "Volume increased."
            AudioManager.ADJUST_LOWER -> "Volume decreased."
            AudioManager.ADJUST_MUTE -> "Device muted."
            AudioManager.ADJUST_UNMUTE -> "Device unmuted."
            else -> "Volume adjusted."
        }
        return DeviceControlResult(true, msg)
    }

    private fun tryLaunchIntent(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AssistantLogger.w(TAG, "Failed to launch system settings intent")
            false
        }
    }
}
