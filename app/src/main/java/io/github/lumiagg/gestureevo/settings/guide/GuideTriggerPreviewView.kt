package io.github.lumiagg.gestureevo.settings.guide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.freeTriggerBounds
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils

class GuideTriggerPreviewView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val paint = Paint()
    private val screenLocation = IntArray(2)
    private var draft: QuickSetupConfig? = null
    private var trigger: FreeTriggerConfig? = null

    internal fun show(config: QuickSetupConfig?) {
        if (draft == config) return
        draft = config
        trigger = config?.let {
            applyDefaultTriggerConfig(it.baseTrigger?.copy() ?: FreeTriggerConfig(), it, "")
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val config = trigger ?: return
        paint.color = ColorUtils.setAlphaComponent(DynamicColorUtils.primaryColor, 150)
        getLocationOnScreen(screenLocation)
        canvas.save()
        canvas.translate(-screenLocation[0].toFloat(), -screenLocation[1].toFloat())
        canvas.drawRect(freeTriggerBounds(context, config), paint)
        if (config.getState(FreeTriggerConfig.FLAG_MIRROR)) {
            canvas.drawRect(freeTriggerBounds(context, config, isMirror = true), paint)
        }
        canvas.restore()
    }
}
