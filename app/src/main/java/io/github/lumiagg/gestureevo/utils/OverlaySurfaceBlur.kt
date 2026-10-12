package io.github.lumiagg.gestureevo.utils

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import android.view.SurfaceControl
import android.view.View
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/**
 * OriginOS ignores blur-behind on an accessibility overlay unless an activity
 * window is around. Setting the overlay surface's own background blur still
 * works, but [ViewRootImpl.getSurfaceControl] and
 * [SurfaceControl.Transaction.setBackgroundBlurRadius] are blocked at
 * targetSdk 36 until the hidden-API exemption is installed.
 */
@SuppressLint("BlockedPrivateApi")
internal object OverlaySurfaceBlur {
    private const val TAG = "OverlaySurfaceBlur"

    private var exemptionsInstalled = false
    private var blocked = false
    private var readyLogged = false
    private var transaction: SurfaceControl.Transaction? = null
    private var setRadiusMethod: Method? = null
    private var getViewRootImplMethod: Method? = null
    private var getSurfaceControlMethod: Method? = null

    @SuppressLint("BlockedPrivateApi")
    fun apply(view: View, radius: Int): Boolean {
        if (blocked || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!view.isAttachedToWindow) return false
        installExemptions()
        return try {
            val surface = surfaceOf(view) ?: return false
            if (!surface.isValid) return false
            val tx = transaction ?: SurfaceControl.Transaction().also { transaction = it }
            val setRadius = setRadiusMethod ?: tx.javaClass.getDeclaredMethod(
                "setBackgroundBlurRadius",
                SurfaceControl::class.java,
                Int::class.javaPrimitiveType
            ).also {
                it.isAccessible = true
                setRadiusMethod = it
            }
            setRadius.invoke(tx, surface, radius.coerceAtLeast(0))
            tx.apply()
            if (!readyLogged) {
                readyLogged = true
                Log.d(TAG, "overlay surface blur ready")
            }
            true
        } catch (error: ReflectiveOperationException) {
            markBlocked(error)
            false
        } catch (error: SecurityException) {
            markBlocked(error)
            false
        } catch (error: RuntimeException) {
            Log.d(TAG, "overlay surface blur apply failed: $error")
            false
        }
    }

    private fun installExemptions() {
        if (exemptionsInstalled || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val granted = runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/ViewRootImpl",
                "Landroid/view/SurfaceControl",
            )
        }.getOrDefault(false)
        if (!granted) {
            markBlocked(IllegalStateException("hidden api exemption was not granted"))
            return
        }
        exemptionsInstalled = true
    }

    private fun markBlocked(error: Exception) {
        blocked = true
        transaction = null
        Log.w(TAG, "overlay surface blur is unavailable", error)
    }

    // The caller catches reflection failures and disables this optional blur backend.
    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun surfaceOf(view: View): SurfaceControl? {
        val getRoot = getViewRootImplMethod ?: View::class.java.getDeclaredMethod("getViewRootImpl")
            .also {
                it.isAccessible = true
                getViewRootImplMethod = it
            }
        val root = getRoot.invoke(view) ?: return null
        val getSurface = getSurfaceControlMethod
            ?: root.javaClass.getDeclaredMethod("getSurfaceControl").also {
                it.isAccessible = true
                getSurfaceControlMethod = it
            }
        return getSurface.invoke(root) as? SurfaceControl
    }
}
