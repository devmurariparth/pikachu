package com.example.voice

import android.service.voice.VoiceInteractionService
import android.content.ComponentName
import com.example.AssistantLogger

/** Lightweight system-owned entry point. It never starts a microphone or performs assistant work. */
class MjVoiceInteractionService : VoiceInteractionService() {
    @Volatile
    private var systemReady = false

    override fun onReady() {
        super.onReady()
        systemReady = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            VoiceInteractionService.isActiveService(this, ComponentName(this, javaClass))
        ) {
            runCatching {
                setDisabledShowContext(
                    android.service.voice.VoiceInteractionSession.SHOW_WITH_ASSIST or
                        android.service.voice.VoiceInteractionSession.SHOW_WITH_SCREENSHOT
                )
            }
        }
        AssistantLogger.i(TAG, "System voice interaction service ready")
    }

    override fun onShutdown() {
        systemReady = false
        AssistantLogger.i(TAG, "System voice interaction service shut down")
        super.onShutdown()
    }

    internal fun isSystemReadyForTest(): Boolean = systemReady

    private companion object { const val TAG = "MjVoiceInteractionService" }
}
