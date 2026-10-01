package com.example.voice

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.example.AssistantLogger

/** Heavy interaction work is created only for an explicit system invocation. */
class MjVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle): VoiceInteractionSession = MjVoiceInteractionSession(this)
}

class MjVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val sessionContext = context
    private var messageView: TextView? = null

    override fun onCreateContentView(): View = FrameLayout(sessionContext).apply {
        val label = TextView(sessionContext).apply {
            text = "Opening MJ…"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24)
        }
        messageView = label
        addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val keyguard = sessionContext.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked == true) {
            // No voice capture, Activity launch, or action is permitted while the device is locked.
            messageView?.text = "Unlock your device to use MJ voice assistant."
            return
        }
        runCatching {
            startVoiceActivity(SystemAssistantInvocation.createActivityIntent(sessionContext))
        }.onFailure {
            AssistantLogger.w(TAG, "Unable to open the assistant activity")
            messageView?.text = "MJ could not open. Unlock your device and try again."
        }
    }

    private companion object { const val TAG = "MjVoiceInteractionSession" }
}
