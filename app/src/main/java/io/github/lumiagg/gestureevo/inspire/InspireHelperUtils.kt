package io.github.lumiagg.gestureevo.inspire

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.pin.PinPoolStore
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_INSPIRE_DELAY_REMOVE_IMAGE
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_SEARCH_APP
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_SEARCH_LINK
import io.github.lumiagg.gestureevo.settings.ui.DomainAppManager
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.GalleryImageManager
import io.github.lumiagg.gestureevo.utils.GestureActionLink
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.UrlDetector
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.uriBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

object InspireHelperUtils {
    suspend fun extractAndOpenURL(context: Context, text: String, side: String? = null): Boolean {
        val urls = UrlDetector.findUrls(text)

        if (urls.isNotEmpty()) {
            val url = UrlDetector.normalizeForOpening(urls.first()) ?: urls.first()

            // Attempt domain-specific app matching
            val domainAppManager = DomainAppManager(context)
            val (pkg, finalUrl) = domainAppManager.processLinkRedirect(url)
            val effectiveSide = side ?: InspireDataHolder.triggerSide
            if (pkg.isNotEmpty()) {
                val actionLink = GestureActionLink()
                actionLink.actionType = GestureActionType.OTHER_LINK
                actionLink.addParams("uri", finalUrl)
                actionLink.addParams("pkg", pkg)
                actionLink.addParams("freeform", "true")
                effectiveSide?.let { actionLink.addParams("triggerSide", it) }
                ActionProxy(context, actionLink.buildLink()).execute(side = effectiveSide)
                return true
            }

            // Fallback to opening link with default browser or handler
            val packageManager = context.packageManager
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) != null) {
                context.startActivity(intent)
                return true
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.inspire_link_no_app),
                        Toast.LENGTH_SHORT
                    )
                        .show()
                }
                return false
            }
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.inspire_link_not_found), Toast.LENGTH_SHORT).show()
        }
        return false
    }


    private suspend fun saveImageToGalleryInternal(
        context: Context,
        bitmap: Bitmap,
        isPress: Boolean
    ): Uri? =
        withContext(
            Dispatchers.IO
        ) {
            val delayRemove = context.settingsStore.readKey(KEY_INSPIRE_DELAY_REMOVE_IMAGE, false)
            val delayTime = if (!isPress && delayRemove) 5 else 0
            return@withContext GalleryImageManager.saveImageToGalleryWithAutoDelete(
                context,
                bitmap,
                delayTime.toLong()
            )
        }

    suspend fun saveImageToGallery(context: Context, bitmapHandle: ManagedBitmap, isPress: Boolean): Uri? =
        bitmapHandle.acquire()?.use { handle ->
            saveImageToGalleryInternal(context, handle.requireBitmap(), isPress)
        }

    private suspend fun saveBitmapToCacheFileInternal(
        context: Context,
        bitmap: Bitmap,
        prefix: String = "inspire"
    ): File? = withContext(Dispatchers.IO) {
        try {
            val imagesFolder = File(context.cacheDir, "images").apply { mkdirs() }
            val file = File(imagesFolder, "${prefix}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun saveBitmapToCacheFile(
        context: Context,
        bitmapHandle: ManagedBitmap,
        prefix: String = "inspire"
    ): File? = bitmapHandle.acquire()?.use { handle ->
        saveBitmapToCacheFileInternal(context, handle.requireBitmap(), prefix)
    }
    fun shareText(context: Context, text: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.inspire_share_text_title))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            ToastHelper.showInUIThread(context, context.getString(R.string.inspire_share_text_failed))
        }
    }

    private suspend fun copyBitmapToClipboardInternal(
        context: Context,
        bitmap: Bitmap,
        showToast: Boolean = true,
        autoSaveToPinPool: Boolean = false
    ) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // Create temporary file to save bitmap
        val imagesFolder = File(context.cacheDir, "images")
        imagesFolder.mkdirs()
        val file = File(imagesFolder, "shared_image.png")

        try {
            // Write bitmap to file
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            stream.close()

            // Create FileProvider content URI
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            // Create ClipData and set to clipboard
            val clip = ClipData.newUri(
                context.contentResolver,
                context.getString(R.string.inspire_clipboard_image_label),
                contentUri
            )
            clipboard.setPrimaryClip(clip)
            if (autoSaveToPinPool) {
                try {
                    PinPoolStore.addScreenContentImageIfEnabled(context, bitmap)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("InspireHelperUtils", "Failed to auto-save copied image to Pin pool", e)
                }
            }
            if (showToast) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.inspire_clipboard_image_copied),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.inspire_clipboard_image_copy_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    suspend fun copyBitmapToClipboard(
        context: Context,
        bitmapHandle: ManagedBitmap,
        showToast: Boolean = true,
        autoSaveToPinPool: Boolean = false
    ) {
        bitmapHandle.acquire()?.use { handle ->
            copyBitmapToClipboardInternal(context, handle.requireBitmap(), showToast, autoSaveToPinPool)
        }
    }

    fun pinImage(context: GestureService, bitmapHandle: ManagedBitmap) {
        bitmapHandle.acquire()?.let { PictureFloating.build(context, it) }
    }

    fun pinText(context: GestureService, text: String) {
        PictureFloating.build(context, text)
    }

    private fun scanQRInternal(context: Context, bitmap: Bitmap): List<String>? {
        try {
            return bitmap.useSoftwareBitmap { softwareBitmap ->
                // Get bitmap dimensions
                val width = softwareBitmap.width
                val height = softwareBitmap.height
                val pixels = IntArray(width * height)
                softwareBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                // Try multiple luminance sources for better color handling
                val sources = mutableListOf<LuminanceSource>()

                // Standard RGB luminance source
                sources.add(RGBLuminanceSource(width, height, pixels))

                // Inverted luminance source (helps with inverted QR codes)
                val invertedSource = sources[0].invert()
                sources.add(invertedSource)

                // Custom luminance source with enhanced color handling
                val enhancedPixels = IntArray(width * height)
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)

                    // This gives more weight to green channel which human eyes are most sensitive to
                    val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    enhancedPixels[i] = Color.rgb(luminance, luminance, luminance)
                }
                sources.add(RGBLuminanceSource(width, height, enhancedPixels))

                val reader = MultiFormatReader()
                val hints = mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to arrayListOf(
                        BarcodeFormat.QR_CODE,
                        BarcodeFormat.EAN_13,
                        BarcodeFormat.EAN_8,
                        BarcodeFormat.CODE_128
                    ),
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.CHARACTER_SET to "UTF-8"
                )

                for (source in sources) {
                    try {
                        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                        val result = reader.decode(binaryBitmap, hints)
                        return@useSoftwareBitmap SmartSegmenter.segment(context, result.text)
                    } catch (e: NotFoundException) {
                        continue
                    }
                }

                for (source in sources) {
                    try {
                        val binaryBitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
                        val result = reader.decode(binaryBitmap, hints)
                        return@useSoftwareBitmap SmartSegmenter.segment(context, result.text)
                    } catch (e: NotFoundException) {
                        continue
                    }
                }

                ToastHelper.showInUIThread(context, context.getString(R.string.inspire_qr_not_found))
                null
            }

        } catch (e: Exception) {
            ToastHelper.showInUIThread(
                context,
                context.getString(R.string.inspire_qr_decode_error, e.message ?: "")
            )
            return null
        }
    }

    fun scanQR(context: Context, bitmapHandle: ManagedBitmap): List<String>? =
        bitmapHandle.acquire()?.use { handle ->
            scanQRInternal(context, handle.requireBitmap())
        }

    suspend fun translateText(context: Context, text: String) {
        val pkg = context.settingsStore.readKey(InspireSettingsActivity.KEY_TRANSLATION_APP, "")
        if (pkg.isEmpty()) {
            ToastHelper.showInUIThread(
                context,
                context.getString(R.string.inspire_translation_app_not_set)
            )
            return
        }
        val intent = Intent().apply {
            setPackage(pkg)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
            type = "text/plain"
            action = Intent.ACTION_PROCESS_TEXT
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        }
        withContext(Dispatchers.Main) {
            try {
                context.startActivity(intent)
            } catch (ignored: ActivityNotFoundException) {
                ToastHelper.showInUIThread(
                    context,
                    context.getString(R.string.inspire_translation_app_not_found)
                )
            } catch (ignored: SecurityException) {
            }
        }
    }

    suspend fun searchText(context: Context, text: String) {
        val encodedQuery = URLEncoder.encode(text, "UTF-8")
        val searchLink = context.settingsStore.readKey(KEY_SEARCH_LINK, "")
        if (searchLink.isEmpty()) {
            val searchApp = context.settingsStore.readKey(KEY_SEARCH_APP, "")
            if (searchApp.isEmpty()) {
                val link = uriBuilder(GestureActionType.SEARCH)
                    .appendQueryParameter("query", text)
                    .build()
                ActionProxy(context, link.toString()).execute(side = InspireDataHolder.triggerSide)
                return
            }
            val intent = Intent().apply {
                setPackage(searchApp)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                type = "text/plain"
                action = Intent.ACTION_PROCESS_TEXT
                putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            }
            withContext(Dispatchers.Main) {
                try {
                    context.startActivity(intent)
                } catch (ignored: ActivityNotFoundException) {
                    ToastHelper.showInUIThread(
                        context,
                        context.getString(R.string.inspire_search_target_app_invalid)
                    )
                } catch (ignored: SecurityException) {
                }
            }
        } else {
            try {
                val uri = searchLink.replace("%s", encodedQuery).toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(intent)
                }
            } catch (ignored: Exception) {
                ToastHelper.showInUIThread(
                    context,
                    context.getString(R.string.inspire_search_link_error)
                )
                ignored.printStackTrace()
            }
        }
    }
}
