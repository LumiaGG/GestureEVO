package io.github.lumiagg.gestureevo.drop

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import android.view.DragAndDropPermissions
import android.view.DragEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Service overlays have no Activity token, so URI grants must be taken transiently. */
internal object DropUriPermissions {
    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
    fun take(event: DragEvent): DragAndDropPermissions? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/view/DragAndDropPermissions;")
        }
        val type = DragAndDropPermissions::class.java
        val permissions = type.getDeclaredMethod("obtain", DragEvent::class.java)
            .invoke(null, event) as? DragAndDropPermissions
        if (permissions != null && type.getDeclaredMethod("takeTransient").invoke(permissions) != true) {
            runCatching { permissions.release() }
            null
        } else {
            permissions
        }
    } catch (error: Exception) {
        Log.w("ExternalDrop", "Cannot acquire transient drop URI permission", error)
        null
    }
}
