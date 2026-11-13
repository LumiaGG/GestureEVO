package io.github.lumiagg.gestureevo.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import io.github.lumiagg.gestureevo.utils.MlKitOcr

/**
 * Synthetic-image round trip for the settings OCR test row. Runs through the MlKitOcr
 * facade, so it always exercises exactly the engine (and options) the user selected.
 */
object OcrSelfTest {
    private const val SAMPLE_TEXT = "GestureEVO OCR 测试 67890"

    data class Outcome(val lines: Int, val elapsedMs: Long)

    suspend fun run(): Outcome {
        val bitmap = drawSample(SAMPLE_TEXT)
        try {
            val started = SystemClock.elapsedRealtime()
            val nodes = MlKitOcr.detect(bitmap)
            val elapsedMs = SystemClock.elapsedRealtime() - started
            val expected = SAMPLE_TEXT.replace(Regex("\\s+"), "")
            val recognized = nodes.joinToString("") { it.content.orEmpty() }.replace(Regex("\\s+"), "")
            if (!recognized.contains(expected)) {
                throw IllegalStateException("识别文字不匹配: $recognized")
            }
            return Outcome(nodes.size, elapsedMs)
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawSample(text: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 96f
        }
        val width = (paint.measureText(text) + 160f).toInt().coerceAtLeast(720)
        val bitmap = Bitmap.createBitmap(width, 240, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(text, 80f, 150f, paint)
        }
        return bitmap
    }
}
