package io.github.lumiagg.gestureevo.utils.icon

import androidx.core.graphics.createBitmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException

internal fun imageSampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
    require(maxWidth > 0 && maxHeight > 0)
    // Preserve the original importer's 1.4x tolerance and integer sampling behavior.
    // w / sample <= floor(limit * 1.4) is equivalent to sample > w / (floor(limit * 1.4) + 1).
    val widthLimit = (maxWidth * 1.4).toLong() + 1
    val heightLimit = (maxHeight * 1.4).toLong() + 1
    return maxOf(width.toLong() / widthLimit + 1, height.toLong() / heightLimit + 1, 1).toInt()
}

object IconImageIO {
    fun read(path: String): Bitmap? {
        if (path.isBlank() || !File(path).isFile) return null
        return try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            Log.w("IconImageIO", "Unable to decode icon file", e)
            null
        }
    }

    fun read(context: Context, uri: Uri?, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (uri == null || maxWidth <= 0 || maxHeight <= 0) return null
        return try {
            val resolver = context.contentResolver
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            options.inSampleSize = imageSampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            Log.w("IconImageIO", "Unable to decode selected icon", e)
            null
        }
    }

    fun savePng(bitmap: Bitmap?, path: String): Boolean {
        if (bitmap == null || bitmap.isRecycled || path.isBlank()) return false
        val file = AtomicFile(File(path))
        val stream = try {
            file.startWrite()
        } catch (e: IOException) {
            Log.w("IconImageIO", "Unable to open icon file for writing", e)
            return false
        }
        return try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IOException("PNG encoder failed")
            }
            file.finishWrite(stream)
            true
        } catch (e: Exception) {
            file.failWrite(stream)
            Log.w("IconImageIO", "Unable to save icon", e)
            false
        }
    }

    fun savePng(drawable: Drawable?, path: String, fallbackSize: Int): Boolean {
        if (drawable == null || fallbackSize <= 0) return false
        val hasIntrinsicSize = drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0
        val width = if (hasIntrinsicSize) drawable.intrinsicWidth else fallbackSize
        val height = if (hasIntrinsicSize) drawable.intrinsicHeight else fallbackSize
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val originalBounds = Rect(drawable.bounds)
        return try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            savePng(bitmap, path)
        } finally {
            drawable.bounds = originalBounds
            bitmap.recycle()
        }
    }
}
