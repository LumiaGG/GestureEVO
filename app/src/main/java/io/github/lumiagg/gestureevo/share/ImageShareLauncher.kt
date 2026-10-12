package io.github.lumiagg.gestureevo.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.Rom.RomFreeFormCode
import io.github.lumiagg.gestureevo.utils.Rom.getFreeFormCode
import java.io.File
import java.io.FileOutputStream

class ImageShareLauncher(
    private val context: Context
) {
    enum class LaunchResult {
        SUCCESS,
        TARGET_NOT_FOUND,
        LAUNCH_FAILED
    }

    data class Payload(
        val intent: Intent,
        val contentUris: List<Uri>
    )

    fun shareImage(
        bitmap: Bitmap,
        targetPackage: String?,
        targetActivity: String? = null,
        forceChooser: Boolean
    ): Boolean {
        val payload = buildPayload(
            bitmap = bitmap,
            targetPackage = if (forceChooser) null else targetPackage,
            targetActivity = if (forceChooser) null else targetActivity
        ) ?: return false
        val intent = payload.intent

        val packageManager = context.packageManager
        if (!targetPackage.isNullOrEmpty() && !forceChooser) {
            payload.contentUris.forEach { uri ->
                context.grantUriPermission(
                    targetPackage,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (intent.resolveActivity(packageManager) != null) {
                context.startActivity(intent)
                return true
            } else {
                if (!targetActivity.isNullOrEmpty()) {
                    intent.component = null
                }
                intent.setPackage(null)
            }
        }

        return launchViaChooser(payload)
    }

    fun buildPayload(
        bitmap: Bitmap,
        targetPackage: String?,
        targetActivity: String? = null
    ): Payload? {
        return try {
            val contentUri = saveBitmapToCacheAndGetUri(bitmap)
            buildPayload(
                uris = listOf(contentUri),
                targetPackage = targetPackage,
                targetActivity = targetActivity
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun buildPayload(
        uris: List<Uri>,
        targetPackage: String?,
        targetActivity: String? = null
    ): Payload? {
        val distinctUris = uris.distinct()
        if (distinctUris.isEmpty()) return null
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                action = if (distinctUris.size > 1) {
                    Intent.ACTION_SEND_MULTIPLE
                } else {
                    Intent.ACTION_SEND
                }
                type = "image/*"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (!targetPackage.isNullOrEmpty()) {
                    if (!targetActivity.isNullOrEmpty()) {
                        setClassName(targetPackage, targetActivity)
                    } else {
                        setPackage(targetPackage)
                    }
                }
                if (distinctUris.size > 1) {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(distinctUris))
                } else {
                    putExtra(Intent.EXTRA_STREAM, distinctUris.first())
                }
                clipData = buildClipData(distinctUris)
            }

            if (!targetPackage.isNullOrEmpty() && intent.resolveActivity(context.packageManager) == null) {
                intent.component = null
                intent.setPackage(targetPackage)
                if (intent.resolveActivity(context.packageManager) == null) {
                    return null
                }
            }

            Payload(intent = intent, contentUris = distinctUris)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun launchViaChooser(
        payload: Payload,
        chooserTitle: String? = null
    ): Boolean {
        return try {
            val intent = payload.intent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = clipData ?: buildClipData(payload.contentUris)
            }

            val targetPackages = LinkedHashSet<String>().apply {
                intent.`package`?.let(::add)
                intent.component?.packageName?.let(::add)
            }
            targetPackages.forEach { pkg ->
                payload.contentUris.forEach { uri ->
                    context.grantUriPermission(
                        pkg,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }

            context.startActivity(
                Intent.createChooser(
                    intent,
                    chooserTitle ?: context.getString(R.string.inspire_share_image_title)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun launchInFreeform(
        activity: AppCompatActivity,
        payload: Payload,
        targetPackage: String,
        triggerSide: String? = null
    ): Boolean {
        return try {
            val intent = payload.intent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            payload.contentUris.forEach { uri ->
                context.grantUriPermission(
                    targetPackage,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (getFreeFormCode(context) == RomFreeFormCode.NUBIA.code) {
                ActionProxy.startNubiaFreeformActivity(activity, intent)
            } else {
                activity.startActivity(
                    intent,
                    ActionProxy.buildFreeformActivityOptions(context, triggerSide).toBundle()
                )
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun launchForSearchEngine(
        activity: AppCompatActivity,
        bitmap: Bitmap,
        targetPackage: String,
        targetActivity: String?,
        freeform: Boolean,
        triggerSide: String? = null
    ): LaunchResult {
        if (!freeform) {
            return if (
                shareImage(
                    bitmap = bitmap,
                    targetPackage = targetPackage,
                    targetActivity = targetActivity,
                    forceChooser = false
                )
            ) {
                LaunchResult.SUCCESS
            } else {
                LaunchResult.LAUNCH_FAILED
            }
        }

        val payload = buildPayload(
            bitmap = bitmap,
            targetPackage = targetPackage,
            targetActivity = targetActivity
        ) ?: return LaunchResult.TARGET_NOT_FOUND

        val launched = if (getFreeFormCode(context) == RomFreeFormCode.VIVO.code) {
            launchViaChooser(payload)
        } else {
            launchInFreeform(
                activity = activity,
                payload = payload,
                targetPackage = targetPackage,
                triggerSide = triggerSide
            )
        }

        return if (launched) LaunchResult.SUCCESS else LaunchResult.LAUNCH_FAILED
    }

    suspend fun launchForSearchEngine(
        activity: AppCompatActivity,
        imageUris: List<Uri>,
        targetPackage: String,
        targetActivity: String?,
        freeform: Boolean,
        triggerSide: String? = null
    ): LaunchResult {
        val payload = buildPayload(
            uris = imageUris,
            targetPackage = targetPackage,
            targetActivity = targetActivity
        ) ?: return LaunchResult.TARGET_NOT_FOUND

        if (!freeform) {
            return if (
                startActivityWithFallback(
                    activity = activity,
                    payload = payload,
                    targetPackage = targetPackage,
                    chooserTitle = context.getString(R.string.inspire_share_image_title)
                )
            ) {
                LaunchResult.SUCCESS
            } else {
                LaunchResult.LAUNCH_FAILED
            }
        }

        val launched = if (getFreeFormCode(context) == RomFreeFormCode.VIVO.code) {
            launchViaChooser(payload)
        } else {
            launchInFreeform(
                activity = activity,
                payload = payload,
                targetPackage = targetPackage,
                triggerSide = triggerSide
            )
        }

        return if (launched) LaunchResult.SUCCESS else LaunchResult.LAUNCH_FAILED
    }

    private fun buildClipData(uris: List<Uri>): ClipData? {
        val first = uris.firstOrNull() ?: return null
        return ClipData.newUri(context.contentResolver, "shared_image", first).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
    }

    private fun startActivityWithFallback(
        activity: AppCompatActivity,
        payload: Payload,
        targetPackage: String,
        chooserTitle: String? = null
    ): Boolean {
        val packageOnlyIntent = payload.intent.cloneFilter().apply {
            action = payload.intent.action
            type = payload.intent.type
            flags = payload.intent.flags
            `package` = targetPackage
            component = null
            clipData = payload.intent.clipData ?: buildClipData(payload.contentUris)
            if (payload.contentUris.size > 1) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(payload.contentUris))
            } else {
                putExtra(Intent.EXTRA_STREAM, payload.contentUris.firstOrNull())
            }
        }

        payload.contentUris.forEach { uri ->
            context.grantUriPermission(
                targetPackage,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        return try {
            activity.startActivity(payload.intent)
            true
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            try {
                if (packageOnlyIntent.resolveActivity(context.packageManager) != null) {
                    activity.startActivity(packageOnlyIntent)
                    true
                } else {
                    launchViaChooser(
                        Payload(packageOnlyIntent, payload.contentUris),
                        chooserTitle = chooserTitle
                    )
                }
            } catch (inner: Exception) {
                inner.printStackTrace()
                launchViaChooser(
                    Payload(packageOnlyIntent, payload.contentUris),
                    chooserTitle = chooserTitle
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun saveBitmapToCacheAndGetUri(bitmap: Bitmap): Uri {
        val cachePath = File(context.cacheDir, "images").apply { mkdirs() }
        cleanupOldCacheImages(cachePath)
        val imageFile = File(cachePath, "shared_image_${System.currentTimeMillis()}.jpg")

        FileOutputStream(imageFile).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
    }

    private fun cleanupOldCacheImages(cacheDir: File) {
        try {
            val files = cacheDir.listFiles() ?: return
            val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
            files.forEach { file ->
                if (file.isFile && file.name.startsWith("shared_image_") && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }
}
