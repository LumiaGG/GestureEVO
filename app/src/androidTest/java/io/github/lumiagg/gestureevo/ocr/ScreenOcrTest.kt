package io.github.lumiagg.gestureevo.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@SdkSuppress(minSdkVersion = OCR_MIN_API)
class ScreenOcrTest {
    @Test
    fun handoffIsSingleUseAndClosingOldOwnerLeavesNewOwnerUsable() = runBlocking {
        val previous = ScreenOcr.openSession()
        val offered = ScreenOcr.openSession()
        val token = ScreenOcr.offerHandoff(offered)
        val claimed = requireNotNull(ScreenOcr.takeHandoff(token))
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            assertSame(offered, claimed)
            assertNull(ScreenOcr.takeHandoff(token))
            previous.close()
            val rejected = runCatching { ScreenOcr.detectTextBoxes(bitmap, previous) }
            assertTrue(rejected.exceptionOrNull() is CancellationException)

            bitmap.eraseColor(Color.WHITE)
            assertTrue(ScreenOcr.detectTextBoxes(bitmap, claimed).isEmpty())
        } finally {
            previous.close()
            claimed.close()
            bitmap.recycle()
        }
    }

    @Test
    fun screenshotAndLineUseOriginalPixelCoordinates() = runBlocking {
        val session = ScreenOcr.openSession()
        val bitmap = Bitmap.createBitmap(420, 120, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 38f
                typeface = Typeface.DEFAULT
            }
            Canvas(bitmap).drawText("Gesture", 35f, 76f, paint)

            val boxes = ScreenOcr.detectTextBoxes(bitmap, session)
            assertFalse(boxes.isEmpty())
            val box = boxes.first { it.contains(80, 62) }
            assertTrue(box.left >= 0 && box.top >= 0 && box.right <= bitmap.width && box.bottom <= bitmap.height)
            val results = ScreenOcr.detect(bitmap, session)
            assertTrue(results.any { it.bound.contains(80, 62) && it.content.orEmpty().contains("Gesture", ignoreCase = true) })

            val line = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
            try {
                assertTrue(ScreenOcr.recognizeTextBox(line, session).contains("Gesture", ignoreCase = true))
            } finally {
                line.recycle()
            }

            bitmap.eraseColor(Color.WHITE)
            assertTrue(ScreenOcr.detect(bitmap, session).isEmpty())
        } finally {
            session.close()
            bitmap.recycle()
        }
    }
}
