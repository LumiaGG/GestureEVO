package io.github.lumiagg.gestureevo.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.lumiagg.gestureevo.R
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class GalleryImageManager {
    companion object {
        private const val TAG = "GalleryImageManager"
        internal const val KEY_IMAGE_URI = "uri"

        fun saveImageToGalleryWithAutoDelete(context: Context, bitmap: Bitmap, deleteAfterMinutes: Long): Uri? =
            saveImage(context, deleteAfterMinutes) { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) {
                    throw IOException("JPEG encoding failed")
                }
            }

        fun saveUriImageToGallery(context: Context, sourceUri: Uri, deleteAfterMinutes: Long = 0): Boolean =
            saveImage(context, deleteAfterMinutes) { output ->
                val input = context.contentResolver.openInputStream(sourceUri)
                    ?: throw IOException("Unable to open source image")
                input.use { it.copyTo(output) }
            } != null

        private fun saveImage(context: Context, deleteAfterMinutes: Long, write: (OutputStream) -> Unit): Uri? {
            val resolver = context.contentResolver
            val filename = if (deleteAfterMinutes > 0) {
                "GestureEVO_${System.currentTimeMillis()}_deleteAfterMinutes_${deleteAfterMinutes}.jpg"
            } else {
                "GestureEVO_${System.currentTimeMillis()}.jpg"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = try {
                writeCreatedResource(
                    create = { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) },
                    write = { destination ->
                        val output = resolver.openOutputStream(destination)
                            ?: throw IOException("Unable to open destination image")
                        output.use(write)
                    },
                    publish = { destination ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                            if (resolver.update(destination, published, null, null) <= 0) {
                                throw IOException("Unable to publish saved image")
                            }
                        }
                    },
                    rollback = { destination -> resolver.delete(destination, null, null) },
                )
            } catch (failure: Exception) {
                Log.e(TAG, "Unable to save gallery image", failure)
                null
            } ?: return null

            if (deleteAfterMinutes > 0) {
                try {
                    scheduleImageDeletion(context, uri, deleteAfterMinutes)
                } catch (failure: Exception) {
                    // Scheduling is part of the requested operation: do not report success without it.
                    try {
                        resolver.delete(uri, null, null)
                    } catch (cleanupFailure: Exception) {
                        failure.addSuppressed(cleanupFailure)
                    }
                    Log.e(TAG, "Unable to schedule image deletion", failure)
                    return null
                }
            }
            val message = if (deleteAfterMinutes > 0) {
                context.resources.getQuantityString(R.plurals.gallery_saved_with_auto_delete, deleteAfterMinutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), deleteAfterMinutes)
            } else {
                context.getString(R.string.gallery_saved)
            }
            ToastHelper.showInUIThread(context, message)
            return uri
        }

        fun openImageInGallery(context: Context, imageUri: Uri, targetPkg: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(imageUri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    targetPkg.takeIf { it.isNotEmpty() }?.let { `package` = it }
                }
                context.startActivity(intent)
            } catch (failure: Exception) {
                Log.w(TAG, "Unable to open gallery image", failure)
                ToastHelper.showInUIThread(context, context.getString(R.string.gallery_open_failed))
            }
        }

        private fun scheduleImageDeletion(context: Context, uri: Uri, deleteAfterMinutes: Long) {
            val request = OneTimeWorkRequestBuilder<DeleteImageWorker>()
                .setInputData(workDataOf(KEY_IMAGE_URI to uri.toString()))
                .setInitialDelay(deleteAfterMinutes, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

class DeleteImageWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val uri = inputData.getString(GalleryImageManager.KEY_IMAGE_URI)?.toUri() ?: return Result.failure()
        return try {
            if (applicationContext.contentResolver.delete(uri, null, null) > 0) Result.success() else Result.failure()
        } catch (failure: Exception) {
            Log.e("DeleteImageWorker", "Unable to delete gallery image", failure)
            Result.failure()
        }
    }
}
