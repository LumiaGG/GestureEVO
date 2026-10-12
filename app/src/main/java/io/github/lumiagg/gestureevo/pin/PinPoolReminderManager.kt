package io.github.lumiagg.gestureevo.pin

import androidx.core.graphics.createBitmap

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.provider.Settings
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.DEFAULT_PIN_POOL_LOCAL_RULE_EXTRACTION
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_PIN_POOL_LOCAL_RULE_EXTRACTION
import io.github.lumiagg.gestureevo.utils.NotificationPermissionUtils
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PinPoolReminderManager {
    private const val KEY_ITEM_ID = "item_id"
    private const val KEY_TRIGGER_AT = "trigger_at"
    private const val WORK_NAME_PREFIX = "pin_pool_reminder_"
    private val notificationUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun showSilentImageNotification(context: Context, bitmap: Bitmap): Boolean {
        if (!canPostNotifications(context)) return false
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return false
        createSilentChannelIfNeeded(context, notificationManager)

        val notificationBitmap = buildNotificationPreviewBitmap(bitmap)
        val notificationId = (System.currentTimeMillis() xor bitmap.width.toLong() xor bitmap.height.toLong()).toInt()
        val defaultTitle = context.getString(R.string.pin_image_notification_title)
        val defaultText = context.getString(R.string.pin_image_notification_text)
        if (isLocalRuleExtractionEnabled(context)) {
            scheduleSilentImageNotificationPost(
                context = context.applicationContext,
                notificationId = notificationId,
                sourceBitmap = bitmap,
                notificationBitmap = notificationBitmap,
                fallbackTitle = defaultTitle,
                fallbackText = defaultText
            )
            return true
        }
        return postSilentImageNotification(
            context = context,
            notificationId = notificationId,
            notificationBitmap = notificationBitmap,
            contentTitle = defaultTitle,
            contentText = defaultText
        )
    }

    fun schedule(context: Context, item: PinPoolItem, triggerAt: Long) {
        val delay = max(0L, triggerAt - System.currentTimeMillis())
        val inputData = Data.Builder()
            .putString(KEY_ITEM_ID, item.id)
            .putLong(KEY_TRIGGER_AT, triggerAt)
            .build()
        val request = OneTimeWorkRequestBuilder<PinPoolReminderWorker>()
            .setInputData(inputData)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_PREFIX + item.id,
            androidx.work.ExistingWorkPolicy.REPLACE,
            request
        )
    }

    internal suspend fun showReminder(context: Context, itemId: String, triggerAt: Long) {
        if (!canPostNotifications(context)) return
        val item = PinPoolStore.findPin(context, itemId) ?: return
        PinPoolStore.updateReminderAt(context, item, 0L)
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannelIfNeeded(context, notificationManager)

        val image = item.imagePath?.let { loadNotificationBitmap(it) }
        val contentTitle = when (item.type) {
            PinType.TEXT -> context.getString(R.string.pin_pool_reminder_notification_title)
            PinType.IMAGE -> context.getString(R.string.pin_pool_reminder_notification_title)
        }
        val contentText = when (item.type) {
            PinType.TEXT -> item.text.orEmpty().ifBlank {
                context.getString(R.string.pin_pool_reminder_text_fallback)
            }

            PinType.IMAGE -> context.getString(R.string.pin_pool_reminder_image_text)
        }
        val notificationImage = image?.let { buildNotificationPreviewBitmap(it) }
        val contentIntent = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            Intent(context, PinPoolFloating::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(PinPoolFloating.EXTRA_SCROLL_TO_PIN_ID, item.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = buildReminderNotificationBuilder(
            context = context,
            contentTitle = contentTitle,
            contentText = contentText,
            contentIntent = contentIntent,
            triggerAt = triggerAt,
            notificationImage = notificationImage
        )

        if (item.type == PinType.IMAGE && image != null && isLocalRuleExtractionEnabled(context)) {
            scheduleReminderNotificationPost(
                context = context.applicationContext,
                notificationId = item.id.hashCode(),
                sourceBitmap = image,
                notificationBitmap = notificationImage,
                contentIntent = contentIntent,
                triggerAt = triggerAt,
                fallbackTitle = contentTitle,
                fallbackText = contentText
            )
            return
        }
        runCatching {
            val notification = builder.build().apply {
                flags = flags or Notification.FLAG_NO_CLEAR
            }
            postNotification(context, item.id.hashCode(), notification)
        }.onFailure {
            Log.e(TAG, "Failed to post pin pool reminder", it)
        }
    }

    private fun createChannelIfNeeded(context: Context, notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.pin_pool_reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.pin_pool_reminder_channel_desc)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createSilentChannelIfNeeded(
        context: Context,
        notificationManager: NotificationManager
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            SILENT_IMAGE_CHANNEL_ID,
            context.getString(R.string.pin_image_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.pin_image_notification_channel_desc)
            enableVibration(false)
            setSound(null, null)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun scheduleSilentImageNotificationPost(
        context: Context,
        notificationId: Int,
        sourceBitmap: Bitmap,
        notificationBitmap: Bitmap,
        fallbackTitle: String,
        fallbackText: String
    ) {
        val backgroundBitmap = sourceBitmap.copyForBackgroundExtraction() ?: return
        notificationUpdateScope.launch {
            val notificationText = runCatching {
                PinImageNotificationTextExtractor.extract(context, backgroundBitmap)
            }.onFailure {
                Log.w(TAG, "Failed to extract silent pin image notification text", it)
            }.getOrNull()
            backgroundBitmap.recycleIfNeeded()
            val contentTitle = notificationText?.title ?: fallbackTitle
            val contentText = notificationText?.content ?: fallbackText
            if (!canPostNotifications(context)) return@launch
            postSilentImageNotification(
                context = context,
                notificationId = notificationId,
                notificationBitmap = notificationBitmap,
                contentTitle = contentTitle,
                contentText = contentText
            )
        }
    }

    private fun scheduleReminderNotificationPost(
        context: Context,
        notificationId: Int,
        sourceBitmap: Bitmap,
        notificationBitmap: Bitmap?,
        contentIntent: PendingIntent,
        triggerAt: Long,
        fallbackTitle: String,
        fallbackText: String
    ) {
        val backgroundBitmap = sourceBitmap.copyForBackgroundExtraction() ?: return
        notificationUpdateScope.launch {
            val notificationText = runCatching {
                PinImageNotificationTextExtractor.extract(context, backgroundBitmap)
            }.onFailure {
                Log.w(TAG, "Failed to extract pin pool reminder notification text", it)
            }.getOrNull()
            backgroundBitmap.recycleIfNeeded()
            val contentTitle = notificationText?.title ?: fallbackTitle
            val contentText = notificationText?.content ?: fallbackText
            if (!canPostNotifications(context)) return@launch
            val builder = buildReminderNotificationBuilder(
                context = context,
                contentTitle = contentTitle,
                contentText = contentText,
                contentIntent = contentIntent,
                triggerAt = triggerAt,
                notificationImage = notificationBitmap
            )
            runCatching {
                val notification = builder.build().apply {
                    flags = flags or Notification.FLAG_NO_CLEAR
                }
                postNotification(context, notificationId, notification)
            }.onFailure {
                Log.e(TAG, "Failed to update pin pool reminder notification", it)
            }
        }
    }

    private fun postSilentImageNotification(
        context: Context,
        notificationId: Int,
        notificationBitmap: Bitmap,
        contentTitle: String,
        contentText: String
    ): Boolean {
        val notification = buildSilentImageNotification(
            context = context,
            notificationBitmap = notificationBitmap,
            contentTitle = contentTitle,
            contentText = contentText
        )
        return runCatching {
            postNotification(context, notificationId, notification)
        }.onFailure {
            NotificationPermissionUtils.showDisabledPromptIfNeeded(context)
            Log.e(TAG, "Failed to post silent pin image notification", it)
        }.isSuccess
    }

    private fun buildSilentImageNotification(
        context: Context,
        notificationBitmap: Bitmap,
        contentTitle: String,
        contentText: String
    ): Notification {
        return NotificationCompat.Builder(context, SILENT_IMAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_push_pin_24)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setLargeIcon(notificationBitmap)
            .setStyle(buildBigPictureStyle(notificationBitmap))
            .setAutoCancel(false)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setSilent(true)
            .setDefaults(0)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .build()
            .apply {
                flags = flags or Notification.FLAG_NO_CLEAR
            }
    }

    private fun buildReminderNotificationBuilder(
        context: Context,
        contentTitle: String,
        contentText: String,
        contentIntent: PendingIntent,
        triggerAt: Long,
        notificationImage: Bitmap?
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_circle_notifications_24)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setWhen(triggerAt)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(VIBRATION_PATTERN)
            .setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
        if (notificationImage != null) {
            builder.setLargeIcon(notificationImage)
                .setStyle(buildBigPictureStyle(notificationImage))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        }
        return builder
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (NotificationPermissionUtils.canPostNotifications(context)) return true
        NotificationPermissionUtils.showDisabledPromptIfNeeded(context)
        return false
    }

    private suspend fun isLocalRuleExtractionEnabled(context: Context): Boolean {
        return context.settingsStore.readKey(
            KEY_PIN_POOL_LOCAL_RULE_EXTRACTION,
            DEFAULT_PIN_POOL_LOCAL_RULE_EXTRACTION
        )
    }

    private fun loadNotificationBitmap(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, 1024, 1024)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun buildNotificationPreviewBitmap(bitmap: Bitmap): Bitmap {
        val outputWidth = NOTIFICATION_IMAGE_WIDTH
        val sourceAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val outputHeight = (outputWidth / sourceAspect).roundToInt().coerceIn(
            NOTIFICATION_IMAGE_MIN_HEIGHT,
            NOTIFICATION_IMAGE_MAX_HEIGHT
        )
        val scale = min(
            outputWidth.toFloat() / bitmap.width,
            outputHeight.toFloat() / bitmap.height
        )
        val drawWidth = (bitmap.width * scale).coerceAtLeast(1f)
        val drawHeight = (bitmap.height * scale).coerceAtLeast(1f)
        val left = (outputWidth - drawWidth) / 2f
        val top = (outputHeight - drawHeight) / 2f
        val preview = createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview)
        canvas.drawColor(Color.TRANSPARENT)
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + drawWidth, top + drawHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return preview
    }

    private fun buildBigPictureStyle(bitmap: Bitmap): NotificationCompat.BigPictureStyle {
        val style = NotificationCompat.BigPictureStyle()
            .bigPicture(bitmap)
            .bigLargeIcon(null as Bitmap?)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) style.showBigPictureWhenCollapsed(true)
        return style
    }

    /** Permission can be revoked after canPostNotifications; callers handle this failure. */
    private fun postNotification(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Notification permission denied")
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun Bitmap.copyForBackgroundExtraction(): Bitmap? {
        return runCatching {
            if (isRecycled) return null
            copy(Bitmap.Config.ARGB_8888, false)
        }.onFailure {
            Log.w(TAG, "Failed to copy bitmap for background extraction", it)
        }.getOrNull()
    }

    private fun Bitmap.recycleIfNeeded() {
        if (!isRecycled) recycle()
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private const val TAG = "PinPoolReminderManager"
    private const val CHANNEL_ID = "pin_pool_reminder_alert"
    private const val SILENT_IMAGE_CHANNEL_ID = "pin_image_silent_top"
    private const val NOTIFICATION_IMAGE_WIDTH = 1024
    private const val NOTIFICATION_IMAGE_MIN_HEIGHT = 256
    private const val NOTIFICATION_IMAGE_MAX_HEIGHT = 576
    private val VIBRATION_PATTERN = longArrayOf(0L, 250L, 120L, 250L)
}

class PinPoolReminderWorker(
    context: Context,
    workerParameters: WorkerParameters
) : Worker(context, workerParameters) {
    override fun doWork(): Result {
        val itemId = inputData.getString("item_id") ?: return Result.failure()
        val triggerAt = inputData.getLong("trigger_at", System.currentTimeMillis())
        kotlinx.coroutines.runBlocking {
            PinPoolReminderManager.showReminder(applicationContext, itemId, triggerAt)
        }
        return Result.success()
    }
}
