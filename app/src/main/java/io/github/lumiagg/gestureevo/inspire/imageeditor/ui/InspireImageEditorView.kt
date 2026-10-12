package io.github.lumiagg.gestureevo.inspire.imageeditor.ui

import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.os.SystemClock
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.inspire.cropByRect
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.EditAction
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.EditorMode
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.EditorPoint
import io.github.lumiagg.gestureevo.inspire.imageeditor.state.EditorSession
import io.github.lumiagg.gestureevo.inspire.imageeditor.state.EditorUiState
import io.github.lumiagg.gestureevo.inspire.imageeditor.utils.EditorExportEngine
import io.github.lumiagg.gestureevo.inspire.imageeditor.utils.EditorRenderUtils
import io.github.lumiagg.gestureevo.inspire.imageeditor.utils.FastBlurHelper
import io.github.lumiagg.gestureevo.inspire.useSoftwareBitmap
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class InspireImageEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface Callback {
        fun onRequestAddText(anchor: EditorPoint, suggestedSize: Float)
        fun onRequestEditText(action: EditAction.Text)
    }

    private enum class GestureKind {
        NONE,
        PAN,
        CROP,
        PATH,
        SHAPE_DRAW,
        SHAPE_DRAG,
        SHAPE_RESIZE,
        SHAPE_TAP,
        TEXT_DRAG,
        TEXT_RESIZE,
        TEXT_TAP,
    }

    private enum class CropDragMode {
        NEW,
        MOVE,
        LEFT,
        TOP,
        RIGHT,
        BOTTOM,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
    }

    var callback: Callback? = null

    private var backgroundBlurredBitmap: Bitmap? = null
    private val backgroundFilterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val backgroundScrimPaint = Paint().apply { style = Paint.Style.FILL }
    private val bgDestRect = RectF()
    private val imageBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp2px
    }
    private val imageShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(90, 0, 0, 0)
        maskFilter = BlurMaskFilter(16f.dp2px, BlurMaskFilter.Blur.NORMAL)
    }
    private val contentChangeListeners = mutableListOf<() -> Unit>()

    fun addOnContentChangedListener(listener: () -> Unit) {
        contentChangeListeners.add(listener)
    }

    private fun notifyContentChanged() {
        contentChangeListeners.forEach { it.invoke() }
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val cropMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(150, 0, 0, 0)
    }
    private val cropFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val cropHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val textHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val controlOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val deleteHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }
    private val deleteIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val centerBrushPreviewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val centerBrushPreviewOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val minCropTouchTargetPx: Float
        get() = 24f.dp2px
    private val cropHandleRadiusPx: Float
        get() = 8f.dp2px
    private val cropCornerHitRadiusPx: Float
        get() = cropHandleRadiusPx + 12f.dp2px
    private val cropEdgeHitSlopPx: Float
        get() = 16f.dp2px
    private val shapeResizeHandleRadiusPx: Float
        get() = 10f.dp2px
    private val shapeResizeHitSlopPx: Float
        get() = 14f.dp2px
    private val minShapeTouchTargetPx: Float
        get() = 18f.dp2px
    private val textResizeHandleRadiusPx: Float
        get() = 10f.dp2px
    private val textResizeHitSlopPx: Float
        get() = 14f.dp2px
    private val minTextTouchTargetPx: Float
        get() = 18f.dp2px
    private val deleteHandleRadiusPx: Float
        get() = 11f.dp2px
    private val deleteHandleHitSlopPx: Float
        get() = 14f.dp2px
    private val suggestedTextTouchTargetPx: Float
        get() = max(28f.dp2px, uiState.currentStrokeWidth * 1.8f)
    private val blankTextDoubleTapSlopPx: Float
        get() = 48f.dp2px

    private var baseBitmapHandle: ManagedBitmap? = null
    private var mosaicBitmap: Bitmap? = null
    private var session: EditorSession? = null
    private var uiState = EditorUiState()
    private val sessionListener = EditorSession.Listener { state ->
        val enteringCropMode = lastObservedMode != EditorMode.CROP && state.currentMode == EditorMode.CROP
        uiState = state
        if (state.currentMode != EditorMode.SHAPE) {
            selectedShapeId = null
        }
        if (state.currentMode != EditorMode.TEXT) {
            selectedTextId = null
        }
        if (enteringCropMode) {
            ensureCropSelectionInitialized()
        }
        lastObservedMode = state.currentMode
        invalidate()
    }

    private var imageZoom = 1f
    private var imagePanX = 0f
    private var imagePanY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private var gestureKind = GestureKind.NONE
    private var activePoints = mutableListOf<EditorPoint>()
    private var activeShapeStart: EditorPoint? = null
    private var activeShapeEnd: EditorPoint? = null
    private var activeCropStart: EditorPoint? = null
    private var activeCropEnd: EditorPoint? = null
    private var cropSelection: RectF? = null
    private var cropDragMode: CropDragMode = CropDragMode.NEW
    private var cropDragStartPoint: EditorPoint? = null
    private var cropDragStartRect: RectF? = null
    private var selectedShapeId: String? = null
    private var draggingShapeId: String? = null
    private var dragStartShapeAction: EditAction.Shape? = null
    private var dragStartShapeBounds: RectF? = null
    private var shapeGestureMoved = false
    private var selectedTextId: String? = null
    private var draggingTextId: String? = null
    private var dragStartImagePoint: EditorPoint? = null
    private var dragStartTextAnchor: EditorPoint? = null
    private var dragStartTextSize: Float? = null
    private var dragStartTextRadius: Float = 0f
    private var pendingTextTapPoint: EditorPoint? = null
    private var textGestureMoved = false
    private var lastTextTapId: String? = null
    private var lastTextTapTimestamp: Long = 0L
    private var suppressMultiTouchUntilUptimeMs: Long = 0L
    private var centerBrushPreviewVisible = false
    private var lastObservedMode: EditorMode = uiState.currentMode

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyScale(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val baseBitmap: Bitmap?
        get() = baseBitmapHandle?.bitmapOrNull

    fun bindSession(editorSession: EditorSession) {
        if (session === editorSession) return
        session?.removeListener(sessionListener)
        session = editorSession
        editorSession.addListener(sessionListener)
    }

    fun setImageBitmap(handle: ManagedBitmap) {
        releaseBaseBitmap()
        baseBitmapHandle = handle.acquire()
        rebuildMosaicBitmap()
        rebuildBlurredBackgroundBitmap()
        resetViewport()
        ensureCropSelectionInitialized()
        invalidate()
    }

    fun clearImage() {
        releaseBaseBitmap()
        resetGestureState()
        cropSelection = null
        invalidate()
    }

    fun exportBitmap(scaleFactor: Float): Bitmap? {
        val bitmap = baseBitmap ?: return null
        return EditorExportEngine.exportBitmap(bitmap, uiState.visibleActions, scaleFactor)
    }

    fun visibleImageCenterPoint(): EditorPoint? {
        val bitmap = baseBitmap ?: return null
        val scale = currentDisplayScale(bitmap)
        val originX = currentOriginX(bitmap, scale)
        val originY = currentOriginY(bitmap, scale)
        val visibleLeft = ((0f - originX) / scale).coerceIn(0f, bitmap.width.toFloat())
        val visibleTop = ((0f - originY) / scale).coerceIn(0f, bitmap.height.toFloat())
        val visibleRight = ((width.toFloat() - originX) / scale).coerceIn(0f, bitmap.width.toFloat())
        val visibleBottom = ((height.toFloat() - originY) / scale).coerceIn(0f, bitmap.height.toFloat())
        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) {
            return EditorPoint(bitmap.width / 2f, bitmap.height / 2f)
        }
        return EditorPoint(
            x = (visibleLeft + visibleRight) / 2f,
            y = (visibleTop + visibleBottom) / 2f,
        )
    }

    fun suggestedTextSizeForInsert(): Float = suggestedTextSize()

    fun currentBitmapSize(): Pair<Int, Int>? {
        val bitmap = baseBitmap ?: return null
        return bitmap.width to bitmap.height
    }

    fun resetViewport() {
        imageZoom = INITIAL_IMAGE_FIT_ZOOM
        imagePanX = 0f
        imagePanY = 0f
        invalidate()
        notifyContentChanged()
    }

    fun clearCropSelection() {
        activeCropStart = null
        activeCropEnd = null
        cropSelection = null
        cropDragStartPoint = null
        cropDragStartRect = null
        cropDragMode = CropDragMode.NEW
        invalidate()
    }

    fun cancelOngoingInteraction() {
        resetGestureState()
        invalidate()
    }

    fun suppressMultiTouchTemporarily(durationMs: Long = MODE_SWITCH_MULTI_TOUCH_GUARD_MS) {
        suppressMultiTouchUntilUptimeMs = SystemClock.uptimeMillis() + durationMs
    }

    fun setCenterBrushPreviewVisible(visible: Boolean) {
        if (centerBrushPreviewVisible == visible) return
        centerBrushPreviewVisible = visible
        invalidate()
    }

    fun applyCropSelection(): Boolean {
        val bitmap = baseBitmap ?: return false
        val cropRect = buildCropBitmapRect(bitmap) ?: return false
        val croppedBitmap = bitmap.cropByRect(cropRect) ?: return false
        val transformedActions = uiState.visibleActions
            .mapNotNull { action -> transformActionForCrop(action, cropRect) }
        val croppedHandle = ManagedBitmap.from(croppedBitmap)
        try {
            setImageBitmap(croppedHandle)
        } finally {
            croppedHandle.close()
        }
        session?.replaceActions(transformedActions)
        clearCropSelection()
        return true
    }

    override fun onDetachedFromWindow() {
        session?.removeListener(sessionListener)
        super.onDetachedFromWindow()
        releaseBaseBitmap()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (backgroundBlurredBitmap == null && baseBitmap != null) {
            rebuildBlurredBackgroundBitmap()
        }
        notifyContentChanged()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        drawBackground(canvas)
        val bitmap = baseBitmap ?: return
        val scale = currentDisplayScale(bitmap)
        val originX = currentOriginX(bitmap, scale)
        val originY = currentOriginY(bitmap, scale)
        updateOverlayPaints(scale)

        val imgW = bitmap.width * scale
        val imgH = bitmap.height * scale
        val shadowSpread = 2f.dp2px
        canvas.drawRect(
            originX - shadowSpread,
            originY - shadowSpread,
            originX + imgW + shadowSpread,
            originY + imgH + shadowSpread,
            imageShadowPaint
        )

        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        imageBorderPaint.color = if (isDark) Color.argb(45, 255, 255, 255) else Color.argb(35, 0, 0, 0)
        canvas.drawRect(originX, originY, originX + imgW, originY + imgH, imageBorderPaint)

        canvas.withTranslation(originX, originY) {
            canvas.scale(scale, scale)
            canvas.drawBitmap(bitmap, 0f, 0f, null)

            val layerId = canvas.saveLayer(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), null)
            uiState.visibleActions.forEach { action ->
                EditorRenderUtils.drawAction(canvas, action, mosaicBitmap)
            }
            drawTemporaryAction(canvas)
            drawCropMask(canvas, bitmap)
            canvas.restoreToCount(layerId)
            drawCropOverlay(canvas, bitmap)

            selectedShapeAction()?.let { action ->
                canvas.drawRect(EditorRenderUtils.buildShapeBounds(action), selectionPaint)
                drawSelectedShapeDeleteHandle(canvas, action)
                drawSelectedShapeHandle(canvas, action)
            }
            selectedTextAction()?.let { action ->
                canvas.drawRect(EditorRenderUtils.buildTextBounds(action), selectionPaint)
                drawSelectedTextDeleteHandle(canvas, action)
                drawSelectedTextHandle(canvas, action)
            }
        }
        drawCenterBrushPreview(canvas)
        notifyContentChanged()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = baseBitmap ?: return false
        scaleDetector.onTouchEvent(event)
        val shouldSuppressMultiTouch =
            event.pointerCount > 1 && SystemClock.uptimeMillis() < suppressMultiTouchUntilUptimeMs

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                handleActionDown(bitmap, event.x, event.y)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (shouldSuppressMultiTouch) {
                    cancelActiveDrawing()
                    gestureKind = GestureKind.NONE
                    return true
                }
                cancelActiveDrawing()
                gestureKind = GestureKind.PAN
                val focus = pointerFocus(event)
                lastFocusX = focus.first
                lastFocusY = focus.second
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount > 2) {
                    val focus = pointerFocusExcludingIndex(event, event.actionIndex)
                    lastFocusX = focus.first
                    lastFocusY = focus.second
                } else {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    lastTouchX = event.getX(remainingIndex)
                    lastTouchY = event.getY(remainingIndex)
                    lastFocusX = lastTouchX
                    lastFocusY = lastTouchY
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    if (shouldSuppressMultiTouch) {
                        return true
                    }
                    val focus = pointerFocus(event)
                    imagePanX += focus.first - lastFocusX
                    imagePanY += focus.second - lastFocusY
                    lastFocusX = focus.first
                    lastFocusY = focus.second
                    invalidate()
                    notifyContentChanged()
                    return true
                }
                handleActionMove(bitmap, event.x, event.y)
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                handleActionUp(bitmap)
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                resetGestureState()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleActionDown(bitmap: Bitmap, x: Float, y: Float) {
        val point = screenToImagePoint(bitmap, x, y)
        val unrestrictedPoint = screenToImagePointUnbounded(bitmap, x, y)
        when (uiState.currentMode) {
            EditorMode.NAVIGATE -> gestureKind = GestureKind.PAN
            EditorMode.CROP -> {
                val existingSelection = cropSelection
                val detectedMode = existingSelection?.let { rect ->
                    unrestrictedPoint?.let { detectCropDragMode(rect, it) }
                }
                if (detectedMode == null && point == null) {
                    gestureKind = GestureKind.PAN
                    return
                }
                gestureKind = GestureKind.CROP
                cropDragStartPoint = unrestrictedPoint ?: point
                if (detectedMode != null) {
                    cropDragMode = detectedMode
                    cropDragStartRect = RectF(existingSelection)
                    activeCropStart = null
                    activeCropEnd = null
                } else {
                    val boundedPoint = point ?: return
                    cropDragMode = CropDragMode.NEW
                    cropDragStartRect = null
                    activeCropStart = boundedPoint
                    activeCropEnd = boundedPoint
                    cropSelection = RectF(
                        boundedPoint.x,
                        boundedPoint.y,
                        boundedPoint.x,
                        boundedPoint.y,
                    )
                }
                invalidate()
            }
            EditorMode.DOODLE, EditorMode.ERASER, EditorMode.MOSAIC -> {
                if (point == null) {
                    gestureKind = GestureKind.PAN
                    return
                }
                gestureKind = GestureKind.PATH
                activePoints = mutableListOf(point)
            }

            EditorMode.SHAPE -> {
                val hitPoint = unrestrictedPoint ?: point
                if (hitPoint == null) {
                    gestureKind = GestureKind.PAN
                    return
                }
                if (deleteSelectedShapeIfHit(hitPoint)) {
                    gestureKind = GestureKind.NONE
                    invalidate()
                    return
                }
                val hitShape = findShapeHandleAtPoint(hitPoint) ?: findShapeAtPoint(hitPoint)
                selectedShapeId = hitShape?.id
                if (hitShape != null) {
                    gestureKind = if (isShapeResizeHandleHit(hitShape, hitPoint)) {
                        GestureKind.SHAPE_RESIZE
                    } else {
                        GestureKind.SHAPE_TAP
                    }
                    draggingShapeId = hitShape.id
                    dragStartImagePoint = hitPoint
                    dragStartShapeAction = hitShape
                    dragStartShapeBounds = EditorRenderUtils.buildShapeContentBounds(hitShape)
                    shapeGestureMoved = false
                    activeShapeStart = null
                    activeShapeEnd = null
                } else {
                    val boundedPoint = point ?: run {
                        gestureKind = GestureKind.PAN
                        return
                    }
                    gestureKind = GestureKind.SHAPE_DRAW
                    activeShapeStart = boundedPoint
                    activeShapeEnd = boundedPoint
                    draggingShapeId = null
                    dragStartShapeAction = null
                    dragStartShapeBounds = null
                    shapeGestureMoved = false
                }
                invalidate()
            }

            EditorMode.TEXT -> {
                val hitPoint = unrestrictedPoint ?: point
                if (hitPoint == null) {
                    gestureKind = GestureKind.PAN
                    return
                }
                if (deleteSelectedTextIfHit(hitPoint)) {
                    gestureKind = GestureKind.NONE
                    invalidate()
                    return
                }
                val hitText = findTextHandleAtPoint(hitPoint) ?: findTextAtPoint(hitPoint)
                selectedTextId = hitText?.id
                if (hitText != null) {
                    val hitResizeHandle = isTextResizeHandleHit(hitText, hitPoint)
                    gestureKind = if (hitResizeHandle) GestureKind.TEXT_RESIZE else GestureKind.TEXT_TAP
                    draggingTextId = hitText.id
                    dragStartImagePoint = hitPoint
                    dragStartTextAnchor = hitText.anchor
                    dragStartTextSize = hitText.textSize
                    dragStartTextRadius = distance(
                        hitText.anchor,
                        EditorRenderUtils.buildTextResizeHandleCenter(hitText)
                    ).coerceAtLeast(1f)
                    textGestureMoved = false
                } else {
                    gestureKind = GestureKind.NONE
                    selectedTextId = null
                    textGestureMoved = false
                }
                invalidate()
            }
        }
    }

    private fun handleActionMove(bitmap: Bitmap, x: Float, y: Float) {
        val point = screenToImagePoint(bitmap, x, y)
        val unrestrictedPoint = screenToImagePointUnbounded(bitmap, x, y)
        when (gestureKind) {
            GestureKind.PAN -> {
                imagePanX += x - lastTouchX
                imagePanY += y - lastTouchY
                invalidate()
                notifyContentChanged()
            }

            GestureKind.CROP -> {
                val cropPoint = unrestrictedPoint ?: return
                when (cropDragMode) {
                    CropDragMode.NEW -> {
                        activeCropEnd = cropPoint.coerceWithinBitmap(bitmap)
                        cropSelection = normalizedRect(activeCropStart, activeCropEnd)
                    }

                    else -> {
                        val startPoint = cropDragStartPoint ?: return
                        val startRect = cropDragStartRect ?: return
                        cropSelection = updateCropRect(
                            bitmap = bitmap,
                            startRect = startRect,
                            startPoint = startPoint,
                            currentPoint = cropPoint,
                            mode = cropDragMode,
                        )
                    }
                }
                invalidate()
            }

            GestureKind.PATH -> {
                point ?: return
                activePoints.add(point)
                invalidate()
            }

            GestureKind.SHAPE_DRAW -> {
                point ?: return
                activeShapeEnd = point
                invalidate()
            }

            GestureKind.SHAPE_TAP, GestureKind.SHAPE_DRAG, GestureKind.SHAPE_RESIZE -> {
                val dragId = draggingShapeId ?: return
                val startPoint = dragStartImagePoint ?: return
                val originalShape = dragStartShapeAction ?: return
                val dragPoint = unrestrictedPoint ?: return
                when (gestureKind) {
                    GestureKind.SHAPE_RESIZE -> {
                        val originalBounds = dragStartShapeBounds ?: return
                        if (!shapeGestureMoved &&
                            (abs(dragPoint.x - startPoint.x) > 4f || abs(dragPoint.y - startPoint.y) > 4f)
                        ) {
                            shapeGestureMoved = true
                        }
                        session?.updateShapeAction(dragId) {
                            resizeShapeAction(
                                action = originalShape,
                                originalBounds = originalBounds,
                                targetPoint = dragPoint,
                            )
                        }
                    }

                    else -> {
                        val deltaX = dragPoint.x - startPoint.x
                        val deltaY = dragPoint.y - startPoint.y
                        if (!shapeGestureMoved && (abs(deltaX) > 4f || abs(deltaY) > 4f)) {
                            gestureKind = GestureKind.SHAPE_DRAG
                            shapeGestureMoved = true
                        }
                        session?.updateShapeAction(dragId) {
                            it.copy(
                                start = EditorPoint(
                                    x = originalShape.start.x + deltaX,
                                    y = originalShape.start.y + deltaY,
                                ),
                                end = EditorPoint(
                                    x = originalShape.end.x + deltaX,
                                    y = originalShape.end.y + deltaY,
                                ),
                            )
                        }
                    }
                }
                invalidate()
            }

            GestureKind.TEXT_TAP, GestureKind.TEXT_DRAG, GestureKind.TEXT_RESIZE -> {
                val dragId = draggingTextId
                val dragPoint = unrestrictedPoint
                if (dragId == null || dragPoint == null) {
                    if (pendingTextTapPoint == null) {
                        imagePanX += x - lastTouchX
                        imagePanY += y - lastTouchY
                        invalidate()
                    }
                    return
                }
                val startPoint = dragStartImagePoint ?: return
                when (gestureKind) {
                    GestureKind.TEXT_RESIZE -> {
                        val startSize = dragStartTextSize ?: return
                        val anchor = dragStartTextAnchor ?: return
                        val currentRadius = distance(anchor, dragPoint).coerceAtLeast(1f)
                        if (!textGestureMoved && abs(currentRadius - dragStartTextRadius) > 4f) {
                            textGestureMoved = true
                        }
                        val scale = currentRadius / dragStartTextRadius
                        session?.updateTextAction(dragId) {
                            it.copy(textSize = (startSize * scale).coerceAtLeast(minTextSizeInImageSpace()))
                        }
                    }

                    else -> {
                        val startAnchor = dragStartTextAnchor ?: return
                        val deltaX = dragPoint.x - startPoint.x
                        val deltaY = dragPoint.y - startPoint.y
                        if (!textGestureMoved && (abs(deltaX) > 4f || abs(deltaY) > 4f)) {
                            gestureKind = GestureKind.TEXT_DRAG
                            textGestureMoved = true
                        }
                        session?.updateTextAction(dragId) {
                            it.copy(anchor = EditorPoint(startAnchor.x + deltaX, startAnchor.y + deltaY))
                        }
                    }
                }
            }

            GestureKind.NONE -> Unit
        }
    }

    private fun handleActionUp(bitmap: Bitmap) {
        when (gestureKind) {
            GestureKind.CROP -> commitCropSelection()
            GestureKind.PATH -> commitPathAction()
            GestureKind.SHAPE_DRAW -> commitShapeAction()
            GestureKind.SHAPE_TAP,
            GestureKind.SHAPE_DRAG,
            GestureKind.SHAPE_RESIZE -> Unit
            GestureKind.TEXT_TAP, GestureKind.TEXT_RESIZE -> {
                val action = selectedTextAction()
                when {
                    action != null && !textGestureMoved -> {
                        handleTextTap(action)
                    }

                    draggingTextId != null && screenToImagePoint(bitmap, lastTouchX, lastTouchY) == null -> Unit
                }
            }

            else -> Unit
        }
        resetGestureState()
        invalidate()
    }

    private fun commitPathAction() {
        if (activePoints.size < 2) return
        val strokeWidth = currentBrushWidthInImageSpace()
        val action = when (uiState.currentMode) {
            EditorMode.DOODLE -> EditAction.Doodle(
                id = buildActionId(),
                points = activePoints.toList(),
                color = uiState.currentColor,
                strokeWidth = strokeWidth,
            )

            EditorMode.ERASER -> EditAction.Doodle(
                id = buildActionId(),
                points = activePoints.toList(),
                color = Color.TRANSPARENT,
                strokeWidth = strokeWidth,
                isEraser = true,
            )

            EditorMode.MOSAIC -> EditAction.Mosaic(
                id = buildActionId(),
                points = activePoints.toList(),
                strokeWidth = strokeWidth,
            )

            else -> null
        }
        action?.let { session?.addAction(it) }
    }

    private fun commitShapeAction() {
        val start = activeShapeStart ?: return
        val end = activeShapeEnd ?: return
        val distance = sqrt(((end.x - start.x) * (end.x - start.x)) + ((end.y - start.y) * (end.y - start.y)))
        if (distance < 4f) return
        val strokeWidth = currentBrushWidthInImageSpace()
        session?.addAction(
            EditAction.Shape(
                id = buildActionId(),
                type = uiState.currentShapeType,
                start = start,
                end = end,
                color = uiState.currentColor,
                strokeWidth = strokeWidth,
            )
        )
    }

    private fun commitCropSelection() {
        val bitmap = baseBitmap ?: run {
            cropSelection = null
            return
        }
        val minCropSize = minCropSizeInImageSpace(bitmap)
        cropSelection = when (cropDragMode) {
            CropDragMode.NEW -> {
                val rect = normalizedRect(activeCropStart, activeCropEnd)
                rect?.takeIf { it.width() >= minCropSize && it.height() >= minCropSize }
            }

            else -> cropSelection?.takeIf { it.width() >= minCropSize && it.height() >= minCropSize }
        }
    }

    private fun drawTemporaryAction(canvas: android.graphics.Canvas) {
        when (uiState.currentMode) {
            EditorMode.DOODLE, EditorMode.ERASER -> {
                if (activePoints.size < 2) return
                EditorRenderUtils.drawAction(
                    canvas,
                    EditAction.Doodle(
                        id = "temp",
                        points = activePoints,
                        color = if (uiState.currentMode == EditorMode.ERASER) Color.TRANSPARENT else uiState.currentColor,
                        strokeWidth = currentBrushWidthInImageSpace(),
                        isEraser = uiState.currentMode == EditorMode.ERASER,
                    ),
                    mosaicBitmap,
                )
            }

            EditorMode.SHAPE -> {
                val start = activeShapeStart ?: return
                val end = activeShapeEnd ?: return
                EditorRenderUtils.drawAction(
                    canvas,
                    EditAction.Shape(
                        id = "temp",
                        type = uiState.currentShapeType,
                        start = start,
                        end = end,
                        color = uiState.currentColor,
                        strokeWidth = currentBrushWidthInImageSpace(),
                    ),
                    mosaicBitmap,
                )
            }

            EditorMode.MOSAIC -> {
                if (activePoints.size < 2) return
                EditorRenderUtils.drawAction(
                    canvas,
                    EditAction.Mosaic(
                        id = "temp",
                        points = activePoints,
                        strokeWidth = currentBrushWidthInImageSpace(),
                    ),
                    mosaicBitmap,
                )
            }

            EditorMode.CROP,
            EditorMode.NAVIGATE,
            EditorMode.TEXT -> Unit
        }
    }

    private fun drawCropMask(
        canvas: android.graphics.Canvas,
        bitmap: Bitmap,
    ) {
        val rect = currentCropRect() ?: return
        val maskPath = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), Path.Direction.CW)
            addRect(rect, Path.Direction.CCW)
        }
        canvas.drawPath(maskPath, cropMaskPaint)
    }

    private fun drawCropOverlay(
        canvas: android.graphics.Canvas,
        bitmap: Bitmap,
    ) {
        val rect = currentCropRect() ?: return
        canvas.drawRect(rect, cropFramePaint)
        val handleRadius = cropHandleRadiusPx / currentDisplayScale(bitmap)
        drawControlCircle(canvas, EditorPoint(rect.left, rect.top), handleRadius, cropHandlePaint)
        drawControlCircle(canvas, EditorPoint(rect.right, rect.top), handleRadius, cropHandlePaint)
        drawControlCircle(canvas, EditorPoint(rect.left, rect.bottom), handleRadius, cropHandlePaint)
        drawControlCircle(canvas, EditorPoint(rect.right, rect.bottom), handleRadius, cropHandlePaint)
    }

    private fun drawSelectedTextDeleteHandle(
        canvas: android.graphics.Canvas,
        action: EditAction.Text,
    ) {
        val center = EditorRenderUtils.buildTextDeleteHandleCenter(action)
        val bitmap = baseBitmap ?: return
        val handleRadius = deleteHandleRadiusPx / currentDisplayScale(bitmap)
        drawDeleteHandle(canvas, center, handleRadius)
    }

    private fun drawSelectedTextHandle(
        canvas: android.graphics.Canvas,
        action: EditAction.Text,
    ) {
        val center = EditorRenderUtils.buildTextResizeHandleCenter(action)
        val bitmap = baseBitmap ?: return
        val handleRadius = textResizeHandleRadiusPx / currentDisplayScale(bitmap)
        drawControlCircle(canvas, center, handleRadius, textHandlePaint)
    }

    private fun drawSelectedShapeDeleteHandle(
        canvas: android.graphics.Canvas,
        action: EditAction.Shape,
    ) {
        val center = EditorRenderUtils.buildShapeDeleteHandleCenter(action)
        val bitmap = baseBitmap ?: return
        val handleRadius = deleteHandleRadiusPx / currentDisplayScale(bitmap)
        drawDeleteHandle(canvas, center, handleRadius)
    }

    private fun drawSelectedShapeHandle(
        canvas: android.graphics.Canvas,
        action: EditAction.Shape,
    ) {
        val center = EditorRenderUtils.buildShapeResizeHandleCenter(action)
        val bitmap = baseBitmap ?: return
        val handleRadius = shapeResizeHandleRadiusPx / currentDisplayScale(bitmap)
        drawControlCircle(canvas, center, handleRadius, textHandlePaint)
    }

    private fun drawControlCircle(
        canvas: android.graphics.Canvas,
        center: EditorPoint,
        radius: Float,
        fillPaint: Paint,
    ) {
        canvas.drawCircle(center.x, center.y, radius, fillPaint)
        canvas.drawCircle(center.x, center.y, radius, controlOutlinePaint)
    }

    private fun drawDeleteHandle(
        canvas: android.graphics.Canvas,
        center: EditorPoint,
        radius: Float,
    ) {
        canvas.drawCircle(center.x, center.y, radius, deleteHandlePaint)
        canvas.drawCircle(center.x, center.y, radius, deleteIconPaint)
        val iconInset = radius * 0.42f
        canvas.drawLine(
            center.x - iconInset,
            center.y - iconInset,
            center.x + iconInset,
            center.y + iconInset,
            deleteIconPaint,
        )
        canvas.drawLine(
            center.x - iconInset,
            center.y + iconInset,
            center.x + iconInset,
            center.y - iconInset,
            deleteIconPaint,
        )
    }

    private fun drawCenterBrushPreview(canvas: android.graphics.Canvas) {
        if (!centerBrushPreviewVisible || !supportsCenterBrushPreview(uiState.currentMode)) return
        val previewDiameter = uiState.currentStrokeWidth.coerceAtLeast(1f)
        val previewRadius = previewDiameter / 2f
        val previewColor = when (uiState.currentMode) {
            EditorMode.ERASER -> {
                val surface = DynamicColorUtils.surfaceContainerHighestColor
                ColorUtils.setAlphaComponent(surface, 220)
            }

            EditorMode.MOSAIC -> DynamicColorUtils.primaryColor
            else -> uiState.currentColor
        }
        val outlineBaseColor = when (uiState.currentMode) {
            EditorMode.ERASER -> DynamicColorUtils.onSurfaceColor
            else -> if (ColorUtils.calculateLuminance(previewColor) > 0.5) Color.BLACK else Color.WHITE
        }
        centerBrushPreviewFillPaint.color = previewColor
        centerBrushPreviewOutlinePaint.color = ColorUtils.setAlphaComponent(outlineBaseColor, 230)
        centerBrushPreviewOutlinePaint.strokeWidth = 2f.dp2px
        canvas.drawCircle(width / 2f, height / 2f, previewRadius, centerBrushPreviewFillPaint)
        canvas.drawCircle(width / 2f, height / 2f, previewRadius, centerBrushPreviewOutlinePaint)
    }

    private fun applyScale(scaleFactor: Float, focusX: Float, focusY: Float) {
        val bitmap = baseBitmap ?: return
        val oldScale = currentDisplayScale(bitmap)
        val oldOriginX = currentOriginX(bitmap, oldScale)
        val oldOriginY = currentOriginY(bitmap, oldScale)
        imageZoom = (imageZoom * scaleFactor).coerceIn(MIN_IMAGE_ZOOM, MAX_IMAGE_ZOOM)
        val newScale = currentDisplayScale(bitmap)
        val baseOriginX = baseOriginX(bitmap, newScale)
        val baseOriginY = baseOriginY(bitmap, newScale)
        val mappedImageX = (focusX - oldOriginX) / oldScale
        val mappedImageY = (focusY - oldOriginY) / oldScale
        val desiredOriginX = focusX - (mappedImageX * newScale)
        val desiredOriginY = focusY - (mappedImageY * newScale)
        imagePanX = desiredOriginX - baseOriginX
        imagePanY = desiredOriginY - baseOriginY
        invalidate()
        notifyContentChanged()
    }

    private fun findTextAtPoint(point: EditorPoint): EditAction.Text? {
        return uiState.visibleActions
            .asReversed()
            .filterIsInstance<EditAction.Text>()
            .firstOrNull { EditorRenderUtils.buildTextBounds(it).contains(point.x, point.y) }
    }

    private fun findShapeAtPoint(point: EditorPoint): EditAction.Shape? {
        return uiState.visibleActions
            .asReversed()
            .filterIsInstance<EditAction.Shape>()
            .firstOrNull { EditorRenderUtils.buildShapeBounds(it).contains(point.x, point.y) }
    }

    private fun findShapeHandleAtPoint(point: EditorPoint): EditAction.Shape? {
        return uiState.visibleActions
            .asReversed()
            .filterIsInstance<EditAction.Shape>()
            .firstOrNull { isShapeResizeHandleHit(it, point) }
    }

    private fun findTextHandleAtPoint(point: EditorPoint): EditAction.Text? {
        return uiState.visibleActions
            .asReversed()
            .filterIsInstance<EditAction.Text>()
            .firstOrNull { isTextResizeHandleHit(it, point) }
    }

    private fun deleteSelectedShapeIfHit(point: EditorPoint): Boolean {
        val action = selectedShapeAction() ?: return false
        if (!isShapeDeleteHandleHit(action, point)) return false
        session?.removeAction(action.id)
        selectedShapeId = null
        draggingShapeId = null
        dragStartShapeAction = null
        dragStartShapeBounds = null
        dragStartImagePoint = null
        shapeGestureMoved = false
        return true
    }

    private fun deleteSelectedTextIfHit(point: EditorPoint): Boolean {
        val action = selectedTextAction() ?: return false
        if (!isTextDeleteHandleHit(action, point)) return false
        session?.removeAction(action.id)
        selectedTextId = null
        draggingTextId = null
        dragStartImagePoint = null
        dragStartTextAnchor = null
        dragStartTextSize = null
        dragStartTextRadius = 0f
        pendingTextTapPoint = null
        textGestureMoved = false
        lastTextTapId = null
        lastTextTapTimestamp = 0L
        return true
    }

    private fun selectedShapeAction(): EditAction.Shape? {
        val targetId = selectedShapeId ?: return null
        return uiState.visibleActions
            .filterIsInstance<EditAction.Shape>()
            .firstOrNull { it.id == targetId }
    }

    private fun selectedTextAction(): EditAction.Text? {
        val targetId = selectedTextId ?: return null
        return uiState.visibleActions
            .filterIsInstance<EditAction.Text>()
            .firstOrNull { it.id == targetId }
    }

    private fun handleTextTap(action: EditAction.Text) {
        val now = System.currentTimeMillis()
        val isDoubleTap = lastTextTapId == action.id && (now - lastTextTapTimestamp) <= DOUBLE_TAP_TIMEOUT_MS
        lastTextTapId = action.id
        lastTextTapTimestamp = now
        if (isDoubleTap) {
            callback?.onRequestEditText(action)
            lastTextTapId = null
            lastTextTapTimestamp = 0L
        }
    }

    private fun distance(start: EditorPoint, end: EditorPoint): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        return sqrt((dx * dx) + (dy * dy))
    }

    private fun isTextResizeHandleHit(
        action: EditAction.Text,
        point: EditorPoint,
    ): Boolean {
        val bitmap = baseBitmap ?: return false
        val resizeHandle = EditorRenderUtils.buildTextResizeHandleCenter(action)
        val hitRadius = (textResizeHandleRadiusPx + textResizeHitSlopPx) / currentDisplayScale(bitmap)
        return distance(point, resizeHandle) <= hitRadius
    }

    private fun isTextDeleteHandleHit(
        action: EditAction.Text,
        point: EditorPoint,
    ): Boolean {
        val bitmap = baseBitmap ?: return false
        val deleteHandle = EditorRenderUtils.buildTextDeleteHandleCenter(action)
        val hitRadius = (deleteHandleRadiusPx + deleteHandleHitSlopPx) / currentDisplayScale(bitmap)
        return distance(point, deleteHandle) <= hitRadius
    }

    private fun isShapeResizeHandleHit(
        action: EditAction.Shape,
        point: EditorPoint,
    ): Boolean {
        val bitmap = baseBitmap ?: return false
        val resizeHandle = EditorRenderUtils.buildShapeResizeHandleCenter(action)
        val hitRadius = (shapeResizeHandleRadiusPx + shapeResizeHitSlopPx) / currentDisplayScale(bitmap)
        return distance(point, resizeHandle) <= hitRadius
    }

    private fun isShapeDeleteHandleHit(
        action: EditAction.Shape,
        point: EditorPoint,
    ): Boolean {
        val bitmap = baseBitmap ?: return false
        val deleteHandle = EditorRenderUtils.buildShapeDeleteHandleCenter(action)
        val hitRadius = (deleteHandleRadiusPx + deleteHandleHitSlopPx) / currentDisplayScale(bitmap)
        return distance(point, deleteHandle) <= hitRadius
    }

    private fun updateOverlayPaints(scale: Float) {
        val inverseScale = 1f / scale.coerceAtLeast(0.0001f)
        val accentColor = DynamicColorUtils.primaryColor
        val accentOnColor = MaterialColors.getColor(this, MaterialR.attr.colorOnPrimary)
        val errorColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
        val errorOnColor = if (ColorUtils.calculateLuminance(errorColor) > 0.5) Color.BLACK else Color.WHITE
        selectionPaint.color = accentColor
        selectionPaint.strokeWidth = 2f.dp2px * inverseScale
        selectionPaint.pathEffect = DashPathEffect(
            floatArrayOf(18f.dp2px * inverseScale, 10f.dp2px * inverseScale),
            0f
        )
        cropFramePaint.color = accentColor
        cropFramePaint.strokeWidth = 3f.dp2px * inverseScale
        cropHandlePaint.color = accentColor
        textHandlePaint.color = accentColor
        controlOutlinePaint.color = accentOnColor
        controlOutlinePaint.strokeWidth = max(1.5f.dp2px * inverseScale, 1f)
        deleteHandlePaint.color = errorColor
        deleteIconPaint.color = errorOnColor
        deleteIconPaint.strokeWidth = max(2f.dp2px * inverseScale, 1f)
    }

    private fun supportsCenterBrushPreview(mode: EditorMode): Boolean {
        return mode == EditorMode.DOODLE ||
            mode == EditorMode.ERASER ||
            mode == EditorMode.SHAPE ||
            mode == EditorMode.MOSAIC
    }

    private fun drawBackground(canvas: Canvas) {
        val bg = backgroundBlurredBitmap
        val w = width.toFloat()
        val h = height.toFloat()
        if (bg != null && !bg.isRecycled && w > 0f && h > 0f) {
            val bgW = bg.width.toFloat()
            val bgH = bg.height.toFloat()
            val scale = max(w / bgW, h / bgH) * 1.06f
            val drawW = bgW * scale
            val drawH = bgH * scale
            val left = (w - drawW) / 2f
            val top = (h - drawH) / 2f
            bgDestRect.set(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bg, null, bgDestRect, backgroundFilterPaint)

            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val scrimColor = if (isDark) {
                Color.argb(28, 0, 0, 0)
            } else {
                Color.argb(10, 0, 0, 0)
            }
            backgroundScrimPaint.color = scrimColor
            canvas.drawRect(0f, 0f, w, h, backgroundScrimPaint)
        } else {
            val fallback = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK)
            canvas.drawColor(fallback)
        }
    }

    fun renderSubRegionToBitmap(regionInView: Rect, targetBitmap: Bitmap) {
        val bmpW = targetBitmap.width
        val bmpH = targetBitmap.height
        if (bmpW <= 0 || bmpH <= 0 || width <= 0 || height <= 0 || regionInView.width() <= 0 || regionInView.height() <= 0) return

        val canvas = Canvas(targetBitmap)
        val scaleX = bmpW.toFloat() / regionInView.width().toFloat()
        val scaleY = bmpH.toFloat() / regionInView.height().toFloat()

        canvas.withScale(scaleX, scaleY) {
            canvas.translate(-regionInView.left.toFloat(), -regionInView.top.toFloat())

            drawBackground(canvas)

            val bitmap = baseBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                val scale = currentDisplayScale(bitmap)
                val originX = currentOriginX(bitmap, scale)
                val originY = currentOriginY(bitmap, scale)
                val imgW = bitmap.width * scale
                val imgH = bitmap.height * scale
                val imgRect = RectF(originX, originY, originX + imgW, originY + imgH)

                if (RectF.intersects(imgRect, RectF(regionInView))) {
                    canvas.withTranslation(originX, originY) {
                        canvas.scale(scale, scale)
                        bitmap.useSoftwareBitmap { safeBitmap ->
                            if (!safeBitmap.isRecycled) {
                                canvas.drawBitmap(safeBitmap, 0f, 0f, null)
                            }
                        }
                        uiState.visibleActions.forEach { action ->
                            EditorRenderUtils.drawAction(canvas, action, mosaicBitmap)
                        }
                    }
                }
            }
        }
    }

    private fun currentDisplayScale(bitmap: Bitmap): Float {
        val fitScale = min(
            width / bitmap.width.toFloat(),
            height / bitmap.height.toFloat(),
        )
        return fitScale * imageZoom
    }

    private fun currentOriginX(bitmap: Bitmap, scale: Float): Float {
        return baseOriginX(bitmap, scale) + imagePanX
    }

    private fun currentOriginY(bitmap: Bitmap, scale: Float): Float {
        return baseOriginY(bitmap, scale) + imagePanY
    }

    private fun baseOriginX(bitmap: Bitmap, scale: Float): Float {
        return (width - (bitmap.width * scale)) / 2f
    }

    private fun baseOriginY(bitmap: Bitmap, scale: Float): Float {
        return (height - (bitmap.height * scale)) / 2f
    }

    private fun screenToImagePoint(bitmap: Bitmap, x: Float, y: Float): EditorPoint? {
        val point = screenToImagePointUnbounded(bitmap, x, y) ?: return null
        if (point.x !in 0f..bitmap.width.toFloat() || point.y !in 0f..bitmap.height.toFloat()) {
            return null
        }
        return point
    }

    private fun screenToImagePointUnbounded(bitmap: Bitmap, x: Float, y: Float): EditorPoint {
        val scale = currentDisplayScale(bitmap)
        val imageX = (x - currentOriginX(bitmap, scale)) / scale
        val imageY = (y - currentOriginY(bitmap, scale)) / scale
        return EditorPoint(imageX, imageY)
    }

    private fun EditorPoint.coerceWithinBitmap(bitmap: Bitmap): EditorPoint {
        return EditorPoint(
            x = x.coerceIn(0f, bitmap.width.toFloat()),
            y = y.coerceIn(0f, bitmap.height.toFloat()),
        )
    }

    private fun pointerFocus(event: MotionEvent): Pair<Float, Float> {
        var sumX = 0f
        var sumY = 0f
        for (index in 0 until event.pointerCount) {
            sumX += event.getX(index)
            sumY += event.getY(index)
        }
        return sumX / event.pointerCount to sumY / event.pointerCount
    }

    private fun pointerFocusExcludingIndex(event: MotionEvent, excludedIndex: Int): Pair<Float, Float> {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (index in 0 until event.pointerCount) {
            if (index == excludedIndex) continue
            sumX += event.getX(index)
            sumY += event.getY(index)
            count += 1
        }
        if (count <= 0) {
            return lastFocusX to lastFocusY
        }
        return sumX / count to sumY / count
    }

    private fun suggestedTextSize(): Float {
        return suggestedTextSizeInImageSpace()
    }

    private fun currentBrushWidthInImageSpace(): Float {
        val bitmap = baseBitmap ?: return uiState.currentStrokeWidth
        val displayScale = currentDisplayScale(bitmap).coerceAtLeast(0.0001f)
        return (uiState.currentStrokeWidth / displayScale).coerceAtLeast(1f)
    }

    private fun suggestedTextSizeInImageSpace(): Float {
        val bitmap = baseBitmap ?: return suggestedTextTouchTargetPx
        val displayScale = currentDisplayScale(bitmap).coerceAtLeast(0.0001f)
        return (suggestedTextTouchTargetPx / displayScale).coerceAtLeast(1f)
    }

    private fun minTextSizeInImageSpace(): Float {
        val bitmap = baseBitmap ?: return minTextTouchTargetPx
        val displayScale = currentDisplayScale(bitmap).coerceAtLeast(0.0001f)
        return (minTextTouchTargetPx / displayScale).coerceAtLeast(1f)
    }

    private fun minShapeSizeInImageSpace(): Float {
        val bitmap = baseBitmap ?: return minShapeTouchTargetPx
        val displayScale = currentDisplayScale(bitmap).coerceAtLeast(0.0001f)
        return (minShapeTouchTargetPx / displayScale).coerceAtLeast(1f)
    }

    private fun resizeShapeAction(
        action: EditAction.Shape,
        originalBounds: RectF,
        targetPoint: EditorPoint,
    ): EditAction.Shape {
        val minSize = minShapeSizeInImageSpace()
        val selectionBounds = EditorRenderUtils.buildShapeBounds(action)
        val horizontalInset = selectionBounds.right - originalBounds.right
        val verticalInset = selectionBounds.bottom - originalBounds.bottom
        val targetBounds = RectF(
            originalBounds.left,
            originalBounds.top,
            max(targetPoint.x - horizontalInset, originalBounds.left + minSize),
            max(targetPoint.y - verticalInset, originalBounds.top + minSize),
        )
        return action.copy(
            start = mapPointBetweenRects(
                point = action.start,
                sourceRect = originalBounds,
                targetRect = targetBounds,
                zeroWidthFallbackFraction = 0f,
                zeroHeightFallbackFraction = 0f,
            ),
            end = mapPointBetweenRects(
                point = action.end,
                sourceRect = originalBounds,
                targetRect = targetBounds,
                zeroWidthFallbackFraction = 1f,
                zeroHeightFallbackFraction = 1f,
            ),
        )
    }

    private fun mapPointBetweenRects(
        point: EditorPoint,
        sourceRect: RectF,
        targetRect: RectF,
        zeroWidthFallbackFraction: Float,
        zeroHeightFallbackFraction: Float,
    ): EditorPoint {
        return EditorPoint(
            x = mapCoordinateBetweenRanges(
                value = point.x,
                sourceStart = sourceRect.left,
                sourceEnd = sourceRect.right,
                targetStart = targetRect.left,
                targetEnd = targetRect.right,
                zeroFallbackFraction = zeroWidthFallbackFraction,
            ),
            y = mapCoordinateBetweenRanges(
                value = point.y,
                sourceStart = sourceRect.top,
                sourceEnd = sourceRect.bottom,
                targetStart = targetRect.top,
                targetEnd = targetRect.bottom,
                zeroFallbackFraction = zeroHeightFallbackFraction,
            ),
        )
    }

    private fun mapCoordinateBetweenRanges(
        value: Float,
        sourceStart: Float,
        sourceEnd: Float,
        targetStart: Float,
        targetEnd: Float,
        zeroFallbackFraction: Float,
    ): Float {
        val sourceSize = sourceEnd - sourceStart
        val fraction = if (abs(sourceSize) < 0.0001f) {
            zeroFallbackFraction
        } else {
            (value - sourceStart) / sourceSize
        }
        return targetStart + ((targetEnd - targetStart) * fraction)
    }

    private fun rebuildMosaicBitmap() {
        mosaicBitmap?.takeIf { !it.isRecycled }?.recycle()
        mosaicBitmap = baseBitmap?.let(EditorRenderUtils::generateMosaicBitmap)
    }

    private fun rebuildBlurredBackgroundBitmap() {
        backgroundBlurredBitmap?.takeIf { !it.isRecycled }?.recycle()
        backgroundBlurredBitmap = baseBitmap?.let(FastBlurHelper::createBlurredBackground)
        notifyContentChanged()
    }

    private fun cancelActiveDrawing() {
        activePoints.clear()
        activeShapeStart = null
        activeShapeEnd = null
        activeCropStart = null
        activeCropEnd = null
        cropDragStartPoint = null
        cropDragStartRect = null
        cropDragMode = CropDragMode.NEW
        draggingShapeId = null
        dragStartShapeAction = null
        dragStartShapeBounds = null
        shapeGestureMoved = false
    }

    private fun resetGestureState() {
        gestureKind = GestureKind.NONE
        activePoints.clear()
        activeShapeStart = null
        activeShapeEnd = null
        activeCropStart = null
        activeCropEnd = null
        cropDragStartPoint = null
        cropDragStartRect = null
        cropDragMode = CropDragMode.NEW
        draggingShapeId = null
        dragStartShapeAction = null
        dragStartShapeBounds = null
        shapeGestureMoved = false
        draggingTextId = null
        dragStartImagePoint = null
        dragStartTextAnchor = null
        dragStartTextSize = null
        dragStartTextRadius = 0f
        pendingTextTapPoint = null
        textGestureMoved = false
    }

    private fun releaseBaseBitmap() {
        mosaicBitmap?.takeIf { !it.isRecycled }?.recycle()
        mosaicBitmap = null
        backgroundBlurredBitmap?.takeIf { !it.isRecycled }?.recycle()
        backgroundBlurredBitmap = null
        baseBitmapHandle?.close()
        baseBitmapHandle = null
        notifyContentChanged()
    }

    private fun buildActionId(): String = System.nanoTime().toString()

    private fun ensureCropSelectionInitialized() {
        if (uiState.currentMode != EditorMode.CROP || cropSelection != null) return
        val bitmap = baseBitmap ?: return
        cropSelection = RectF(
            0f,
            0f,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
        )
        activeCropStart = null
        activeCropEnd = null
        cropDragStartPoint = null
        cropDragStartRect = null
        cropDragMode = CropDragMode.NEW
    }

    private fun buildCropBitmapRect(bitmap: Bitmap): Rect? {
        val rect = cropSelection ?: return null
        val left = rect.left.toInt().coerceIn(0, bitmap.width)
        val top = rect.top.toInt().coerceIn(0, bitmap.height)
        val right = rect.right.toInt().coerceIn(0, bitmap.width)
        val bottom = rect.bottom.toInt().coerceIn(0, bitmap.height)
        return Rect(left, top, right, bottom).takeIf { it.width() > 0 && it.height() > 0 }
    }

    private fun normalizedRect(
        start: EditorPoint?,
        end: EditorPoint?,
    ): RectF? {
        val actualStart = start ?: return null
        val actualEnd = end ?: return null
        return RectF(
            min(actualStart.x, actualEnd.x),
            min(actualStart.y, actualEnd.y),
            max(actualStart.x, actualEnd.x),
            max(actualStart.y, actualEnd.y),
        )
    }

    private fun currentCropRect(): RectF? {
        if (uiState.currentMode != EditorMode.CROP) return null
        return normalizedRect(activeCropStart, activeCropEnd) ?: cropSelection
    }

    private fun detectCropDragMode(
        rect: RectF,
        point: EditorPoint,
    ): CropDragMode? {
        val bitmap = baseBitmap ?: return null
        val scale = currentDisplayScale(bitmap)
        val cornerHitRadius = cropCornerHitRadiusPx / scale
        if (distance(point, EditorPoint(rect.left, rect.top)) <= cornerHitRadius) {
            return CropDragMode.TOP_LEFT
        }
        if (distance(point, EditorPoint(rect.right, rect.top)) <= cornerHitRadius) {
            return CropDragMode.TOP_RIGHT
        }
        if (distance(point, EditorPoint(rect.left, rect.bottom)) <= cornerHitRadius) {
            return CropDragMode.BOTTOM_LEFT
        }
        if (distance(point, EditorPoint(rect.right, rect.bottom)) <= cornerHitRadius) {
            return CropDragMode.BOTTOM_RIGHT
        }

        val edgeHitSlop = cropEdgeHitSlopPx / scale
        val nearLeft = abs(point.x - rect.left) <= edgeHitSlop
        val nearRight = abs(point.x - rect.right) <= edgeHitSlop
        val nearTop = abs(point.y - rect.top) <= edgeHitSlop
        val nearBottom = abs(point.y - rect.bottom) <= edgeHitSlop
        return when {
            nearLeft && point.y in rect.top..rect.bottom -> CropDragMode.LEFT
            nearRight && point.y in rect.top..rect.bottom -> CropDragMode.RIGHT
            nearTop && point.x in rect.left..rect.right -> CropDragMode.TOP
            nearBottom && point.x in rect.left..rect.right -> CropDragMode.BOTTOM
            rect.contains(point.x, point.y) -> CropDragMode.MOVE
            else -> null
        }
    }

    private fun updateCropRect(
        bitmap: Bitmap,
        startRect: RectF,
        startPoint: EditorPoint,
        currentPoint: EditorPoint,
        mode: CropDragMode,
    ): RectF {
        val deltaX = currentPoint.x - startPoint.x
        val deltaY = currentPoint.y - startPoint.y
        return when (mode) {
            CropDragMode.MOVE -> {
                val moved = RectF(startRect)
                val targetLeft = (startRect.left + deltaX).coerceIn(0f, bitmap.width - startRect.width())
                val targetTop = (startRect.top + deltaY).coerceIn(0f, bitmap.height - startRect.height())
                moved.offsetTo(targetLeft, targetTop)
                moved
            }

            CropDragMode.LEFT -> resizeCropRect(bitmap, startRect, left = startRect.left + deltaX)
            CropDragMode.TOP -> resizeCropRect(bitmap, startRect, top = startRect.top + deltaY)
            CropDragMode.RIGHT -> resizeCropRect(bitmap, startRect, right = startRect.right + deltaX)
            CropDragMode.BOTTOM -> resizeCropRect(bitmap, startRect, bottom = startRect.bottom + deltaY)
            CropDragMode.TOP_LEFT -> resizeCropRect(
                bitmap,
                startRect,
                left = startRect.left + deltaX,
                top = startRect.top + deltaY,
            )

            CropDragMode.TOP_RIGHT -> resizeCropRect(
                bitmap,
                startRect,
                right = startRect.right + deltaX,
                top = startRect.top + deltaY,
            )

            CropDragMode.BOTTOM_LEFT -> resizeCropRect(
                bitmap,
                startRect,
                left = startRect.left + deltaX,
                bottom = startRect.bottom + deltaY,
            )

            CropDragMode.BOTTOM_RIGHT -> resizeCropRect(
                bitmap,
                startRect,
                right = startRect.right + deltaX,
                bottom = startRect.bottom + deltaY,
            )

            CropDragMode.NEW -> startRect
        }
    }

    private fun resizeCropRect(
        bitmap: Bitmap,
        startRect: RectF,
        left: Float? = null,
        top: Float? = null,
        right: Float? = null,
        bottom: Float? = null,
    ): RectF {
        val minCropSize = minCropSizeInImageSpace(bitmap)
        var newLeft = startRect.left
        var newTop = startRect.top
        var newRight = startRect.right
        var newBottom = startRect.bottom

        left?.let { newLeft = it.coerceIn(0f, newRight - minCropSize) }
        top?.let { newTop = it.coerceIn(0f, newBottom - minCropSize) }
        right?.let { newRight = it.coerceIn(newLeft + minCropSize, bitmap.width.toFloat()) }
        bottom?.let { newBottom = it.coerceIn(newTop + minCropSize, bitmap.height.toFloat()) }

        return RectF(newLeft, newTop, newRight, newBottom)
    }

    private fun minCropSizeInImageSpace(bitmap: Bitmap): Float {
        val displayScale = currentDisplayScale(bitmap).coerceAtLeast(0.0001f)
        val touchTargetInImageSpace = minCropTouchTargetPx / displayScale
        val bitmapBound = min(bitmap.width, bitmap.height).toFloat()
        return touchTargetInImageSpace.coerceAtMost(bitmapBound)
    }

    private fun transformActionForCrop(
        action: EditAction,
        cropRect: Rect,
    ): EditAction? {
        val cropBounds = RectF(
            cropRect.left.toFloat(),
            cropRect.top.toFloat(),
            cropRect.right.toFloat(),
            cropRect.bottom.toFloat(),
        )
        return when (action) {
            is EditAction.Doodle -> {
                val originalBounds = pathBounds(action.points)
                if (!RectF.intersects(originalBounds, cropBounds)) return null
                val points = action.points.map { it.offsetByCrop(cropRect) }
                action.copy(points = points)
            }

            is EditAction.Mosaic -> {
                val originalBounds = pathBounds(action.points)
                if (!RectF.intersects(originalBounds, cropBounds)) return null
                val points = action.points.map { it.offsetByCrop(cropRect) }
                action.copy(points = points)
            }

            is EditAction.Shape -> {
                val bounds = RectF(
                    min(action.start.x, action.end.x),
                    min(action.start.y, action.end.y),
                    max(action.start.x, action.end.x),
                    max(action.start.y, action.end.y),
                )
                if (!RectF.intersects(bounds, cropBounds)) return null
                action.copy(
                    start = action.start.offsetByCrop(cropRect),
                    end = action.end.offsetByCrop(cropRect),
                )
            }

            is EditAction.Text -> {
                if (!RectF.intersects(EditorRenderUtils.buildTextBounds(action), cropBounds)) return null
                action.copy(anchor = action.anchor.offsetByCrop(cropRect))
            }
        }
    }

    private fun pathBounds(points: List<EditorPoint>): RectF {
        if (points.isEmpty()) return RectF()
        var left = points.first().x
        var top = points.first().y
        var right = points.first().x
        var bottom = points.first().y
        points.drop(1).forEach { point ->
            left = min(left, point.x)
            top = min(top, point.y)
            right = max(right, point.x)
            bottom = max(bottom, point.y)
        }
        return RectF(left, top, right, bottom)
    }

    private fun EditorPoint.offsetByCrop(cropRect: Rect): EditorPoint {
        return EditorPoint(
            x = x - cropRect.left,
            y = y - cropRect.top,
        )
    }

    companion object {
        private const val INITIAL_IMAGE_FIT_ZOOM = 0.92f
        private const val MIN_IMAGE_ZOOM = 0.35f
        private const val MAX_IMAGE_ZOOM = 6f
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        private const val MODE_SWITCH_MULTI_TOUCH_GUARD_MS = 180L
    }
}
