package io.github.lumiagg.gestureevo.utils

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Utility functions for interacting with the system clipboard.
 */
object ClipboardUtils {
    private const val TAG = "ClipboardUtils"

    private fun clipboard(context: Context): ClipboardManager? =
        context.getSystemService(ClipboardManager::class.java)

    /**
     * Copies text to the clipboard.
     *
     * @param context Context
     * @param text The text to copy
     */
    fun copyText(context: Context, text: String) {
        val clipboard = clipboard(context) ?: return
        val clip = ClipData.newPlainText("text", text)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * Retrieves text from the clipboard.
     *
     * @param context Context
     * @return The clipboard text, or an empty string if none
     */
    fun getText(context: Context): String {
        val clipboard = clipboard(context) ?: return ""
        return try {
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) "" else clip.getItemAt(0).text?.toString().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read clipboard text (${e.javaClass.simpleName})")
            ""
        }
    }

    /**
     * Copies a bitmap image to the clipboard through the app FileProvider.
     *
     * The PNG is written into the `cacheDir/images` directory declared in `file_paths.xml`,
     * so no storage permission is required and nothing is inserted into MediaStore.
     *
     * This is the single low-level implementation for image clipboard writes; callers keep
     * their own user-facing feedback around it.
     *
     * @param context Context
     * @param bitmap The image to copy
     * @param fileName Cache file name; give each caller its own to avoid clobbering
     * @param quality PNG compression hint, passed to [Bitmap.compress]
     * @param label ClipData label
     * @return true when the clipboard was updated
     */
    fun copyImageToClipboard(
        context: Context,
        bitmap: Bitmap,
        fileName: String = "clipboard_auto_image.png",
        quality: Int = 100,
        label: String = "image",
    ): Boolean {
        val clipboard = clipboard(context) ?: return false
        return try {
            val imagesFolder = File(context.cacheDir, "images").apply { mkdirs() }
            val file = File(imagesFolder, fileName)
            val written = FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, quality, output)
            }
            if (!written) {
                Log.w(TAG, "Unable to compress bitmap for the clipboard")
                return false
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, label, uri))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to copy image to clipboard (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * Retrieves an image URI from the clipboard.
     *
     * @param context Context
     * @return The image URI from the clipboard, or null if none
     */
    fun getImage(context: Context): Uri? {
        val clipboard = clipboard(context) ?: return null
        return try {
            val clip = clipboard.primaryClip ?: return null
            val isClipImageDescription = clip.description.hasMimeType("image/*")

            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i) ?: continue
                val uri = item.uri
                if (uri != null && (isClipImageDescription || isImageUri(context, uri))) {
                    return uri
                }
                val text = item.text?.toString()?.trim()
                if (!text.isNullOrEmpty() && (text.startsWith("file://") || text.endsWith(".png", true) || text.endsWith(".jpg", true) || text.endsWith(".jpeg", true) || text.endsWith(".webp", true))) {
                    try {
                        val parsed = text.toUri()
                        if (isImageUri(context, parsed) || (parsed.scheme == "file" && File(parsed.path ?: text).exists())) {
                            return parsed
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read clipboard image (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * Checks whether the clipboard contains plain text.
     *
     * @param context Context
     * @return True if plain text is present, false otherwise
     */
    fun hasText(context: Context): Boolean {
        val clipboard = clipboard(context) ?: return false
        return clipboard.hasPrimaryClip() && clipboard.primaryClip?.description?.hasMimeType(
            ClipDescription.MIMETYPE_TEXT_PLAIN
        ) == true
    }

    /**
     * Checks whether the clipboard contains an image.
     *
     * @param context Context
     * @return True if an image is present, false otherwise
     */
    fun hasImage(context: Context): Boolean {
        return getImage(context) != null
    }

    /**
     * Checks whether a URI points to an image.
     *
     * @param context Context
     * @param uri URI to check
     * @return True if the URI represents an image, false otherwise
     */
    fun isImageUri(context: Context, uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType?.startsWith("image/") == true) return true
            val path = uri.path ?: uri.toString()
            val lower = path.lowercase(Locale.ROOT)
            lower.endsWith(".png") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".webp") ||
                lower.endsWith(".gif") || lower.endsWith(".bmp")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect image URI (${e.javaClass.simpleName})")
            false
        }
    }
}
