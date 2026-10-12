package io.github.lumiagg.gestureevo.inspire.imageeditor.utils

import androidx.core.graphics.scale

import android.graphics.Bitmap
import io.github.lumiagg.gestureevo.inspire.useSoftwareBitmap
import android.graphics.Canvas
import androidx.core.graphics.withClip
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.EditAction
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.EditorPoint
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.ShapeType
import kotlin.math.max
import kotlin.math.min

object EditorRenderUtils {

    fun createPath(points: List<EditorPoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points.first().x, points.first().y)
        if (points.size == 1) {
            path.lineTo(points.first().x, points.first().y)
            return path
        }
        var current = points.first()
        for (index in 1 until points.size) {
            val next = points[index]
            val midX = (current.x + next.x) / 2f
            val midY = (current.y + next.y) / 2f
            if (index == 1) {
                path.lineTo(midX, midY)
            } else {
                path.quadTo(current.x, current.y, midX, midY)
            }
            current = next
        }
        path.lineTo(current.x, current.y)
        return path
    }

    fun drawAction(
        canvas: Canvas,
        action: EditAction,
        mosaicBitmap: Bitmap?,
        overrideTextAlpha: Int? = null,
    ) {
        when (action) {
            is EditAction.Doodle -> drawDoodle(canvas, action)
            is EditAction.Shape -> drawShape(canvas, action)
            is EditAction.Text -> drawText(canvas, action, overrideTextAlpha)
            is EditAction.Mosaic -> drawMosaic(canvas, action, mosaicBitmap)
        }
    }

    fun buildTextBounds(action: EditAction.Text, padding: Float = 12f): RectF {
        val paint = createTextPaint(action)
        val lines = action.text.split('\n')
        val maxWidth = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val metrics = paint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val totalHeight = max(1, lines.size) * lineHeight
        return RectF(
            action.anchor.x - (maxWidth / 2f) - padding,
            action.anchor.y - (totalHeight / 2f) - padding,
            action.anchor.x + (maxWidth / 2f) + padding,
            action.anchor.y + (totalHeight / 2f) + padding,
        )
    }

    fun buildTextResizeHandleCenter(action: EditAction.Text, padding: Float = 12f): EditorPoint {
        val bounds = buildTextBounds(action, padding)
        return EditorPoint(bounds.right, bounds.bottom)
    }

    fun buildTextDeleteHandleCenter(action: EditAction.Text, padding: Float = 12f): EditorPoint {
        val bounds = buildTextBounds(action, padding)
        return EditorPoint(bounds.left, bounds.top)
    }

    fun buildShapeBounds(action: EditAction.Shape, padding: Float = 12f): RectF {
        val contentBounds = buildShapeContentBounds(action)
        val inset = max(padding, action.strokeWidth * 0.75f)
        return RectF(
            contentBounds.left - inset,
            contentBounds.top - inset,
            contentBounds.right + inset,
            contentBounds.bottom + inset,
        )
    }

    fun buildShapeContentBounds(action: EditAction.Shape): RectF {
        return RectF(
            min(action.start.x, action.end.x),
            min(action.start.y, action.end.y),
            max(action.start.x, action.end.x),
            max(action.start.y, action.end.y),
        )
    }

    fun buildShapeResizeHandleCenter(action: EditAction.Shape, padding: Float = 12f): EditorPoint {
        val bounds = buildShapeBounds(action, padding)
        return EditorPoint(bounds.right, bounds.bottom)
    }

    fun buildShapeDeleteHandleCenter(action: EditAction.Shape, padding: Float = 12f): EditorPoint {
        val bounds = buildShapeBounds(action, padding)
        return EditorPoint(bounds.left, bounds.top)
    }

    fun generateMosaicBitmap(source: Bitmap): Bitmap {
        return source.useSoftwareBitmap { softwareSource ->
            val sampleSize = max(1, min(softwareSource.width, softwareSource.height) / 36)
            val scaledWidth = max(1, softwareSource.width / sampleSize)
            val scaledHeight = max(1, softwareSource.height / sampleSize)
            val tiny = softwareSource.scale(scaledWidth, scaledHeight, false)
            try {
                tiny.scale(softwareSource.width, softwareSource.height, false)
            } finally {
                if (!tiny.isRecycled) {
                    tiny.recycle()
                }
            }
        }
    }

    private fun drawDoodle(canvas: Canvas, action: EditAction.Doodle) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = action.strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = action.color
            if (action.isEraser) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
        }
        canvas.drawPath(createPath(action.points), paint)
    }

    private fun drawShape(canvas: Canvas, action: EditAction.Shape) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = action.strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = action.color
        }
        val left = min(action.start.x, action.end.x)
        val top = min(action.start.y, action.end.y)
        val right = max(action.start.x, action.end.x)
        val bottom = max(action.start.y, action.end.y)
        val rect = RectF(left, top, right, bottom)
        when (action.type) {
            ShapeType.RECTANGLE -> canvas.drawRect(rect, paint)
            ShapeType.ROUNDED_RECTANGLE -> {
                val radius = min(rect.width(), rect.height()) * 0.18f
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            ShapeType.OVAL -> canvas.drawOval(rect, paint)
            ShapeType.LINE -> canvas.drawLine(action.start.x, action.start.y, action.end.x, action.end.y, paint)
            ShapeType.ARROW -> drawArrow(canvas, action.start, action.end, paint)
            ShapeType.DIAMOND -> drawDiamond(canvas, rect, paint)
            ShapeType.TRIANGLE -> drawTriangle(canvas, rect, paint)
        }
    }

    private fun drawDiamond(
        canvas: Canvas,
        rect: RectF,
        paint: Paint,
    ) {
        val path = Path().apply {
            moveTo(rect.centerX(), rect.top)
            lineTo(rect.right, rect.centerY())
            lineTo(rect.centerX(), rect.bottom)
            lineTo(rect.left, rect.centerY())
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawTriangle(
        canvas: Canvas,
        rect: RectF,
        paint: Paint,
    ) {
        val path = Path().apply {
            moveTo(rect.centerX(), rect.top)
            lineTo(rect.right, rect.bottom)
            lineTo(rect.left, rect.bottom)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawArrow(
        canvas: Canvas,
        start: EditorPoint,
        end: EditorPoint,
        paint: Paint,
    ) {
        canvas.drawLine(start.x, start.y, end.x, end.y, paint)
        val dx = end.x - start.x
        val dy = end.y - start.y
        val distance = kotlin.math.sqrt((dx * dx) + (dy * dy)).coerceAtLeast(1f)
        val unitX = dx / distance
        val unitY = dy / distance
        val headLength = max(28f, paint.strokeWidth * 2.2f)
        val wingX = -unitY
        val wingY = unitX
        canvas.drawLine(
            end.x,
            end.y,
            end.x - (unitX * headLength) + (wingX * headLength * 0.45f),
            end.y - (unitY * headLength) + (wingY * headLength * 0.45f),
            paint,
        )
        canvas.drawLine(
            end.x,
            end.y,
            end.x - (unitX * headLength) - (wingX * headLength * 0.45f),
            end.y - (unitY * headLength) - (wingY * headLength * 0.45f),
            paint,
        )
    }

    private fun drawText(
        canvas: Canvas,
        action: EditAction.Text,
        overrideAlpha: Int?,
    ) {
        val paint = createTextPaint(action).apply {
            overrideAlpha?.let { alpha = it }
        }
        val lines = action.text.split('\n')
        val metrics = paint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val baselineShift = -((metrics.ascent + metrics.descent) / 2f)
        val startY = action.anchor.y - ((max(1, lines.size) - 1) * lineHeight / 2f)
        lines.forEachIndexed { index, line ->
            canvas.drawText(
                line,
                action.anchor.x,
                startY + (index * lineHeight) + baselineShift,
                paint,
            )
        }
    }

    private fun drawMosaic(
        canvas: Canvas,
        action: EditAction.Mosaic,
        mosaicBitmap: Bitmap?,
    ) {
        val bitmap = mosaicBitmap ?: return
        val strokePath = createPath(action.points)
        val fillPath = Path()
        val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = action.strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        clipPaint.getFillPath(strokePath, fillPath)
        canvas.withClip(fillPath) { drawBitmap(bitmap, 0f, 0f, null) }
    }

    private fun createTextPaint(action: EditAction.Text) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = action.color
        textSize = action.textSize
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
        setShadowLayer(max(2f, action.textSize * 0.08f), 0f, 0f, Color.argb(96, 0, 0, 0))
    }
}
