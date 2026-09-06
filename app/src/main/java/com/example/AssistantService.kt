package com.example

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent

class AssistantService : AccessibilityService() {

    companion object {
        var instance: AssistantService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null

        fun isAccessibilitySettingsEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${AssistantService::class.java.canonicalName}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AssistantLogger.i("AssistantService", "Accessibility service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        AssistantLogger.i("AssistantService", "Accessibility service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handled as needed
    }

    override fun onInterrupt() {
        AssistantLogger.w("AssistantService", "Accessibility service interrupted")
    }

    fun performGlobal(action: Int): Boolean {
        return performGlobalAction(action)
    }

    /**
     * Accessibility Service Device Control helpers
     */
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun showRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun toggleWifi(context: Context, enable: Boolean? = null) {
        com.example.device.DeviceControlManager.toggleWifi(context, enable)
    }

    fun toggleBluetooth(context: Context, enable: Boolean? = null) {
        com.example.device.DeviceControlManager.toggleBluetooth(context, enable)
    }

    fun setBrightness(context: Context, percent: Int) {
        com.example.device.DeviceControlManager.setBrightness(context, percent)
    }

    /**
     * Captures current on-screen content via AccessibilityService takeScreenshot API (Android 11+ / API 30+).
     * The captured hardware buffer bitmap is wrapped and returned in-memory for transient analysis,
     * respecting the strict privacy policy that screenshots are never saved or retained.
     */
    fun captureScreenAsync(
        onSuccess: (android.graphics.Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val executor = android.os.Handler(android.os.Looper.getMainLooper()).let { handler ->
                java.util.concurrent.Executor { command -> handler.post(command) }
            }

            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val bitmap = if (colorSpace != null) {
                                android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            } else {
                                android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                            }
                            hardwareBuffer.close()

                            if (bitmap != null) {
                                // Copy into software bitmap for JPEG compression / processing
                                val softwareBitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                onSuccess(softwareBitmap)
                            } else {
                                onError("Failed to process captured screen hardware buffer.")
                            }
                        } catch (e: Exception) {
                            AssistantLogger.e("AssistantService", "Error reading screenshot", e)
                            onError("Failed to read screenshot: ${e.message}")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val message = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Internal screen capture error."
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Accessibility service permission revoked or denied."
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Screen captures requested too quickly."
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid display."
                            else -> "Screen capture failed (code: $errorCode)."
                        }
                        AssistantLogger.w("AssistantService", message)
                        onError(message)
                    }
                }
            )
        } else {
            onError("On-screen capture via accessibility requires Android 11 or newer.")
        }
    }
}
