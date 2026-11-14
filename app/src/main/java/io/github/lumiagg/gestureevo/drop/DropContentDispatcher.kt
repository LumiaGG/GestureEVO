package io.github.lumiagg.gestureevo.drop

import android.content.ClipData
import android.content.Context
import android.net.Uri
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.inspire.InspireContentActivity
import io.github.lumiagg.gestureevo.inspire.InspireDataHolder
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.inspire.OcrImageTextProcessor
import io.github.lumiagg.gestureevo.pin.PinPoolStore
import io.github.lumiagg.gestureevo.search.config.EngineType
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import io.github.lumiagg.gestureevo.utils.ToastHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Executes a selected destination without knowing window geometry or drag session state. */
internal class DropContentDispatcher(
    private val context: Context,
    private val imageDecoder: DropImageDecoder,
    private val showTextPin: (String) -> Unit,
    // Ownership of the acquired image is transferred to the Pin window.
    private val showImagePin: (ManagedBitmap) -> Unit,
) {
    enum class Tool { POOL, PIN, TOKENIZE }
    private val imageShareLauncher = ImageShareLauncher(context.applicationContext)
    private suspend fun isImage(clip: ClipData, uri: Uri?): Boolean =
        uri != null && withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(uri)
            if (mime != null) mime.startsWith("image/") else clip.description.hasMimeType("image/*")
        }

    suspend fun search(clip: ClipData, engine: SearchEngine, freeform: Boolean) {
        val images = mutableListOf<Uri>()
        val texts = mutableListOf<String>()
        // Dual-mode engines keep both content types for the executor's mode selection.
        val collectImages = engine.supportsImageShare()
        val collectTexts = engine.engineType != EngineType.SHARE_IMAGE_TO_APP
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            val uri = item.uri
            if (collectImages && isImage(clip, uri)) {
                imageDecoder.decode(checkNotNull(uri))?.use { image ->
                    // Copy into EVO's sharing cache before the source's transient grant ends.
                    val payload = withContext(Dispatchers.IO) {
                        imageShareLauncher.buildPayload(image.requireBitmap(), null)
                    }
                    images += checkNotNull(payload).contentUris
                }
            } else if (collectTexts && !isImage(clip, uri)) {
                val text = withContext(Dispatchers.IO) { item.coerceToText(context)?.toString() }
                if (!text.isNullOrBlank()) texts += text
            }
        }
        if (images.isEmpty() && texts.isEmpty()) {
            toast(R.string.external_drop_empty)
            return
        }
        DropSearchActivity.start(context, engine.tag, texts.joinToString("\n"), images, freeform)
    }

    suspend fun process(clip: ClipData, action: Tool) {
        val tokenText = mutableListOf<String>()
        var tokenImage: ManagedBitmap? = null
        var handled = 0
        try {
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                val uri = item.uri
                if (isImage(clip, uri)) {
                    val image = imageDecoder.decode(checkNotNull(uri)) ?: error("Cannot decode dropped image")
                    image.use {
                        when (action) {
                            Tool.POOL -> checkNotNull(PinPoolStore.addImagePin(context, it.requireBitmap()))
                            Tool.PIN -> showImagePin(checkNotNull(it.acquire()))
                            Tool.TOKENIZE -> {
                                if (clip.itemCount == 1) tokenImage = it.acquire()
                                else tokenText += withContext(Dispatchers.Default) {
                                    OcrImageTextProcessor(context).process(it.requireBitmap()).rawText
                                }
                            }
                        }
                    }
                } else {
                    val text = withContext(Dispatchers.IO) { item.coerceToText(context)?.toString() }
                        ?.takeIf { it.isNotBlank() } ?: continue
                    when (action) {
                        Tool.POOL -> checkNotNull(PinPoolStore.addTextPin(context, text))
                        Tool.PIN -> showTextPin(text)
                        Tool.TOKENIZE -> tokenText += text
                    }
                }
                handled++
            }
            if (handled == 0) {
                toast(R.string.external_drop_empty)
            } else if (action == Tool.TOKENIZE) {
                InspireDataHolder.clear()
                InspireDataHolder.replaceScreenshotBitmap(tokenImage)
                InspireDataHolder.accessibilityContent = tokenText
                    .joinToString("\n")
                    .takeIf { it.isNotEmpty() }
                InspireContentActivity.start(context)
            } else if (action == Tool.POOL) {
                toast(R.string.external_drop_added)
            }
        } finally {
            tokenImage?.close()
        }
    }

    private fun toast(message: Int) = ToastHelper.showInUIThread(context, context.getString(message))
}
