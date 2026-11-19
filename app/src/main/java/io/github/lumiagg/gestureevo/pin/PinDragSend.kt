package io.github.lumiagg.gestureevo.pin

import android.annotation.SuppressLint
import androidx.core.graphics.createBitmap

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PersistableBundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.lumiagg.gestureevo.drop.ExternalDropPolicy
import io.github.lumiagg.gestureevo.utils.DeleteImageWorker
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

object PinDragSend {
    private const val CLEANUP_DELAY_SECONDS = 45L
    private val SHARE_PACKAGES = listOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.tim",
        "com.tencent.qqlite",
        "com.tencent.minihd.qq",
        "com.tencent.wework",
        "com.alibaba.android.rimet",
    )

    fun start(view: View, clip: ClipData, shadowView: View = view): Boolean {
        if (!view.isAttachedToWindow) return false
        clip.description.extras = PersistableBundle(clip.description.extras ?: PersistableBundle()).apply {
            putBoolean(ExternalDropPolicy.EVO_SOURCE_EXTRA, true)
        }
        val flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
        return view.startDragAndDrop(clip, snapshotShadow(shadowView), clip, flags)
    }

    private fun snapshotShadow(view: View): View.DragShadowBuilder {
        val srcW = view.width
        val srcH = view.height
        if (srcW <= 0 || srcH <= 0) return View.DragShadowBuilder(view)
        val max = (256f * view.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val scale = minOf(1f, max.toFloat() / srcW, max.toFloat() / srcH)
        val width = (srcW * scale).toInt().coerceAtLeast(1)
        val height = (srcH * scale).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        view.draw(canvas)
        return object : View.DragShadowBuilder() {
            override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
                outShadowSize.set(width, height)
                outShadowTouchPoint.set(width / 2, height / 2)
            }

            override fun onDrawShadow(canvas: Canvas) {
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        }
    }

    fun clipForText(text: String): ClipData {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return ClipData("text", arrayOf("text/plain"), ClipData.Item(text, intent, null))
    }

    fun clipForImage(context: Context, uri: Uri): ClipData {
        grantReadToKnownApps(context, uri)
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            setDataAndType(uri, mime)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, "image", uri)
        }
        return ClipData(
            "image",
            arrayOf(mime, "image/*", "image/jpeg", "image/png"),
            ClipData.Item(null, intent, uri)
        )
    }

    fun publishBitmap(context: Context, bitmap: Bitmap): Uri? {
        return insertMediaStore(context, "image/jpeg") { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
    }

    fun publishFile(context: Context, file: File): Uri? {
        if (!file.exists()) return null
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        if (decoded != null) {
            try {
                return publishBitmap(context, decoded)
            } finally {
                decoded.recycle()
            }
        }
        val mime = if (file.name.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
        return insertMediaStore(context, mime) { output ->
            FileInputStream(file).use { input -> input.copyTo(output) }
            true
        }
    }

    private fun insertMediaStore(
        context: Context,
        mime: String,
        write: (java.io.OutputStream) -> Boolean,
    ): Uri? {
        val resolver = context.contentResolver
        val extension = if (mime.contains("png")) "png" else "jpg"
        val displayName = "GestureEVO_drag_${System.currentTimeMillis()}.$extension"

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Prefer hidden media directory (.GestureEVO) to avoid gallery pollution and cloud backup.
            insertWithRelativePath(resolver, mime, displayName, Environment.DIRECTORY_PICTURES + "/.GestureEVO")
                ?: insertWithRelativePath(resolver, mime, displayName, Environment.DIRECTORY_PICTURES + "/GestureEVO")
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } ?: return null

        val written = runCatching {
            resolver.openOutputStream(uri)?.use(write) == true
        }.getOrDefault(false)
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null)
        }
        scheduleCleanup(context, uri)
        return uri
    }

    private fun insertWithRelativePath(
        resolver: ContentResolver,
        mime: String,
        displayName: String,
        relativePath: String,
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
    }

    private fun scheduleCleanup(context: Context, uri: Uri) {
        val work = OneTimeWorkRequestBuilder<DeleteImageWorker>()
            .setInputData(workDataOf("uri" to uri.toString()))
            .setInitialDelay(CLEANUP_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(work)
    }

    private fun grantReadToKnownApps(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        SHARE_PACKAGES.forEach { pkg ->
            runCatching { context.grantUriPermission(pkg, uri, flags) }
        }
    }
}

class LongPressDragDetector(
    private val view: View,
    private val onLongPress: () -> Unit = {},
    private val onDragSend: () -> Boolean,
    private val onLongPressUp: () -> Unit,
) : View.OnTouchListener {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var dragStarted = false
    private val longPressRunnable = Runnable {
        longPressed = true
        VibratorUtils.vibrator(view.context, true)
        view.parent?.requestDisallowInterceptTouchEvent(true)
        onLongPress()
    }

    // Normal taps return false for View to handle; only long-press dragging is consumed.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                longPressed = false
                dragStarted = false
                view.removeCallbacks(longPressRunnable)
                view.postDelayed(longPressRunnable, longPressTimeout)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(event.rawX - downX)
                val dy = kotlin.math.abs(event.rawY - downY)
                if (!longPressed && (dx > slop || dy > slop)) {
                    view.removeCallbacks(longPressRunnable)
                    return false
                }
                if (longPressed && !dragStarted && (dx > slop || dy > slop)) {
                    dragStarted = true
                    return onDragSend()
                }
                return longPressed
            }

            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(longPressRunnable)
                view.parent?.requestDisallowInterceptTouchEvent(false)
                if (longPressed && !dragStarted) {
                    onLongPressUp()
                    return true
                }
                return dragStarted
            }

            MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPressRunnable)
                view.parent?.requestDisallowInterceptTouchEvent(false)
                longPressed = false
                dragStarted = false
                return false
            }
        }
        return false
    }
}
