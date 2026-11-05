package io.github.lumiagg.gestureevo.settings.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withClip
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.freeTriggerBounds
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.ScreenUtil

/** Uses the runtime bounds so edge clamping and mirroring match the real trigger. */
class TriggerPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private val regionRect = RectF()
    private var regions: List<Pair<Rect, Int>> = emptyList()
    private var screenWidth = 0f
    private var screenHeight = 0f

    fun bind(config: FreeTriggerConfig, otherTriggers: List<FreeTriggerConfig>) {
        screenWidth = ScreenUtil.getScreenRealWidth(context, false).toFloat()
        screenHeight = ScreenUtil.getScreenRealHeight(context, false).toFloat()
        regions = buildList {
            fun addRegion(item: FreeTriggerConfig, color: Int, mirrorColor: Int = color) {
                add(freeTriggerBounds(context, item) to color)
                if (item.getState(FreeTriggerConfig.FLAG_MIRROR)) {
                    add(freeTriggerBounds(context, item, true) to mirrorColor)
                }
            }
            otherTriggers.filter { it.tag != config.tag && it.enable }.forEach {
                addRegion(it, DynamicColorUtils.outlineColor)
            }
            addRegion(config, DynamicColorUtils.primaryColor, DynamicColorUtils.inversePrimaryColor)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (screenWidth <= 0 || screenHeight <= 0) return
        val inset = 8 * resources.displayMetrics.density
        val scale = minOf((width - 2 * inset) / screenWidth, (height - 2 * inset) / screenHeight)
        if (scale <= 0) return
        val left = (width - screenWidth * scale) / 2
        val top = (height - screenHeight * scale) / 2
        frame.set(left, top, left + screenWidth * scale, top + screenHeight * scale)
        paint.style = Paint.Style.FILL
        paint.color = DynamicColorUtils.surfaceContainerHighColor
        canvas.drawRoundRect(frame, inset, inset, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        paint.color = DynamicColorUtils.outlineColor
        canvas.drawRoundRect(frame, inset, inset, paint)
        canvas.withClip(frame) {
            regions.forEach { (bounds, color) ->
                regionRect.set(left + bounds.left * scale, top + bounds.top * scale,
                    left + bounds.right * scale, top + bounds.bottom * scale)
                paint.color = color
                paint.style = Paint.Style.FILL
                drawRect(regionRect, paint)
                paint.style = Paint.Style.STROKE
                drawRect(regionRect, paint)
            }
        }
    }
}
