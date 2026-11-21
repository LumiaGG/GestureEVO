package io.github.lumiagg.gestureevo.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@SdkSuppress(minSdkVersion = OCR_MIN_API)
class ScreenOcrTest {
    @Test
    fun screenshotAndLineUseOriginalPixelCoordinates() = runBlocking {
        val bitmap = Bitmap.createBitmap(420, 120, Bitmap.Config.ARGB_8888)
        try {
            ScreenOcr.preload()
            bitmap.eraseColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 38f
                typeface = Typeface.DEFAULT
            }
            Canvas(bitmap).drawText("Gesture", 35f, 76f, paint)

            val boxes = ScreenOcr.detectTextBoxes(bitmap)
            assertFalse(boxes.isEmpty())
            val box = boxes.first { it.contains(80, 62) }
            assertTrue(box.left >= 0 && box.top >= 0 && box.right <= bitmap.width && box.bottom <= bitmap.height)
            val results = ScreenOcr.detect(bitmap)
            assertTrue(results.any { it.bound.contains(80, 62) && it.content.orEmpty().contains("Gesture", ignoreCase = true) })

            val line = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
            try {
                assertTrue(ScreenOcr.recognizeTextBox(line).contains("Gesture", ignoreCase = true))
            } finally {
                line.recycle()
            }

            bitmap.eraseColor(Color.WHITE)
            assertTrue(ScreenOcr.detect(bitmap).isEmpty())
        } finally {
            ScreenOcr.releaseSession()
            bitmap.recycle()
        }
    }
}
