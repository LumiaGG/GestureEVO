package io.github.lumiagg.gestureevo.drop

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded software decoding shared by content conversion and drop execution. */
internal class DropImageDecoder(private val context: Context) {
    suspend fun decode(uri: Uri): ManagedBitmap? {
        var decoded: ManagedBitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val longest = maxOf(info.size.width, info.size.height)
                        if (longest > MAX_IMAGE_SIDE) {
                            val scale = MAX_IMAGE_SIDE.toFloat() / longest
                            decoder.setTargetSize(
                                (info.size.width * scale).toInt().coerceAtLeast(1),
                                (info.size.height * scale).toInt().coerceAtLeast(1),
                            )
                        }
                    }
                } else {
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        inSampleSize = 1
                    }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    options.inJustDecodeBounds = false
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > MAX_IMAGE_SIDE) {
                        options.inSampleSize *= 2
                    }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                }
                bitmap?.let(ManagedBitmap::from).also { decoded = it }
            }
        } catch (error: Throwable) {
            decoded?.close()
            throw error
        }
    }

    private companion object { const val MAX_IMAGE_SIDE = 4096 }
}
