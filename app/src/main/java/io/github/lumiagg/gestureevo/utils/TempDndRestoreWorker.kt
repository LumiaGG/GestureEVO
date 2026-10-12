package io.github.lumiagg.gestureevo.utils

import androidx.core.net.toUri

import android.app.AutomaticZenRule
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.service.notification.Condition
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.annotation.RequiresApi
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object TempDndManager {
    private const val TAG = "TempDndManager"
    private const val KEY_ORIGINAL_FILTER = "original_filter"
    private const val KEY_RULE_ID = "temp_dnd_rule_id"
    private const val UNIQUE_WORK_NAME = "temp_dnd_restore_work"
    private const val DND_NOTIFICATION_CHANNEL_ID = "temp_dnd_countdown"
    private const val DND_NOTIFICATION_ID = 0x0D01
    internal val ACTION_REDEFINE = "${BuildConfig.APPLICATION_ID}.action.TEMP_DND_REDEFINE"
    internal val ACTION_STOP = "${BuildConfig.APPLICATION_ID}.action.TEMP_DND_STOP"
    internal const val KEY_INPUT_MINUTES = "temp_dnd_input_minutes"
    private const val DEFAULT_MINUTES = 15
    private const val MAX_MINUTES = 24 * 60
    private val TEMP_DND_CONDITION_ID: Uri = "gesture-evo://temp_dnd/active".toUri()
    private val DND_ENABLE_FILTER_CANDIDATES = intArrayOf(
        NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        NotificationManager.INTERRUPTION_FILTER_ALARMS,
        NotificationManager.INTERRUPTION_FILTER_NONE
    )

    fun hasUsableDndPolicyAccess(context: Context): Boolean {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
            ?: return false
        if (notificationManager.isNotificationPolicyAccessGranted) {
            return true
        }
        // Some OEM ROMs may return false here even when the package is listed in secure settings.
        val grantedPackages = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_policy_access_packages"
            ).orEmpty()
        }.getOrDefault("")
        return grantedPackages.split(":").any { it == context.packageName }
    }

    suspend fun enableTempDnd(context: Context, minutes: Int = DEFAULT_MINUTES): Boolean {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
            ?: return false

        val hasPolicyAccess = hasUsableDndPolicyAccess(context)
        if (!hasPolicyAccess) {
            Log.w(TAG, "Policy access pre-check failed, will still try enabling DND directly")
        }

        val safeMinutes = minutes.coerceIn(1, MAX_MINUTES)
        val originalFilter = context.settingsStore.readKey(KEY_ORIGINAL_FILTER, Int.MIN_VALUE)
        if (originalFilter == Int.MIN_VALUE) {
            val currentFilter = notificationManager.currentInterruptionFilter
            val safeFilter = if (currentFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                NotificationManager.INTERRUPTION_FILTER_ALL
            } else {
                currentFilter
            }
            context.settingsStore.saveKey(
                KEY_ORIGINAL_FILTER,
                safeFilter
            )
        }

        if (!enableDndFilter(context, notificationManager)) {
            Log.e(TAG, "Failed to enable temporary DND with all known filters")
            ToastHelper.showInUIThread(
                context,
                context.getString(R.string.temp_dnd_policy_access_required)
            )
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            return false
        }

        scheduleRestore(context.applicationContext, safeMinutes.toLong())
        showCountdownNotification(context.applicationContext, safeMinutes)
        ToastHelper.showInUIThread(
            context,
            context.resources.getQuantityString(R.plurals.temp_dnd_enabled_minutes, safeMinutes, safeMinutes)
        )
        return true
    }

    suspend fun restoreDnd(context: Context): Boolean {
        val originalFilter = context.settingsStore.readKey(KEY_ORIGINAL_FILTER, Int.MIN_VALUE)
        if (originalFilter == Int.MIN_VALUE) {
            return true
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
            ?: return false

        val safeOriginalFilter = if (originalFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            originalFilter
        }

        return runCatching {
            if (!restoreDndFilter(context, notificationManager, safeOriginalFilter)) {
                return@runCatching false
            }
            context.settingsStore.removeKey<Int>(KEY_ORIGINAL_FILTER)
            notificationManager.cancel(DND_NOTIFICATION_ID)
            true
        }.getOrElse {
            Log.e(TAG, "Failed to restore DND filter", it)
            false
        }
    }

    private fun scheduleRestore(context: Context, minutes: Long) {
        val restoreWork = OneTimeWorkRequestBuilder<TempDndRestoreWorker>()
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            restoreWork
        )
    }

    private fun showCountdownNotification(context: Context, minutes: Int) {
        if (NotificationPermissionUtils.showDisabledPromptIfNeeded(context)) return
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        createNotificationChannelIfNeeded(notificationManager, context)
        val redefineIntent = Intent(context, TempDndNotificationReceiver::class.java).apply {
            action = ACTION_REDEFINE
        }
        val redefinePendingIntent = PendingIntent.getBroadcast(
            context,
            1001,
            redefineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder(KEY_INPUT_MINUTES)
            .setLabel(context.getString(R.string.temp_dnd_notification_redefine_hint))
            .build()

        val stopIntent = Intent(context, TempDndNotificationReceiver::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val finishAt = System.currentTimeMillis() + minutes * 60_000L
        val notification = NotificationCompat.Builder(context, DND_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_circle_notifications_24)
            .setContentTitle(context.getString(R.string.temp_dnd_notification_title))
            .setContentText(context.resources.getQuantityString(R.plurals.temp_dnd_notification_text, minutes, minutes))
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
                    context.getString(R.string.temp_dnd_notification_redefine_action),
                    redefinePendingIntent
                ).addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(false)
                    .build()
            )
            .addAction(
                0,
                context.getString(R.string.temp_dnd_notification_stop_action),
                stopPendingIntent
            )
            .build()

        runCatching {
            notificationManager.notify(DND_NOTIFICATION_ID, notification)
        }.onFailure {
            Log.e(
                TAG,
                "Failed to post temporary DND notification. " +
                        "Check POST_NOTIFICATIONS permission and PendingIntent mutability.",
                it
            )
        }
    }

    private fun createNotificationChannelIfNeeded(
        notificationManager: NotificationManager,
        context: Context
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            DND_NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.temp_dnd_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.temp_dnd_notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private suspend fun enableDndFilter(
        context: Context,
        notificationManager: NotificationManager
    ): Boolean {
        DND_ENABLE_FILTER_CANDIDATES.forEach { filter ->
            val success = runCatching {
                val before = notificationManager.currentInterruptionFilter
                notificationManager.setInterruptionFilter(filter)
                val after = notificationManager.currentInterruptionFilter
                val applied = after == filter
                if (!applied) {
                    Log.w(
                        TAG,
                        "DND filter write ignored. requested=$filter, before=$before, after=$after"
                    )
                }
                applied
            }.getOrElse {
                Log.w(TAG, "Failed to enable DND with filter=$filter", it)
                false
            }
            if (success) {
                return true
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return enableDndRuleState(context, notificationManager, true)
        }
        return false
    }

    private suspend fun restoreDndFilter(
        context: Context,
        notificationManager: NotificationManager,
        originalFilter: Int
    ): Boolean {
        val restored = runCatching {
            val before = notificationManager.currentInterruptionFilter
            notificationManager.setInterruptionFilter(originalFilter)
            val after = notificationManager.currentInterruptionFilter
            val applied = after == originalFilter
            if (!applied) {
                Log.w(
                    TAG,
                    "Restore filter write ignored. target=$originalFilter, before=$before, after=$after"
                )
            }
            applied
        }.getOrElse {
            Log.w(TAG, "Failed to restore interruption filter directly", it)
            false
        }
        if (restored) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return enableDndRuleState(context, notificationManager, false)
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun enableDndRuleState(
        context: Context,
        notificationManager: NotificationManager,
        active: Boolean
    ): Boolean {
        val ruleId = ensureTempDndRuleId(context, notificationManager) ?: return false
        val state = if (active) Condition.STATE_TRUE else Condition.STATE_FALSE
        return runCatching {
            notificationManager.setAutomaticZenRuleState(
                ruleId,
                Condition(TEMP_DND_CONDITION_ID, "GestureEVO temp dnd", state)
            )
            true
        }.getOrElse {
            Log.e(TAG, "Failed to update temp DND automatic rule state active=$active", it)
            false
        }
    }

    private suspend fun ensureTempDndRuleId(
        context: Context,
        notificationManager: NotificationManager
    ): String? {
        val existingRuleId = context.settingsStore.readKey(KEY_RULE_ID, "")
        if (existingRuleId.isNotEmpty()) {
            val usable = runCatching {
                notificationManager.getAutomaticZenRule(existingRuleId) != null
            }.getOrDefault(false)
            if (usable) {
                return existingRuleId
            }
        }

        val createdRuleId = runCatching {
            val rule = AutomaticZenRule(
                "GestureEVO temporary DND",
                null,
                TEMP_DND_CONDITION_ID,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                true
            )
            notificationManager.addAutomaticZenRule(rule)
        }.getOrElse {
            Log.e(TAG, "Failed to create temp DND automatic rule", it)
            null
        }

        if (!createdRuleId.isNullOrEmpty()) {
            context.settingsStore.saveKey(KEY_RULE_ID, createdRuleId)
        }
        return createdRuleId
    }
}

class TempDndNotificationReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                when (intent.action) {
                    TempDndManager.ACTION_REDEFINE -> {
                        val input = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(TempDndManager.KEY_INPUT_MINUTES)
                            ?.toString()
                            ?.trim()
                            ?.toIntOrNull()
                        if (input != null && input > 0) {
                            TempDndManager.enableTempDnd(context.applicationContext, input)
                        } else {
                            ToastHelper.showInUIThread(
                                context,
                                context.getString(R.string.gesture_add_action_temp_dnd_minutes_invalid)
                            )
                        }
                    }

                    TempDndManager.ACTION_STOP -> {
                        TempDndManager.restoreDnd(context.applicationContext)
                    }
                }
            }.onFailure {
                Log.e("TempDndReceiver", "Failed to handle notification action", it)
            }
            pendingResult.finish()
        }
    }
}

class TempDndRestoreWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return if (TempDndManager.restoreDnd(applicationContext)) {
            Result.success()
        } else {
            Result.failure()
        }
    }
}
