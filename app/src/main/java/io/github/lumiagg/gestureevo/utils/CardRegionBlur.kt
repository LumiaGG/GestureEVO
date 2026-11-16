package io.github.lumiagg.gestureevo.utils

import android.annotation.SuppressLint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Blurs one card with the system [BackgroundBlurDrawable]. The drawable is
 * drawn by a child that does not affect the card's measured size.
 */
internal object CardRegionBlur {
    private const val TAG = "CardRegionBlur"
    const val RADIUS = 40

    // Cards own their hosts. This weak index only lets a reflection failure
    // disable other configured hosts without keeping their windows/contexts alive.
    private val hosts = Collections.newSetFromMap(WeakHashMap<BlurHost, Boolean>())
    private var exemptionsInstalled = false
    private var drawableBlocked = false
    private var drawableLogged = false
    private var getViewRootImplMethod: Method? = null
    private var createDrawableMethod: Method? = null
    private var setBlurRadiusMethod: Method? = null
    private var setCornerRadiusMethod: Method? = null
    private var setColorMethod: Method? = null

    fun update(card: View, enabled: Boolean, cornerRadius: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !enabled || drawableBlocked) {
            remove(card)
            return
        }
        val group = card as? ViewGroup ?: return
        val radius = cornerRadius.coerceAtLeast(0f)
        val existing = findHost(card)
        val unchanged = existing?.cornerRadius == radius
        val host = existing ?: BlurHost(group, radius).also {
            card.setTag(R.id.cardRegionBlurHost, it)
        }
        host.cornerRadius = radius
        // BackgroundBlurDrawable uses SRC to open a hole in the window buffer.
        // An alpha saveLayer confines that hole to the offscreen card layer, so
        // blur only becomes visible when the card's fade finishes.
        card.forceHasOverlappingRendering(false)
        if (unchanged && host.blurDrawable != null && host.isAttachedToWindow) return
        hosts.add(host)
        if (host.parent == null) host.attachToCard()
        if (host.isAttachedToWindow) bindDrawable(host)
    }

    private fun remove(card: View) {
        val host = findHost(card) ?: return
        card.setTag(R.id.cardRegionBlurHost, null)
        host.release()
    }

    private fun findHost(card: View): BlurHost? = card.getTag(R.id.cardRegionBlurHost) as? BlurHost

    private fun bindDrawable(host: BlurHost) {
        if (drawableBlocked) return
        val card = host.card
        if (!host.isAttachedToWindow || findHost(card) !== host) return
        // Register the system blur aggregator on attach, before the first
        // pre-draw dispatch. Bounds and relative corners are updated after layout.
        if (!host.isHardwareAccelerated) {
            markDrawableBlocked()
            return
        }
        try {
            if (!installExemptions()) {
                markDrawableBlocked()
                return
            }
            val drawable = host.blurDrawable ?: createDrawable(host)?.also { host.blurDrawable = it }
            if (drawable == null) {
                markDrawableBlocked()
                return
            }
            val corners = cornerRadii(card, host.cornerRadius)
            val color = (card as? MaterialCardView)?.cardBackgroundColor?.defaultColor ?: 0
            if (!sameBlur(host, corners, color)) {
                configure(drawable, corners, color)
                host.appliedCorners = corners.copyOf()
                host.appliedColor = color
            }
            if (host.background !== drawable) host.background = drawable
            syncDrawableAlpha(host)
            if (!drawableLogged) {
                drawableLogged = true
                Log.d(TAG, "card blur drawable ready")
            }
        } catch (error: Exception) {
            Log.w(TAG, "card blur drawable failed", error)
            host.blurDrawable = null
            host.background = null
            markDrawableBlocked()
        }
    }

    private fun markDrawableBlocked() {
        if (drawableBlocked) return
        drawableBlocked = true
        val snapshot = hosts.toList()
        // Failure can happen during addView/layout. Clean up on the next main
        // turn even if the failing card is detached before its callbacks run.
        Handler(Looper.getMainLooper()).post {
            for (host in snapshot) {
                if (findHost(host.card) === host) remove(host.card)
            }
        }
    }

    private fun createDrawable(host: View): Drawable? {
        val viewRoot = viewRootOf(host) ?: return null
        val create = createDrawableMethod ?: viewRoot.javaClass.getDeclaredMethod(
            "createBackgroundBlurDrawable",
        ).also {
            it.isAccessible = true
            createDrawableMethod = it
        }
        return create.invoke(viewRoot) as? Drawable
    }

    private fun syncDrawableAlpha(host: BlurHost) {
        val drawable = host.blurDrawable ?: return
        val alpha = (combinedAlpha(host) * 255f).roundToInt().coerceIn(0, 255)
        if (host.appliedAlpha == alpha) return
        host.appliedAlpha = alpha
        drawable.alpha = alpha
    }

    private fun combinedAlpha(view: View): Float {
        var alpha = 1f
        var current: View? = view
        while (current != null) {
            // Some panels are attached while INVISIBLE during configuration.
            // Their blur regions must stay hidden along with their content.
            if (current.visibility != View.VISIBLE) return 0f
            alpha *= current.alpha
            if (alpha <= 0f) return 0f
            current = current.parent as? View
        }
        return alpha.coerceIn(0f, 1f)
    }

    private fun sameBlur(host: BlurHost, corners: FloatArray, color: Int): Boolean {
        val applied = host.appliedCorners ?: return false
        return host.appliedColor == color && applied.contentEquals(corners)
    }

    private fun configure(drawable: Drawable, corners: FloatArray, color: Int) {
        val type = drawable.javaClass
        val setRadius = setBlurRadiusMethod ?: type.getMethod(
            "setBlurRadius",
            Int::class.javaPrimitiveType,
        ).also { setBlurRadiusMethod = it }
        setRadius.invoke(drawable, RADIUS)
        val setCorners = setCornerRadiusMethod ?: type.getMethod(
            "setCornerRadius",
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        ).also { setCornerRadiusMethod = it }
        setCorners.invoke(drawable, corners[0], corners[1], corners[2], corners[3])
        val setColor = setColorMethod ?: type.getMethod(
            "setColor",
            Int::class.javaPrimitiveType,
        ).also { setColorMethod = it }
        setColor.invoke(drawable, color)
    }

    private fun cornerRadii(card: View, fallbackRadius: Float): FloatArray {
        val materialCard = card as? MaterialCardView
            ?: return FloatArray(4) { fallbackRadius }
        val bounds = RectF(0f, 0f, card.width.toFloat(), card.height.toFloat())
        val shape = materialCard.shapeAppearanceModel
        val radii = floatArrayOf(
            shape.topLeftCornerSize.getCornerSize(bounds),
            shape.topRightCornerSize.getCornerSize(bounds),
            shape.bottomLeftCornerSize.getCornerSize(bounds),
            shape.bottomRightCornerSize.getCornerSize(bounds),
        )
        if (radii.all { it <= 0f }) {
            val radius = materialCard.radius.coerceAtLeast(0f).takeIf { it > 0f }
                ?: fallbackRadius
            radii.fill(radius)
        }
        return radii
    }

    private fun installExemptions(): Boolean {
        if (exemptionsInstalled || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return exemptionsInstalled
        }
        val granted = runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/ViewRootImpl",
                "Lcom/android/internal/graphics/drawable/BackgroundBlurDrawable",
            )
        }.getOrDefault(false)
        if (!granted) {
            Log.w(TAG, "hidden api exemption was not granted")
            return false
        }
        exemptionsInstalled = true
        return true
    }

    // The caller catches reflection failures and uses the existing blur fallback.
    @SuppressLint("DiscouragedPrivateApi")
    private fun viewRootOf(view: View): Any? {
        val getRoot = getViewRootImplMethod ?: View::class.java.getDeclaredMethod("getViewRootImpl")
            .also {
                it.isAccessible = true
                getViewRootImplMethod = it
            }
        return getRoot.invoke(view)
    }

    private class BlurHost(val card: ViewGroup, var cornerRadius: Float) : View(card.context) {
        private val originalOverlappingRendering = card.getHasOverlappingRendering()
        private var released = false
        private var preDrawObserver: ViewTreeObserver? = null
        var blurDrawable: Drawable? = null
        var appliedCorners: FloatArray? = null
        var appliedColor: Int = Int.MIN_VALUE
        var appliedAlpha: Int = -1
        private var restoringSize = false
        private val attachRunnable = Runnable {
            if (!released && !drawableBlocked && findHost(card) === this && parent == null) {
                card.addView(this, 0, ViewGroup.LayoutParams(0, 0))
            }
        }
        private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            placeToParent()
            // onSizeChanged may already have restored our size. Binding must
            // not depend on placeToParent reporting a change.
            bindDrawable(this)
            true
        }

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun attachToCard() {
            card.removeCallbacks(attachRunnable)
            if (card.isInLayout) card.post(attachRunnable) else attachRunnable.run()
        }

        fun release() {
            released = true
            card.removeCallbacks(attachRunnable)
            hosts.remove(this)
            clearDrawable()
            card.forceHasOverlappingRendering(originalOverlappingRendering)
            if (parent === card) card.removeView(this)
        }

        private fun clearDrawable() {
            preDrawObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
            preDrawObserver = null
            appliedCorners = null
            appliedColor = Int.MIN_VALUE
            appliedAlpha = -1
            background = null
            blurDrawable = null
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(0, 0)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (restoringSize) return
            val parentView = parent as? View ?: return
            if (parentView.width <= 0 || parentView.height <= 0) return
            if (w == parentView.width && h == parentView.height) return
            restoringSize = true
            layout(0, 0, parentView.width, parentView.height)
            restoringSize = false
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (released || drawableBlocked || findHost(card) !== this) return
            hosts.add(this)
            card.forceHasOverlappingRendering(false)
            preDrawObserver = viewTreeObserver.also { it.addOnPreDrawListener(preDrawListener) }
            placeToParent()
            bindDrawable(this)
        }

        override fun onDetachedFromWindow() {
            card.removeCallbacks(attachRunnable)
            clearDrawable()
            card.forceHasOverlappingRendering(originalOverlappingRendering)
            super.onDetachedFromWindow()
        }

        private fun placeToParent(): Boolean {
            val parentView = parent as? View ?: return false
            if (parentView.width <= 0 || parentView.height <= 0) return false
            if (left == 0 && top == 0 && width == parentView.width && height == parentView.height) {
                return false
            }
            layout(0, 0, parentView.width, parentView.height)
            return true
        }
    }
}
