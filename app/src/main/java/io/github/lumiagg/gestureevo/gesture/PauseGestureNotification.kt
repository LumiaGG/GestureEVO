package io.github.lumiagg.gestureevo.gesture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.github.lumiagg.gestureevo.BuildConfig
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.utils.NotificationPermissionUtils
import io.github.lumiagg.gestureevo.utils.ToastHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object PauseGestureNotification {
    private const val TAG = "PauseGestureNotification"
    private const val CHANNEL_ID = "pause_gesture_countdown"
    private const val NOTIFICATION_ID = 0x0E01
    internal val ACTION_EXTEND = "${BuildConfig.APPLICATION_ID}.action.PAUSE_GESTURE_EXTEND"
    internal val ACTION_RESTORE = "${BuildConfig.APPLICATION_ID}.action.PAUSE_GESTURE_RESTORE"
    internal const val EXTRA_TRIGGER_TAG = "pause_gesture_trigger_tag"
    internal const val KEY_INPUT_SECONDS = "pause_gesture_input_seconds"

    fun show(
        context: Context,
        tag: String,
        triggerName: String,
        totalDurationMs: Long,
        remainingMs: Long = totalDurationMs
    ) {
        // Pausing works without notifications, so a blocked notification must not fail the action.
        // Prompt once so the countdown controls are discoverable instead of silently missing.
        if (NotificationPermissionUtils.showDisabledPromptIfNeeded(context)) {
            AppLogger.event(
                LogModule.ACTION, "pause_gesture_notification_skipped",
                LogLevel.WARN, fields = mapOf("reason" to "notification_disabled")
            )
            return
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannelIfNeeded(context, notificationManager)

        val finishAt = System.currentTimeMillis() + remainingMs
        val totalSeconds = (totalDurationMs / 1000L).coerceAtLeast(1L).toInt()
        val requestBase = tag.hashCode()
        // The body states the whole session length, matching the temporary DND notification.
        val bodyText = if (totalSeconds < 60) {
            context.resources.getQuantityString(
                R.plurals.pause_gesture_notification_text_seconds,
                totalSeconds,
                triggerName,
                totalSeconds
            )
        } else {
            val minutes = (totalSeconds + 59) / 60
            context.resources.getQuantityString(
                R.plurals.pause_gesture_notification_text_minutes,
                minutes,
                triggerName,
                minutes
            )
        }

        val extendIntent = Intent(context, PauseGestureNotificationReceiver::class.java).apply {
            action = ACTION_EXTEND
            putExtra(EXTRA_TRIGGER_TAG, tag)
        }
        val extendPendingIntent = PendingIntent.getBroadcast(
            context,
            requestBase,
            extendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder(KEY_INPUT_SECONDS)
            .setLabel(context.getString(R.string.pause_gesture_notification_extend_hint))
            .build()

        val restoreIntent = Intent(context, PauseGestureNotificationReceiver::class.java).apply {
            action = ACTION_RESTORE
            putExtra(EXTRA_TRIGGER_TAG, tag)
        }
        val restorePendingIntent = PendingIntent.getBroadcast(
            context,
            requestBase xor 0x5A5A,
            restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_gesture_24)
            .setContentTitle(context.getString(R.string.pause_gesture_notification_title))
            .setContentText(bodyText)
            .setWhen(finishAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    context.getString(R.string.pause_gesture_notification_extend_action),
                    extendPendingIntent
                ).addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(false)
                    .build()
            )
            .addAction(
                0,
                context.getString(R.string.pause_gesture_notification_restore_action),
                restorePendingIntent
            )
            .build()

        runCatching {
            notificationManager.notify(tag, NOTIFICATION_ID, notification)
        }.onSuccess {
            AppLogger.event(
                LogModule.ACTION, "pause_gesture_notification_posted",
                fields = mapOf("count" to 1, "durationMs" to totalDurationMs)
            )
        }.onFailure {
            Log.e(TAG, "Failed to post pause gesture notification", it)
            AppLogger.event(
                LogModule.ACTION, "pause_gesture_notification_failed",
                LogLevel.ERROR, error = it
            )
        }
    }

    fun cancel(context: Context, tag: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(tag, NOTIFICATION_ID)
    }

    private fun createChannelIfNeeded(
        context: Context,
        notificationManager: NotificationManager
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.pause_gesture_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.pause_gesture_notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }
}

class PauseGestureNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra(PauseGestureNotification.EXTRA_TRIGGER_TAG) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val floating = FreeTriggerFloating.current()
                when (intent.action) {
                    PauseGestureNotification.ACTION_EXTEND -> {
                        val seconds = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(PauseGestureNotification.KEY_INPUT_SECONDS)
                            ?.toString()
                            ?.trim()
                            ?.toIntOrNull()
                        when {
                            floating == null -> PauseGestureNotification.cancel(context, tag)
                            seconds != null && seconds > 0 -> {
                                val applied = floating.pauseTriggerTemporarily(
                                    tag,
                                    seconds.coerceAtMost(GestureManager.MAX_PAUSE_GESTURE_SECONDS) * 1000L,
                                    extend = true
                                )
                                // The trigger may have been removed while the notification lived on.
                                if (!applied) PauseGestureNotification.cancel(context, tag)
                            }

                            else -> ToastHelper.showInUIThread(
                                context,
                                context.getString(R.string.gesture_add_action_pause_seconds_invalid)
                            )
                        }
                    }

                    PauseGestureNotification.ACTION_RESTORE -> {
                        if (floating == null) {
                            PauseGestureNotification.cancel(context, tag)
                        } else {
                            floating.restorePausedTrigger(tag)
                        }
                    }
                }
            }.onFailure {
                Log.e(
                    "PauseGestureNotificationReceiver",
                    "Failed to handle pause gesture notification action",
                    it
                )
            }
            pendingResult.finish()
        }
    }
}
