package io.github.lumiagg.gestureevo.inspire.imageeditor.utils

import androidx.core.graphics.createBitmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.EditAction
import io.github.lumiagg.gestureevo.inspire.useSoftwareBitmap
import kotlin.math.max
import kotlin.math.roundToInt

import android.util.Log

object EditorExportEngine {

    private const val TAG = "EditorExportEngine"

    fun exportBitmap(
        sourceBitmap: Bitmap,
        actions: List<EditAction>,
        scaleFactor: Float,
    ): Bitmap? {
        if (sourceBitmap.isRecycled) return null
        return try {
            sourceBitmap.useSoftwareBitmap { safeBitmap ->
                if (safeBitmap.isRecycled) return@useSoftwareBitmap null
                val outputScale = scaleFactor.coerceAtLeast(0.1f)
                val outWidth = max(1, (safeBitmap.width * outputScale).roundToInt())
                val outHeight = max(1, (safeBitmap.height * outputScale).roundToInt())
                val output = createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
                val mosaicBitmap = EditorRenderUtils.generateMosaicBitmap(safeBitmap)
                try {
                    val canvas = Canvas(output)
                    canvas.scale(outputScale, outputScale)
                    canvas.drawBitmap(safeBitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                    val layerId = canvas.saveLayer(
                        0f,
                        0f,
                        safeBitmap.width.toFloat(),
                        safeBitmap.height.toFloat(),
                        null,
                    )
                    actions.forEach { action ->
                        EditorRenderUtils.drawAction(canvas, action, mosaicBitmap)
                    }
                    canvas.restoreToCount(layerId)
                    output
                } finally {
                    if (!mosaicBitmap.isRecycled) {
                        mosaicBitmap.recycle()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to export bitmap", t)
            null
        }
    }
}
