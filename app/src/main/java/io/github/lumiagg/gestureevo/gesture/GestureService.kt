package io.github.lumiagg.gestureevo.gesture

import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.utils.CurrentPackageProvider
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.RecentAppsRecorder
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.shizuku.initShizukuService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume


class GestureService : AccessibilityService(), CoroutineScope {
    lateinit var job: Job

    // CoroutineScope implementation
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    private var powerManager: PowerManager? = null
    private val gestureBroadcastReceiver = GestureBroadcastReceiver()
    var gestureManager: GestureManager? = null
        private set
    private var lastNightMode = Configuration.UI_MODE_NIGHT_UNDEFINED

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        CurrentPackageProvider.onAccessibilityEvent(event)
        gestureManager?.onWindowStateChanged(event)
    }

    override fun onInterrupt() {
        AppLogger.event(
            LogModule.SERVICE, "accessibility_interrupted",
            LogLevel.WARN)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLogger.event(
            LogModule.SERVICE, "accessibility_connected")
        CurrentPackageProvider.setService(this)
        initReceiver()
        launch(Dispatchers.Main) {
            delay(1000)
            gestureManager = GestureManager(this@GestureService, this)
        }
        launch(Dispatchers.IO) {
            DynamicColorUtils.captureColor(
                this@GestureService,
                this@GestureService
            )
            RecentAppsRecorder.initLauncherPackagesInDB(applicationContext)
            initShizukuService(applicationContext)
        }
    }

    private fun initReceiver() {
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gestureBroadcastReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(gestureBroadcastReceiver, filter)
        }
        gestureBroadcastReceiver.isReceiverRegistered = true
    }

    override fun onCreate() {
        super.onCreate()
        job = Job()

        setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        launch {
            IconHelper.initIconConfig(applicationContext)
        }
    }

    override fun onDestroy() {
        AppLogger.event(
            LogModule.SERVICE, "accessibility_destroyed",
            LogLevel.WARN)
        super.onDestroy()
        gestureManager?.destroy()
        gestureManager = null
        if (gestureBroadcastReceiver.isReceiverRegistered) {
            unregisterReceiver(gestureBroadcastReceiver)
        }
        CurrentPackageProvider.clearService()
        io.github.lumiagg.gestureevo.pin.PinDragCancelOverlay.hide(notify = false)
        job.cancel()
    }

    suspend fun setActionViewTouchEnabled(enabled: Boolean) = withContext(Dispatchers.Main) {
        gestureManager?.setActionViewTouchEnabled(enabled)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val nightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightMode != lastNightMode) {
            lastNightMode = nightMode
            DynamicColorUtils.refreshSurfaceColors(createConfigurationContext(newConfig))
        }
        super.onConfigurationChanged(newConfig)
        gestureManager?.onConfigurationChanged(newConfig)
    }

    private val takeCaptureService: ExecutorService =
        ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(10))

    suspend fun takeScreenshot(): Bitmap? =
        withTimeoutOrNull(3500L) {
            suspendCancellableCoroutine { continuation ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    takeScreenshot(0, takeCaptureService, object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            if (!continuation.isActive) {
                                screenshot.hardwareBuffer.close()
                                return
                            }
                            val bitmap = runCatching {
                                var hardwareBitmap: Bitmap? = null
                                try {
                                    hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                        screenshot.hardwareBuffer,
                                        screenshot.colorSpace
                                    )
                                    hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                                } finally {
                                    hardwareBitmap?.recycle()
                                    screenshot.hardwareBuffer.close()
                                }
                            }.onFailure {
                                AppLogger.event(
                                    LogModule.SERVICE, "screenshot_failed",
                                    LogLevel.ERROR, error = it)
                            }.getOrNull()
                            if (bitmap != null) {
                                Log.d(TAG, "takeScreenshot success")
                            } else {
                                Log.d(TAG, "takeScreenshot onFailure: bitmap is null")
                            }
                            continuation.resume(bitmap) { bitmap?.recycle() }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (!continuation.isActive) return
                            Log.d(TAG, "takeScreenshot onFailure: $errorCode")
                            continuation.resume(null) {}
                        }
                    })
                } else {
                    if (continuation.isActive) {
                        continuation.resume(null) {}
                    }
                }
            }
        }.also {
            if (it == null) {
                Log.d(TAG, "takeScreenshot timeout or null")
            }
        }

    suspend fun takeManagedScreenshot(): ManagedBitmap? =
        takeScreenshot()?.let(ManagedBitmap::from)

    suspend fun performClickAsync(
        x: Float,
        y: Float,
        duration: Long = 100L
    ): Boolean = suspendCancellableCoroutine { continuation ->
        if (x < 0 || y < 0) {
            continuation.resume(false) { Log.d(TAG, "Click cancelled: negative position") }
            return@suspendCancellableCoroutine
        }

        val clickPath = Path().apply {
            moveTo(x, y)
        }

        val gestureBuilder = GestureDescription.Builder()
        val gesture = GestureDescription.StrokeDescription(
            clickPath,
            0,  // Start time
            duration  // Duration
        )

        gestureBuilder.addStroke(gesture)
        Log.d(TAG, "performClickAsync: start click")
        dispatchGesture(
            gestureBuilder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "onCompleted: click completed")
                    continuation.resume(true) { Log.d(TAG, "Click cancelled: after completion") }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "onCancelled: click cancelled")
                    continuation.resume(false) { Log.d(TAG, "Click cancelled: by system") }
                }
            },
            null
        )
    }

    suspend fun performSwipeAsync(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = 600L,
        holdDuration: Long = 0L,
        useDecelerationCurve: Boolean = true
    ): Boolean = suspendCancellableCoroutine { continuation ->
        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
            Log.d(TAG, "Swipe cancelled: negative position")
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        val swipePath = if (useDecelerationCurve) {
            createDecelerationPath(startX, startY, endX, endY)
        } else {
            Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
        }

        // If hold duration is needed, use willContinue param of StrokeDescription
        val gesture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            GestureDescription.StrokeDescription(swipePath, 0, duration, holdDuration > 0)
        } else {
            GestureDescription.StrokeDescription(swipePath, 0, duration)
        }

        val gestureBuilder = GestureDescription.Builder().apply {
            addStroke(gesture)

            // If hold duration is specified, append a stationary stroke
            if (holdDuration > 0) {
                val holdPath = Path().apply {
                    moveTo(endX, endY)
                    lineTo(endX, endY)  // Stay stationary at end position
                }
                val holdStroke = GestureDescription.StrokeDescription(
                    holdPath,
                    duration,  // Start after swipe duration
                    holdDuration
                )
                addStroke(holdStroke)
            }
        }

        Log.d(
            TAG,
            "performSwipeAsync: start swipe from ($startX,$startY) to ($endX,$endY), duration=${duration}ms, hold=${holdDuration}ms"
        )

        dispatchGesture(
            gestureBuilder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "onCompleted: swipe completed successfully")
                    continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "onCancelled: swipe cancelled by system")
                    continuation.resume(false)
                }
            },
            null
        )
    }

    /**
     * Creates a swipe path with deceleration effect.
     * Simulates natural finger deceleration using a cubic Bezier curve.
     */
    private fun createDecelerationPath(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Path {
        return Path().apply {
            moveTo(startX, startY)

            // Calculate control points to create deceleration curve
            // Create cubic Bezier curve using two control points
            val deltaX = endX - startX
            val deltaY = endY - startY

            // First control point: near start, sets higher initial speed
            val cp1X = startX + deltaX * 0.2f
            val cp1Y = startY + deltaY * 0.2f

            // Second control point: near end, sets deceleration effect
            val cp2X = startX + deltaX * 0.7f
            val cp2Y = startY + deltaY * 0.7f

            cubicTo(cp1X, cp1Y, cp2X, cp2Y, endX, endY)
        }
    }


    private inner class GestureBroadcastReceiver : BroadcastReceiver() {
        var isReceiverRegistered = false
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "onReceive: ${intent.action}")
            when (intent.action) {

                Intent.ACTION_USER_PRESENT -> {
                    gestureManager?.activeSomeFloating()
                }

                Intent.ACTION_SCREEN_OFF -> {
                    if (powerManager == null) {
                        powerManager = getSystemService(POWER_SERVICE) as PowerManager
                    }
                    // This broadcast may arrive after ACTION_USER_PRESENT, leading to deadlock
                    if (powerManager?.isInteractive == true) {
                        return
                    }
                    gestureManager?.inactiveAllFloating()
                }
            }
        }
    }


    companion object {
        private const val TAG = "GestureService"

    }
}
