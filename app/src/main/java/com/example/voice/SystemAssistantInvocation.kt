package com.example.voice

import android.content.Context
import android.content.Intent
import com.example.MainActivity

object SystemAssistantInvocation {
    const val ACTION_START_VOICE_ASSISTANT = "com.example.action.START_VOICE_ASSISTANT"
    const val EXTRA_INVOCATION_SOURCE = "com.example.extra.ASSISTANT_INVOCATION_SOURCE"

    fun createActivityIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        action = ACTION_START_VOICE_ASSISTANT
        putExtra(EXTRA_INVOCATION_SOURCE, "android_voice_interaction")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    fun isAssistantInvocation(intent: Intent?): Boolean = intent?.action == ACTION_START_VOICE_ASSISTANT
}
