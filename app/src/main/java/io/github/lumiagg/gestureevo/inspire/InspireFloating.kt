package io.github.lumiagg.gestureevo.inspire

import io.github.lumiagg.gestureevo.databinding.InspireViewBinding

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.WindowManager
import android.widget.Toast
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.GestureManager.InteractionMode
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.gesture.ui.GestureFluidAppearance
import io.github.lumiagg.gestureevo.inspire.imageeditor.InspireImageEditorActivity
import io.github.lumiagg.gestureevo.inspire.ui.InspireView
import io.github.lumiagg.gestureevo.inspire.ui.InspireViewCallBack
import io.github.lumiagg.gestureevo.pin.PinPoolStore
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_DEFAULT_IMAGE_TEXT_OVERLAY
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_DEFAULT_SHARE_IMAGE_APP
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_INSPIRE_SELECTION_VIBRATE
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.MlKitOcr
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.postOnMain
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow

class InspireFloating(private val context: GestureService) :
    BaseFloatingWindow(context, context, "InspireFloating") {
    private val inspireView: InspireView by lazy {
        val themeContext: Context = ContextThemeWrapper(context, R.style.Theme_GestureEVO)
        InspireViewBinding.inflate(LayoutInflater.from(themeContext)).root
    }

    private val layoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams()
    private var transitionJob: Job? = null
    private var transitionPending = false
    private var transitionShown = false
    private var screenshotDone = false
    private var activityLaunching = false
    private var isAttached = false
    private var sessionScreenshot: ManagedBitmap? = null
    private var sessionCaptureJob: Job? = null
    private var sessionGeneration = 0L
    @Volatile
    private var lastSessionCaptureAtMs = 0L
    private var textBoxDetectionJob: Job? = null
    private var sessionTextBoxes: List<Rect>? = null
    private var previewSelectionRect: Rect? = null
    private var textBoxGeneration = 0L
    private var textBoxPreviewEnabled = false
    private var textBoxPreviewUpdateScheduled = false
    private var sessionForceImageTextSelection = false
    private var sessionTriggerSide: String? = null
    private val textBoxPreviewUpdateRunnable = Runnable {
        textBoxPreviewUpdateScheduled = false
        updateTextBoxPreview()
    }
    private val imageShareLauncher by lazy { ImageShareLauncher(context) }

    init {
        initFloatingWindow()
        initInspireView()
    }

    private fun initFloatingWindow() {
        layoutParams.configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            title = "GestureEVO:InspireFloating",
        )
    }

    private fun ensureAttached() {
        if (inspireView.isAttachedToWindow) {
            isAttached = true
            return
        }
        addViewSafely(inspireView, layoutParams) { isAttached = true }
        isAttached = inspireView.isAttachedToWindow
    }

    private fun detachIfNeeded() {
        detachViewSafely(inspireView) { isAttached = false }
    }

    private fun initInspireView() {
        inspireView.visibility = GONE
        inspireView.inspireViewCallBack = object : InspireViewCallBack {
            var taskJob: Job? = null
            var lastLongPressUptimeMs = 0L
            override fun onSessionCaptureRequested(onCaptured: () -> Unit) {
                requestSessionScreenshot(onCaptured)
            }

            override fun onSelectionStarted() {
                textBoxPreviewEnabled = true
                previewSelectionRect = null
                startFullScreenTextBoxDetectionIfReady()
            }

            override fun onDragMove(dragSelectRect: Rect) {
                requestTextBoxPreview(dragSelectRect)
            }
            override fun onDragUp(dragSelectRect: Rect) {
                if (SystemClock.uptimeMillis() - lastLongPressUptimeMs < 500L) {
                    return
                }
                if (taskJob?.isActive == true) {
                    Log.d("onDragUp", "onDragUp: task running")
                    return
                }
                taskJob = context.launch {
                    val targetRect = resolveTargetRect(dragSelectRect) ?: dragSelectRect
                    processScreenContent(targetRect)
                }
            }

            override fun onDragCancel() {
                context.launch {
                    taskJob?.cancelAndJoin()
                    taskJob = null
                    hide()
                }
            }

            override fun onPinImage(dragSelectRect: Rect) {
                takeScreenShot(dragSelectRect) { img ->
                    InspireHelperUtils.pinImage(context, img)
                    withContext(Dispatchers.Main) { hide() }
                }
            }

            override fun onCopyImage(dragSelectRect: Rect) {
                lastLongPressUptimeMs = SystemClock.uptimeMillis()
                takeScreenShot(dragSelectRect) { img ->
                    InspireHelperUtils.copyBitmapToClipboard(context, img, autoSaveToPinPool = true)
                    withContext(Dispatchers.Main) { hide() }
                }
            }

            override fun onPinImageLongPress(dragSelectRect: Rect) {
                lastLongPressUptimeMs = SystemClock.uptimeMillis()
                takeScreenShot(dragSelectRect) { img ->
                    PinPoolStore.addImagePin(context, img.requireBitmap())
                    withContext(Dispatchers.Main) { hide() }
                }
            }

            override fun onEditImage(dragSelectRect: Rect) {
                if (taskJob?.isActive == true) return
                taskJob = context.launch {
                    try {
                        editSelectedImage(dragSelectRect)
                    } finally {
                        taskJob = null
                    }
                }
            }

            override fun onTextBoxClick(textBox: Rect) {
                if (taskJob?.isActive == true) return
                taskJob = context.launch {
                    try {
                        copyDetectedTextBox(textBox)
                    } finally {
                        taskJob = null
                    }
                }
            }

            override fun onSaveImage(dragSelectRect: Rect, isPress: Boolean) {
                if (isPress) {
                    lastLongPressUptimeMs = SystemClock.uptimeMillis()
                }
                takeScreenShot(dragSelectRect) { img ->
                    InspireHelperUtils.saveImageToGallery(context, img, isPress)
                    PinPoolStore.addScreenContentImageIfEnabled(context, img.requireBitmap())
                    withContext(Dispatchers.Main) { hide() }
                }
            }

            override fun onSharImage(dragSelectRect: Rect, isPress: Boolean) {
                if (isPress) {
                    lastLongPressUptimeMs = SystemClock.uptimeMillis()
                }
                takeScreenShot(dragSelectRect) { img ->
                    val targetPackage = if (isPress) {
                        ""
                    } else {
                        context.settingsStore.readKey(KEY_DEFAULT_SHARE_IMAGE_APP, "")
                    }
                    val forceChooser = isPress || targetPackage.isEmpty()
                    withContext(Dispatchers.Main) {
                        imageShareLauncher.shareImage(
                            bitmap = img.requireBitmap(),
                            targetPackage = targetPackage.ifEmpty { null },
                            targetActivity = null,
                            forceChooser = forceChooser
                        )
                        hide()
                    }
                }
            }
        }
    }

    private fun requestSessionScreenshot(onCaptured: () -> Unit = {}) {
        context.launch(Dispatchers.Main) {
            if (awaitSessionScreenshot()) {
                onCaptured()
            }
        }
    }

    private fun startSessionCapture(): Job {
        sessionCaptureJob?.let { return it }
        val captureGeneration = sessionGeneration
        return context.launch {
            withContext(Dispatchers.Main) {
                layoutParams.alpha = 0f
                updateViewLayoutSafely(inspireView, layoutParams)
            }
            try {
                delay(SCREENSHOT_HIDE_SETTLE_MS)
                lastSessionCaptureAtMs = SystemClock.uptimeMillis()
                val captured = context.takeManagedScreenshot()
                withContext(Dispatchers.Main) {
                    if (captureGeneration == sessionGeneration) {
                        sessionScreenshot?.close()
                        sessionScreenshot = captured
                        startFullScreenTextBoxDetectionIfReady()
                    } else {
                        captured?.close()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (captureGeneration == sessionGeneration) {
                        layoutParams.alpha = 1f
                        updateViewLayoutSafely(inspireView, layoutParams)
                    }
                }
            }
        }.also { sessionCaptureJob = it }
    }

    private suspend fun awaitSessionScreenshot(): Boolean {
        repeat(SESSION_CAPTURE_MAX_ATTEMPTS) {
            val generation = withContext(Dispatchers.Main) { sessionGeneration }
            val job = withContext(Dispatchers.Main) {
                sessionCaptureJob ?: startSessionCapture()
            }
            job.join()
            val (isCurrentGeneration, succeeded) = withContext(Dispatchers.Main) {
                val isCurrent = generation == sessionGeneration
                isCurrent to (isCurrent && !job.isCancelled && sessionScreenshot?.bitmapOrNull != null)
            }
            if (!isCurrentGeneration) return false
            if (succeeded) return true
            withContext(Dispatchers.Main) {
                if (sessionCaptureJob === job) sessionCaptureJob = null
            }
        }
        return false
    }

    private suspend fun acquireSessionScreenshot(): ManagedBitmap? {
        if (!awaitSessionScreenshot()) return null
        return withContext(Dispatchers.Main) { sessionScreenshot?.acquire() }
    }

    private fun invalidateSessionCapture() {
        sessionGeneration += 1
        resetTextBoxPreview()
        sessionCaptureJob?.cancel()
        sessionCaptureJob = null
        sessionScreenshot?.close()
        sessionScreenshot = null
    }

    private suspend fun hideForScreenshot() {
        withContext(Dispatchers.Main) {
            cancelTransition()
            inspireView.stopTransitionAnimation()
            inspireView.visibility = GONE
            inspireView.clear()
            detachIfNeeded()
            invalidateSessionCapture()
        }
        val elapsed = SystemClock.uptimeMillis() - lastSessionCaptureAtMs
        val intervalWait = if (lastSessionCaptureAtMs == 0L) {
            0L
        } else {
            (SCREENSHOT_MIN_INTERVAL_MS - elapsed).coerceAtLeast(0L)
        }
        delay(maxOf(SCREENSHOT_HIDE_SETTLE_MS, intervalWait))
    }

    private suspend fun recaptureSessionScreenshot(): ManagedBitmap? {
        hideForScreenshot()
        return captureFreshScreenshot()
    }

    private suspend fun captureFreshScreenshot(): ManagedBitmap? {
        val generation = withContext(Dispatchers.Main) { sessionGeneration }
        repeat(SESSION_CAPTURE_MAX_ATTEMPTS) { attempt ->
            lastSessionCaptureAtMs = SystemClock.uptimeMillis()
            val captured = context.takeManagedScreenshot()
            val acquired = withContext(Dispatchers.Main) {
                if (generation != sessionGeneration) {
                    captured?.close()
                    return@withContext null
                }
                if (captured == null) return@withContext null
                sessionScreenshot?.close()
                sessionScreenshot = captured
                captured.acquire()
            }
            if (acquired != null) return acquired
            if (generation != withContext(Dispatchers.Main) { sessionGeneration }) return null
            if (attempt < SESSION_CAPTURE_MAX_ATTEMPTS - 1) {
                delay(SCREENSHOT_MIN_INTERVAL_MS)
            }
        }
        return null
    }

    private fun resetSessionCapture() {
        invalidateSessionCapture()
        layoutParams.alpha = 1f
        updateViewLayoutSafely(inspireView, layoutParams)
    }

    private fun resetTextBoxPreview() {
        textBoxGeneration += 1
        textBoxDetectionJob?.cancel()
        textBoxDetectionJob = null
        sessionTextBoxes = null
        previewSelectionRect = null
        textBoxPreviewEnabled = false
        if (textBoxPreviewUpdateScheduled) {
            inspireView.removeCallbacks(textBoxPreviewUpdateRunnable)
            textBoxPreviewUpdateScheduled = false
        }
        inspireView.setDetectedTextBoxes(emptyList())
    }

    private fun requestTextBoxPreview(rect: Rect) {
        textBoxPreviewEnabled = true
        previewSelectionRect = rect.normalized()
        if (sessionTextBoxes != null) {
            scheduleTextBoxPreviewUpdate()
        }
        if (sessionScreenshot?.bitmapOrNull == null && sessionCaptureJob == null) {
            requestSessionScreenshot()
        } else {
            startFullScreenTextBoxDetectionIfReady()
        }
    }

    private fun startFullScreenTextBoxDetectionIfReady() {
        if (!textBoxPreviewEnabled || sessionTextBoxes != null || textBoxDetectionJob?.isActive == true) {
            return
        }
        val screenshot = sessionScreenshot?.acquire() ?: return
        val detectionGeneration = textBoxGeneration
        textBoxDetectionJob = context.launch(Dispatchers.Default) {
            val boxes = try {
                screenshot.use { MlKitOcr.detectTextBoxes(it.requireBitmap()) }
            } catch (error: Exception) {
                Log.w("InspireFloating", "Full-screen text box detection failed", error)
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (detectionGeneration == textBoxGeneration) {
                    sessionTextBoxes = boxes.map(::Rect)
                    scheduleTextBoxPreviewUpdate()
                }
            }
        }
    }

    private fun scheduleTextBoxPreviewUpdate() {
        if (textBoxPreviewUpdateScheduled) return
        textBoxPreviewUpdateScheduled = true
        inspireView.postOnAnimation(textBoxPreviewUpdateRunnable)
    }

    private fun updateTextBoxPreview() {
        val selection = previewSelectionRect
        val boxes = sessionTextBoxes
        if (selection == null || boxes == null || selection.isEmpty) {
            inspireView.setDetectedTextBoxes(emptyList())
            return
        }
        inspireView.setDetectedTextBoxes(boxes.mapNotNull { box ->
            box.selectedHorizontalPart(selection)
        })
    }

    private fun Rect.normalized(): Rect = Rect(
        minOf(left, right),
        minOf(top, bottom),
        maxOf(left, right),
        maxOf(top, bottom),
    )

    private suspend fun editSelectedImage(selectionRect: Rect) = withContext(Dispatchers.Main) {
        inspireView.setInteractionEnabled(false, keepControlsVisible = true)
        try {
            withContext(Dispatchers.Default) {
                val screenshot = recaptureSessionScreenshot()
                    ?: error("Session screenshot unavailable")
                val editGeneration = withContext(Dispatchers.Main) { sessionGeneration }
                screenshot.use {
                    val cropped = it.cropByRect(selectionRect)
                        ?: error("Selection screenshot unavailable")
                    cropped.use { image ->
                        withContext(Dispatchers.Main) {
                            if (editGeneration != sessionGeneration) return@withContext
                            InspireDataHolder.clear()
                            InspireDataHolder.replaceScreenshotBitmap(image)
                            InspireDataHolder.dragRect = Rect(selectionRect)
                            InspireDataHolder.forceImageTextSelection = sessionForceImageTextSelection
                            InspireDataHolder.triggerSide = sessionTriggerSide
                            try {
                                context.startActivity(
                                    Intent(context, InspireImageEditorActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (error: Exception) {
                                InspireDataHolder.clear()
                                throw error
                            }
                            hide()
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("InspireFloating", "Opening image editor failed", error)
            recoverInspireState()
            Toast.makeText(
                context,
                context.getString(R.string.inspire_image_edit_load_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private suspend fun copyDetectedTextBox(textBox: Rect) {
        val copyGeneration = withContext(Dispatchers.Main) { sessionGeneration }
        withContext(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                inspireView.setInteractionEnabled(false, keepControlsVisible = true)
            }
            try {
                val text = runCatching { recognizeDetectedTextBox(textBox) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.e("InspireFloating", "Text recognition failed", it)
                    }
                    .getOrDefault("")

                withContext(Dispatchers.Main) {
                    if (copyGeneration != sessionGeneration) return@withContext
                    if (text.isBlank()) {
                        inspireView.showToast(
                            context.getString(R.string.inspire_content_accessibility_empty)
                        )
                    } else {
                        ClipboardUtils.copyText(context, text)
                        inspireView.showToast(
                            context.getString(R.string.inspire_quick_copy_copied, text)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("InspireFloating", "copyDetectedTextBox failed", e)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (copyGeneration == sessionGeneration) {
                        inspireView.setInteractionEnabled(true)
                    }
                }
            }
        }
    }

    private suspend fun recognizeDetectedTextBox(textBox: Rect): String {
        acquireSessionScreenshot()?.use { screenshot ->
            screenshot.cropByRect(textBox)?.use { cropped ->
                return cropped.requireBitmap().useSoftwareBitmap { bitmap ->
                    MlKitOcr.recognizeTextBox(bitmap)
                }
            }
        }
        return ""
    }

    private fun Rect.selectedHorizontalPart(selection: Rect): Rect? {
        if (top < selection.top || bottom > selection.bottom) return null
        return Rect(this).takeIf { it.intersect(selection) && !it.isEmpty }
    }

    private suspend fun resolveTargetRect(dragSelectRect: Rect): Rect? =
        withContext(Dispatchers.Default) {
            val tooSmallWidth = inspireView.rectTooSmallWidth(dragSelectRect)
            val tooSmallHeight = inspireView.rectTooSmallHeight(dragSelectRect)
            when {
                tooSmallWidth && tooSmallHeight -> {
                    val intersectingViews = getScreenView(context, dragSelectRect, clickMode = true)
                    intersectingViews
                        .filter { it.area > dragSelectRect.height() * dragSelectRect.width() * 1.2 }
                        .minByOrNull { it.area }?.bound
                }

                tooSmallHeight || tooSmallWidth -> {
                    val views = getScreenView(context, dragSelectRect, clickMode = false)
                    if (views.isEmpty()) {
                        null
                    } else {
                        val expandedRect = Rect()
                        var isFirst = true
                        views.forEach { view ->
                            if (isFirst) {
                                expandedRect.set(view.bound)
                                isFirst = false
                            } else {
                                expandedRect.union(view.bound)
                            }
                        }
                        expandedRect
                    }
                }

                else -> dragSelectRect
            }
        }

    private suspend fun recoverInspireState() = withContext(Dispatchers.Main) {
        context.gestureManager?.setInteractionMode(InteractionMode.NORMAL)
        inspireView.stopTransitionAnimation()
        inspireView.setInteractionEnabled(true)
        inspireView.visibility = GONE
        inspireView.clear()
        detachIfNeeded()
        resetSessionCapture()
    }

    private suspend fun processScreenContent(dragSelectRect: Rect) {
        withContext(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                inspireView.setInteractionEnabled(false)
            }
            if (shouldPrewarmSegmenter()) {
                prewarmSegmenterDictionary()
            }
            try {
                hideForScreenshot()
                val startUptimeMs = SystemClock.uptimeMillis()
                resetTransitionState()
                scheduleTransition(startUptimeMs)

                val accessibilityJob = async {
                    val words = mutableListOf<String>()
                    for (copyNode in getScreenContent(context, dragSelectRect)) {
                        copyNode.content?.let { words.add(it) }
                    }
                    InspireDataHolder.accessibilityContent = words
                }

                val takeScreenshotJob = async {
                    captureFreshScreenshot()?.use { screenshotHandle ->
                        val croppedBitmap =
                            screenshotHandle.cropByRect(dragSelectRect) ?: return@async
                        InspireDataHolder.replaceScreenshotBitmap(croppedBitmap)
                        PinPoolStore.addScreenContentImageIfEnabled(
                            context,
                            croppedBitmap.requireBitmap()
                        )
                        croppedBitmap.close()
                        screenshotDone = true
                        maybeStartTransitionAfterScreenshot(startUptimeMs)
                    }
                }

                accessibilityJob.await()
                takeScreenshotJob.await()
                InspireDataHolder.replaceOcrAllowedTextBoxes(null)

                InspireDataHolder.dragRect = Rect()
                InspireDataHolder.dragRect?.set(dragSelectRect)
                InspireDataHolder.forceImageTextSelection = sessionForceImageTextSelection
                InspireDataHolder.triggerSide = sessionTriggerSide

                launchInspireContentActivity()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("InspireFloating", "processScreenContent failed", e)
                InspireDataHolder.clear()
                recoverInspireState()
            }
        }
    }

    private fun takeScreenShot(
        dragSelectRect: Rect,
        block: suspend (ManagedBitmap) -> Unit
    ) {
        context.launch(Dispatchers.IO) {
            recaptureSessionScreenshot()?.use { screenshotHandle ->
                val img = screenshotHandle.cropByRect(dragSelectRect)
                if (img == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.inspire_screenshot_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                try {
                    block(img)
                } finally {
                    img.close()
                }
            } ?: withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.inspire_screenshot_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun launchInspireContentActivity() {
        activityLaunching = true
        cancelTransition()
        InspireContentActivity.start(context)
        hide()
    }

    fun hide() {
        context.postOnMain {
            context.gestureManager?.setInteractionMode(InteractionMode.NORMAL)
            cancelTransition()
            inspireView.stopTransitionAnimation()
            inspireView.visibility = GONE
            inspireView.clear()
            detachIfNeeded()
            resetSessionCapture()
        }
    }

    fun destroy() {
        context.postOnMain {
            context.gestureManager?.setInteractionMode(InteractionMode.NORMAL)
            cancelTransition()
            inspireView.stopTransitionAnimation()
            inspireView.visibility = GONE
            inspireView.clear()
            detachIfNeeded()
            resetSessionCapture()
        }
    }

    private fun preloadOcrModel() {
        context.launch(Dispatchers.IO) {
            MlKitOcr.preload()
        }
    }

    private fun prewarmSegmenterDictionary() {
        SmartSegmenter.prewarm(context.applicationContext)
    }

    private suspend fun shouldPrewarmSegmenter(): Boolean {
        val defaultImageTextOverlay = context.settingsStore.readKey(KEY_DEFAULT_IMAGE_TEXT_OVERLAY, false)
        return !(defaultImageTextOverlay || sessionForceImageTextSelection)
    }

    fun refreshTheme() {
        inspireView.refreshDynamicColors()
    }

    private suspend fun prepareVisibleInspireView() {
        context.gestureManager?.setInteractionMode(InteractionMode.INSPIRE_SELECT)
        preloadOcrModel()
        ensureAttached()
        GestureFluidAppearance.refresh(context)
        inspireView.refreshDynamicColors()
        inspireView.visibility = View.VISIBLE
        inspireView.stopTransitionAnimation()
        inspireView.setInteractionEnabled(true)
        if (context.settingsStore.readKey(KEY_INSPIRE_SELECTION_VIBRATE, true)) {
            VibratorUtils.vibrator(context, true)
        }
    }

    private suspend fun prepareScreenContentSelector(
        forceImageTextSelection: Boolean,
        screenshotFabVisibility: Int,
    ) {
        sessionForceImageTextSelection = forceImageTextSelection
        prepareVisibleInspireView()
        inspireView.checkIsNeedDoubleCheck()
        inspireView.showScreenShotFab(screenshotFabVisibility)
    }

    fun show(forceImageTextSelection: Boolean = false, side: String? = null) {
        context.postOnMain {
            resetSessionCapture()
            sessionTriggerSide = side
            prepareScreenContentSelector(forceImageTextSelection, View.VISIBLE)
        }
    }

    fun show(rect: Rect, forceImageTextSelection: Boolean = false, side: String? = null) {
        context.postOnMain {
            resetSessionCapture()
            sessionTriggerSide = side
            sessionForceImageTextSelection = forceImageTextSelection
            prepareVisibleInspireView()
            inspireView.dragSelectRect.set(rect)
            inspireView.inspireViewCallBack.onDragUp(rect)
        }
    }

    fun showFullscreen(forceImageTextSelection: Boolean = false, side: String? = null) {
        context.postOnMain {
            resetSessionCapture()
            sessionTriggerSide = side
            prepareScreenContentSelector(forceImageTextSelection, View.GONE)
            inspireView.captureFullScreen()
        }
    }

    fun showByReselect(rect: Rect, forceImageTextSelection: Boolean, side: String? = null) {
        context.postOnMain {
            resetSessionCapture()
            sessionTriggerSide = side
            sessionForceImageTextSelection = forceImageTextSelection
            prepareVisibleInspireView()
            inspireView.dragSelectRect.set(rect)
            inspireView.reSelect()
        }
    }

    private fun resetTransitionState() {
        transitionJob?.cancel()
        transitionJob = null
        transitionPending = false
        transitionShown = false
        screenshotDone = false
        activityLaunching = false
    }

    private fun scheduleTransition(startUptimeMs: Long) {
        transitionJob?.cancel()
        transitionJob = context.postOnMain {
            delay(250)
            if (activityLaunching) return@postOnMain
            if (screenshotDone) {
                showTransition()
            } else {
                transitionPending = true
                // Ensure it starts ASAP after screenshot completes if already late.
                if (SystemClock.uptimeMillis() - startUptimeMs >= 250) {
                    maybeStartTransitionAfterScreenshot(startUptimeMs)
                }
            }
        }
    }

    private fun maybeStartTransitionAfterScreenshot(startUptimeMs: Long) {
        if (activityLaunching || transitionShown) return
        if (screenshotDone && (transitionPending || SystemClock.uptimeMillis() - startUptimeMs >= 250)) {
            showTransition()
            transitionPending = false
        }
    }

    private fun showTransition() {
        if (transitionShown) return
        transitionShown = true
        context.postOnMain {
            ensureAttached()
            inspireView.visibility = View.VISIBLE
            inspireView.startTransitionAnimation()
        }
    }

    private fun cancelTransition() {
        transitionJob?.cancel()
        transitionJob = null
        transitionPending = false
        transitionShown = false
        context.postOnMain {
            inspireView.stopTransitionAnimation()
        }
    }

    private companion object {
        const val SCREENSHOT_HIDE_SETTLE_MS = 50L
        const val SCREENSHOT_MIN_INTERVAL_MS = 400L
        const val SESSION_CAPTURE_MAX_ATTEMPTS = 2
    }
}
