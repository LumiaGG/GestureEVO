package io.github.lumiagg.gestureevo.drop

import android.content.ClipData
import android.content.Context
import android.net.Uri
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.inspire.OcrImageTextProcessor
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Converts content without owning any windows or drag session state. */
internal class DragContentConverter(
    private val context: Context,
    private val decodeImage: suspend (Uri) -> ManagedBitmap?,
) {
    private val imageShareLauncher = ImageShareLauncher(context.applicationContext)
    suspend fun convert(clip: ClipData): ClipData? {
        val image = clip.description.hasMimeType("image/*")
        val text = buildList {
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                if (image) {
                    val uri = item.uri ?: continue
                    val bitmap = decodeImage(uri) ?: error("Image cannot be decoded")
                    bitmap.use {
                        add(OcrImageTextProcessor(context).process(it.requireBitmap()).rawText)
                    }
                } else {
                    add(withContext(Dispatchers.IO) { item.coerceToText(context)?.toString().orEmpty() })
                }
            }
        }.filter { it.isNotBlank() }.joinToString("\n")
        if (text.isBlank()) return null
        if (image) return ClipData.newPlainText("text", text)
        return withContext(Dispatchers.IO) {
            val bitmap = DragTextImageRenderer.render(text)
            try {
                val payload = checkNotNull(imageShareLauncher.buildPayload(bitmap, null))
                ClipData.newUri(context.contentResolver, "image", payload.contentUris.first())
            } finally {
                bitmap.recycle()
            }
        }
    }

}
