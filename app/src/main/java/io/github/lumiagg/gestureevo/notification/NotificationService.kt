package io.github.lumiagg.gestureevo.notification

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceNotificationActionEvent
import io.github.lumiagg.gestureevo.gesture.NotificationActionEvent
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_SHOW_NOTIFICATION_ICON
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.RecentAppsRecorder
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.uriBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class NotificationEvent(
    val packageName: String,
    val freeForm: Boolean
)

class NotificationService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    data class NotificationInfo(
        val key: String,
        val packageName: String,
        val contentIntent: PendingIntent,
        val timestamp: Long,
    ) : Comparable<NotificationInfo> {
        override fun compareTo(other: NotificationInfo): Int {
            // Sort in descending order by timestamp (most recent first)
            return other.timestamp.compareTo(timestamp)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: rebind")
        requestRebind(ComponentName(this, NotificationService::class.java))
        serviceScope.launch(Dispatchers.IO) {
            notificationEventBus.collect {
                getNotification(it.packageName)?.let { notificationInfo ->
                    handleNotificationClick(notificationInfo, it.freeForm)
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        serviceScope.launch {
            val isListenerWhite = getAPPDao().findByPkg(sbn.packageName)
                ?.getState(APPModel.FLAG_NOTIFICATION_APPS_ENABLE) == true
            if (!isListenerWhite) return@launch

            val notificationInfo = processNotification(sbn)
            // Store or update notification info (keep newest for each application)
            notificationInfo?.let {
                notificationMap[it.packageName] = it

                if (applicationContext.settingsStore.readKey(KEY_SHOW_NOTIFICATION_ICON, false)
                ) {
                    produceNotificationActionEvent(NotificationActionEvent(isAdd = true))
                }
            }
        }
    }

    private suspend fun processNotification(sbn: StatusBarNotification): NotificationInfo? =
        withContext(Dispatchers.IO) {
            val notification = sbn.notification
            NotificationInfo(
                key = sbn.key,
                packageName = sbn.packageName,
                contentIntent = notification.contentIntent ?: return@withContext null,
                timestamp = sbn.postTime,
            )
        }

    private suspend fun handleNotificationClick(info: NotificationInfo, freeForm: Boolean) =
        withContext(Dispatchers.Default) {
            val link = uriBuilder(GestureActionType.OPEN_APP)
                .appendQueryParameter("pkg", info.packageName)
                .appendQueryParameter("freeform", freeForm.toString())
            RecentAppsRecorder.getRecentApps(1, excludeState = APPModel.FLAG_RECENT_APPS_DISABLE)
                .firstOrNull()?.let { recentApp ->
                    if (recentApp.pkgName == info.packageName) {
                        link.appendQueryParameter("newWindow", true.toString())
                    }
                }
            val actionProxy = ActionProxy(applicationContext, link.toString())
            try {
                actionProxy.execute()
                info.contentIntent.send()
                Log.d("NotificationService", "Successfully clicked notification")
                removeNotification(info.key)
            } catch (e: PendingIntent.CanceledException) {
                Log.e("NotificationService", "Error clicking notification", e)
            }
        }

    private suspend fun removeNotification(key: String) =
        withContext(Dispatchers.Main) {
            cancelNotification(key)
        }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        serviceScope.launch {
            val isListenerWhite = getAPPDao().findByPkg(sbn.packageName)
                ?.getState(APPModel.FLAG_NOTIFICATION_APPS_ENABLE) == true
            if (!isListenerWhite) return@launch

            sbn.let {
                // Remove notification for the package
                notificationMap.remove(it.packageName)
                if (applicationContext.settingsStore.readKey(KEY_SHOW_NOTIFICATION_ICON, false)
                ) {
                    produceNotificationActionEvent(NotificationActionEvent(isAdd = false))
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        notificationMap.clear()
    }

    companion object {
        private const val TAG = "NotificationService"
        private val notificationMap = ConcurrentHashMap<String, NotificationInfo>()
        val notificationEventBus = MutableSharedFlow<NotificationEvent>()

        suspend fun produceNotificationEvent(event: NotificationEvent) =
            withContext(Dispatchers.IO) {
                notificationEventBus.emit(event)
            }

        // Returns all notifications sorted chronologically.
        fun getSortedNotifications(): List<NotificationInfo> {
            return notificationMap.values.sorted()
        }

        // Returns notification info for a specific package.
        fun getNotification(packageName: String): NotificationInfo? {
            return notificationMap[packageName]
        }
    }
}
