package io.github.lumiagg.gestureevo.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.lumiagg.gestureevo.R

object NotificationPermissionUtils {
    private const val PROMPT_INTERVAL_MS = 1_000L
    private var lastPromptAt = 0L

    fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun showDisabledPromptIfNeeded(context: Context): Boolean {
        if (canPostNotifications(context)) return false
        val now = System.currentTimeMillis()
        if (now - lastPromptAt < PROMPT_INTERVAL_MS) return true
        lastPromptAt = now
        runCatching {
            context.startActivity(
                Intent(context, NotificationPermissionPromptActivity::class.java).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            ToastHelper.showInUIThread(
                context,
                context.getString(R.string.notification_permission_disabled_message)
            )
        }
        return true
    }
}
