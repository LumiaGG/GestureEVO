package io.github.lumiagg.gestureevo.utils

import android.content.Context
import android.content.Intent

object TransparentActivityLauncher {
    fun start(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        context.startActivity(intent)
    }
}
