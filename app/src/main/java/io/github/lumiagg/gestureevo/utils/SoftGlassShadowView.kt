package io.github.lumiagg.gestureevo.utils

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View

/** One rounded rect, blurred by the system and cached until the card moves. */
// Only constructed in code: its runtime blur radius is not an XML style attribute.
@SuppressLint("ViewConstructor")
internal class SoftGlassShadowView(
    context: Context,
    radiusPx: Float = 28f.dp2px,
) : View(context) {
    val blurRadius = radiusPx
    var cornerRadius = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2C000000
    }
    private val shape = RectF()
    private val spread = radiusPx * 0.28f
    private val shapeInset = (radiusPx - spread).coerceAtLeast(0f)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.DECAL))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(0, 0)
    }

    override fun layout(l: Int, t: Int, r: Int, b: Int) {
        if (r <= l || b <= t) return
        super.layout(l, t, r, b)
    }

    override fun onDraw(canvas: Canvas) {
        if (cornerRadius <= 0f || width <= 0 || height <= 0) return
        // Keep part of the dark shape outside the card. An opaque card hides the
        // shadow that sits only underneath it.
        shape.set(shapeInset, shapeInset, width - shapeInset, height - shapeInset)
        val radius = cornerRadius + spread
        canvas.drawRoundRect(shape, radius, radius, paint)
    }
}
