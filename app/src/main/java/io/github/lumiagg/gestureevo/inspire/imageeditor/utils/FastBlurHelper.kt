package io.github.lumiagg.gestureevo.inspire.imageeditor.utils

import androidx.core.graphics.scale

import android.graphics.Bitmap
import io.github.lumiagg.gestureevo.inspire.useSoftwareBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object FastBlurHelper {

    fun createBlurredBackground(source: Bitmap): Bitmap? {
        if (source.isRecycled) return null
        return source.useSoftwareBitmap { safeSource ->
            if (safeSource.isRecycled) return@useSoftwareBitmap null
            val srcW = safeSource.width
            val srcH = safeSource.height
            if (srcW <= 0 || srcH <= 0) return@useSoftwareBitmap null

            val maxDim = 180
            val (sampleW, sampleH) = if (srcW > srcH) {
                maxDim to max(1, (maxDim * (srcH.toFloat() / srcW)).roundToInt())
            } else {
                max(1, (maxDim * (srcW.toFloat() / srcH)).roundToInt()) to maxDim
            }

            val small = try {
                safeSource.scale(sampleW, sampleH, true)
            } catch (_: Throwable) {
                return@useSoftwareBitmap null
            }

            try {
                val mutable = if (small.isMutable) small else small.copy(Bitmap.Config.ARGB_8888, true)
                if (mutable !== small && !small.isRecycled) {
                    small.recycle()
                }
                val blurR = min(7, max(1, min(sampleW, sampleH) / 6))
                blurInPlace(mutable, radius = blurR, passes = 2)
                mutable
            } catch (_: Throwable) {
                small
            }
        }
    }

    fun blurInPlace(bitmap: Bitmap, radius: Int, passes: Int = 2) {
        if (radius < 1 || bitmap.isRecycled) return
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return

        val pixels = IntArray(w * h)
        try {
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        } catch (_: Throwable) {
            return
        }

        val temp = IntArray(w * h)
        for (i in 0 until passes) {
            boxBlurHorizontal(pixels, temp, w, h, radius)
            boxBlurVertical(temp, pixels, w, h, radius)
        }

        try {
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                var a = 0
                var red = 0
                var g = 0
                var b = 0
                for (k in -r..r) {
                    val px = (x + k).coerceIn(0, w - 1)
                    val p = src[rowOffset + px]
                    a += (p ushr 24) and 0xFF
                    red += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                }
                dst[rowOffset + x] = ((a / div) shl 24) or
                        ((red / div) shl 16) or
                        ((g / div) shl 8) or
                        (b / div)
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            for (y in 0 until h) {
                var a = 0
                var red = 0
                var g = 0
                var b = 0
                for (k in -r..r) {
                    val py = (y + k).coerceIn(0, h - 1)
                    val p = src[py * w + x]
                    a += (p ushr 24) and 0xFF
                    red += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                }
                dst[y * w + x] = ((a / div) shl 24) or
                        ((red / div) shl 16) or
                        ((g / div) shl 8) or
                        (b / div)
            }
        }
    }
}
