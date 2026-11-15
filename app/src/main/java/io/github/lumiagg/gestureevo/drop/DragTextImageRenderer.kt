package io.github.lumiagg.gestureevo.drop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap

internal object DragTextImageRenderer {
    fun render(text: String): Bitmap {
        require(text.isNotBlank() && text.length <= 50_000)
        val padding = 32
        val width = 720
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 32f
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - padding * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(true)
            .setLineSpacing(4f, 1f)
            .build()
        val height = layout.height + padding * 2
        require(height in 1..8192)
        return createBitmap(width, height).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                translate(padding.toFloat(), padding.toFloat())
                layout.draw(this)
            }
        }
    }
}
