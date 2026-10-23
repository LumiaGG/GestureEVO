package io.github.lumiagg.gestureevo.utils.icon

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap

/** Preview and PNG export share the same drawing path. Wheel styling stays in the host view. */
internal class EditableIconDrawable(private val source: Bitmap) : Drawable() {
    var scale = 1f
    var roundPercent = 0f
    var offsetX = 0f
    var offsetY = 0f
    private val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = this@EditableIconDrawable.shader
    }
    private val matrix = Matrix()
    private val destination = RectF()

    override fun getIntrinsicWidth() = 512
    override fun getIntrinsicHeight() = 512

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val geometry = iconEditGeometry(
            source.width, source.height, bounds.width(), bounds.height(),
            scale, roundPercent, offsetX, offsetY,
        )
        val left = bounds.left + geometry.left
        val top = bounds.top + geometry.top
        matrix.setScale(geometry.width / source.width, geometry.height / source.height)
        matrix.postTranslate(left, top)
        shader.setLocalMatrix(matrix)
        destination.set(left, top, left + geometry.width, top + geometry.height)
        val checkpoint = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawRoundRect(destination, geometry.cornerRadius, geometry.cornerRadius, paint)
        canvas.restoreToCount(checkpoint)
    }

    fun renderBitmap(): Bitmap {
        // A separate drawable avoids changing the bounds of the on-screen preview.
        val export = EditableIconDrawable(source).also {
            it.scale = scale
            it.roundPercent = roundPercent
            it.offsetX = offsetX
            it.offsetY = offsetY
            it.setBounds(0, 0, 512, 512)
        }
        return createBitmap(512, 512).also { export.draw(Canvas(it)) }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
