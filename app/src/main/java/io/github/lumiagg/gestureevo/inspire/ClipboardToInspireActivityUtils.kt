package io.github.lumiagg.gestureevo.inspire

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceInspireEvent
import io.github.lumiagg.gestureevo.gesture.InspireEvent
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.pin.PinPoolStore
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.GalleryImageManager
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.uriBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClipboardToInspireActivityUtils(val activity: AppCompatActivity) {

    private val applicationContext = activity.applicationContext

    suspend fun handleIntent(intent: Intent?) {
        when {
            // Handle shared text
            intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                handleSharedContent(sharedText, null)
            }
            // Handle shared image
            (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) && intent.type?.startsWith("image/") == true -> {
                val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                if (imageUri == null) {
                    ToastHelper.showInUIThread(
                        applicationContext,
                        activity.getString(R.string.clipboard_image_missing)
                    )
                    activity.finish()
                    return
                }
                val image = uriToBitmap(imageUri)
                if (image == null) {
                    ToastHelper.showInUIThread(
                        applicationContext,
                        activity.getString(R.string.clipboard_image_read_failed)
                    )
                    activity.finish()
                    return
                }
                handleSharedContent(null, image)
            }
            // Handle clipboard content as fallback
            else -> {
                val action = intent?.getStringExtra("clipAction")
                if (action == null) {
                    activity.finish()
                    return
                }
                when (action) {
                    "pin" -> {
                        if (ClipboardUtils.hasImage(activity)) {
                            handleImage(ClipboardUtils.getImage(activity))
                        } else {
                            handleText(ClipboardUtils.getText(activity))
                        }
                    }

                    "search" -> {
                        val query = ClipboardUtils.getText(activity)
                        if (query.isBlank()) {
                            ToastHelper.showInUIThread(
                                applicationContext,
                                activity.getString(R.string.clipboard_content_missing)
                            )
                            activity.finish()
                            return
                        }
                        val uri = uriBuilder(GestureActionType.SEARCH)
                            .appendQueryParameter("query", query).build()
                        activity.lifecycleScope.launch {
                            ActionProxy(applicationContext, uri.toString()).execute(side = InspireDataHolder.triggerSide)
                            activity.finish()
                        }
                    }

                    "translate" -> {
                        val query = ClipboardUtils.getText(activity)
                        if (query.isBlank()) {
                            ToastHelper.showInUIThread(
                                applicationContext,
                                activity.getString(R.string.clipboard_content_missing)
                            )
                            activity.finish()
                            return
                        }
                        activity.lifecycleScope.launch {
                            InspireHelperUtils.translateText(activity, query)
                            activity.finish()
                        }
                    }

                    "inspire" -> {
                        val image = ClipboardUtils.getImage(activity)?.let { uriToBitmap(it) }
                        val text = ClipboardUtils.getText(activity).takeIf { it.isNotBlank() }
                        if (image == null && text == null) {
                            ToastHelper.showInUIThread(
                                applicationContext,
                                activity.getString(R.string.clipboard_content_missing)
                            )
                            activity.finish()
                            return
                        }
                        launcherInspire(image, text)
                    }

                    "saveImage" -> {
                        val uri = ClipboardUtils.getImage(activity)
                        if (uri == null) {
                            ToastHelper.showInUIThread(
                                applicationContext,
                                activity.getString(R.string.clipboard_image_missing)
                            )
                            activity.finish()
                            return
                        }
                        GalleryImageManager.saveUriImageToGallery(applicationContext, uri)
                        uriToBitmap(uri)?.use { handle ->
                            PinPoolStore.addScreenContentImageIfEnabled(
                                activity,
                                handle.requireBitmap()
                            )
                        }
                        activity.finish()
                    }

                    else -> activity.finish()
                }
            }
        }
    }

    /**
     * Handles shared content based on the default share behavior setting
     */
    private suspend fun handleSharedContent(text: String?, image: ManagedBitmap?) {
        val defaultShareBehavior = applicationContext.settingsStore.readKey(
            InspireSettingsActivity.KEY_DEFAULT_SHARE_BEHAVIOR,
            "tokenization"
        )

        when (defaultShareBehavior) {
            "pinpool" -> {
                // Share to Pin Pool
                if (image != null) {
                    activity.lifecycleScope.launch {
                        ToastHelper.showInUIThread(
                            applicationContext,
                            activity.getString(R.string.clipboard_share_to_pin_pool)
                        )
                        image.use { handle ->
                            try {
                                PinPoolStore.addImagePin(activity, handle.requireBitmap())
                            } finally {
                                activity.finish()
                            }
                        }
                    }
                } else if (!text.isNullOrEmpty()) {
                    ToastHelper.showInUIThread(
                        applicationContext,
                        activity.getString(R.string.clipboard_share_to_pin_pool)
                    )
                    activity.lifecycleScope.launch {
                        PinPoolStore.addTextPin(activity, text)
                        activity.finish()
                    }
                } else {
                    ToastHelper.showInUIThread(
                        applicationContext,
                        activity.getString(R.string.clipboard_share_empty)
                    )
                    activity.finish()
                }
            }
            else -> { // Default to tokenization (Inspire)
                launcherInspire(image, text)
            }
        }
    }

    fun launcherInspire(image: ManagedBitmap?, text: String?) {
        InspireDataHolder.clear()
        activity.lifecycleScope.launch {
            image?.use {
                InspireDataHolder.replaceScreenshotBitmap(it)
                PinPoolStore.addScreenContentImageIfEnabled(activity, it.requireBitmap())
            }
            text?.let {
                ArrayList<String>().apply { add(it) }.let { words ->
                    InspireDataHolder.accessibilityContent = words
                }
            }
            InspireContentActivity.start(activity)
            activity.finish()
        }
    }

    private fun handleText(text: String?) {
        if (text.isNullOrEmpty()) {
            ToastHelper.showInUIThread(
                applicationContext,
                activity.getString(R.string.clipboard_content_missing)
            )
            activity.finish()
        } else {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                produceInspireEvent(InspireEvent(text = text))
                withContext(Dispatchers.Main) {
                    activity.finish()
                }
            }
        }
    }

    private fun handleImage(uri: Uri?) {
        if (uri == null) {
            ToastHelper.showInUIThread(
                applicationContext,
                activity.getString(R.string.clipboard_image_missing)
            )
            activity.finish()
            return
        }

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmapHandle = uriToBitmap(uri)
                if (bitmapHandle != null) {
                    Log.d("PinClipboardActivity", "handleImage: success")
                    produceInspireEvent(InspireEvent(image = bitmapHandle))
                } else {
                    ToastHelper.showInUIThread(
                        applicationContext,
                        activity.getString(R.string.clipboard_image_read_failed)
                    )
                }
            } catch (e: Exception) {
                Log.e("PinClipboardActivity", "Error handling image (${e.javaClass.simpleName})")
                ToastHelper.showInUIThread(
                    applicationContext,
                    activity.getString(R.string.clipboard_image_process_error)
                )
            } finally {
                withContext(Dispatchers.Main) {
                    activity.finish()
                }
            }
        }
    }

    private suspend fun uriToBitmap(uri: Uri): ManagedBitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = decodeBitmapFromUri(uri)
                bitmap?.let(ManagedBitmap::from)
            } catch (e: Exception) {
                Log.e("PinClipboardActivity", "Error converting image to Bitmap (${e.javaClass.simpleName})")
                null
            }
        }
    }

    private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = if (uri.scheme == "file" || uri.scheme == null) {
                    val path = uri.path ?: uri.toString()
                    ImageDecoder.createSource(File(path))
                } else {
                    ImageDecoder.createSource(activity.contentResolver, uri)
                }
                return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } catch (e: Exception) {
                Log.w("PinClipboardActivity", "ImageDecoder failed (${e.javaClass.simpleName}); falling back to stream decoding")
            }
        }

        return try {
            if (uri.scheme == "file" || uri.scheme == null) {
                val path = uri.path ?: uri.toString()
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                    inMutable = true
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            } else {
                activity.contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply {
                        inMutable = true
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }
        } catch (e: Exception) {
            Log.e("PinClipboardActivity", "Fallback image decode failed (${e.javaClass.simpleName})")
            null
        }
    }
}
