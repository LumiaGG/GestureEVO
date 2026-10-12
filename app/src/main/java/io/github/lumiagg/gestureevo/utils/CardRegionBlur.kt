package io.github.lumiagg.gestureevo.utils

import android.annotation.SuppressLint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.google.android.material.card.MaterialCardView
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * Blurs one card with the system [BackgroundBlurDrawable]. The drawable is
 * drawn by a child that does not affect the card's measured size.
 */
internal object CardRegionBlur {
    private const val TAG = "CardRegionBlur"
    const val RADIUS = 40

    private val cards = IdentityHashMap<View, Boolean>()
    private val cornerFallbacks = IdentityHashMap<View, Float>()
    private val overlappingRendering = IdentityHashMap<View, Boolean>()
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
            if (cards.containsKey(card)) remove(card)
            return
        }
        val radius = cornerRadius.coerceAtLeast(0f)
        val unchanged = cards[card] == true && cornerFallbacks[card] == radius
        cards[card] = true
        cornerFallbacks[card] = radius
        val group = card as? ViewGroup ?: return
        // BackgroundBlurDrawable uses SRC to open a hole in the window buffer.
        // An alpha saveLayer confines that hole to the offscreen card layer, so
        // blur only becomes visible when the card's fade finishes.
        if (!overlappingRendering.containsKey(card)) {
            overlappingRendering[card] = card.hasOverlappingRendering()
            card.forceHasOverlappingRendering(false)
        }
        val existing = findHost(group)
        if (unchanged && existing?.blurDrawable != null && existing.isAttachedToWindow) return
        val host = existing ?: ensureHost(group) ?: return
        if (host.isAttachedToWindow) bindDrawable(host)
    }

    private fun remove(card: View) {
        cards.remove(card)
        cornerFallbacks.remove(card)
        overlappingRendering.remove(card)?.let { card.forceHasOverlappingRendering(it) }
        removeHost(card)
    }

    private fun ensureHost(card: ViewGroup): BlurHost? {
        findHost(card)?.let { return it }
        val host = BlurHost(card.context)
        if (card.isInLayout) {
            card.post {
                if (!cards.containsKey(card) || findHost(card) != null) return@post
                card.addView(host, 0, ViewGroup.LayoutParams(0, 0))
            }
            return null
        }
        card.addView(host, 0, ViewGroup.LayoutParams(0, 0))
        return host
    }

    private fun findHost(card: ViewGroup): BlurHost? {
        for (index in 0 until card.childCount) {
            val child = card.getChildAt(index)
            if (child is BlurHost) return child
        }
        return null
    }

    private fun removeHost(card: View) {
        val group = card as? ViewGroup ?: return
        val host = findHost(group) ?: return
        group.removeView(host)
    }

    private fun bindDrawable(host: BlurHost) {
        if (drawableBlocked) return
        val card = host.parent as? View ?: return
        if (!cards.containsKey(card)) return
        // Register the system blur aggregator on attach, before the first
        // pre-draw dispatch. Bounds and relative corners are updated after layout.
        if (!host.isHardwareAccelerated) {
            markDrawableBlocked(card)
            return
        }
        try {
            if (!installExemptions()) {
                markDrawableBlocked(card)
                return
            }
            val drawable = host.blurDrawable ?: createDrawable(host)?.also { host.blurDrawable = it }
            if (drawable == null) {
                markDrawableBlocked(card)
                return
            }
            val corners = cornerRadii(card)
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
            markDrawableBlocked(card)
        }
    }

    private fun markDrawableBlocked(card: View) {
        if (drawableBlocked) return
        drawableBlocked = true
        val snapshot = cards.keys.toList()
        card.post {
            for (tracked in snapshot) remove(tracked)
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

    private fun cornerRadii(card: View): FloatArray {
        val materialCard = card as? MaterialCardView
            ?: return FloatArray(4) { cornerFallbacks[card] ?: 0f }
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
                ?: cornerFallbacks[card]
                ?: 0f
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

    private class BlurHost(context: android.content.Context) : View(context) {
        var blurDrawable: Drawable? = null
        var appliedCorners: FloatArray? = null
        var appliedColor: Int = Int.MIN_VALUE
        var appliedAlpha: Int = -1
        private var restoringSize = false
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
            viewTreeObserver.addOnPreDrawListener(preDrawListener)
            placeToParent()
            bindDrawable(this)
        }

        override fun onDetachedFromWindow() {
            val observer = viewTreeObserver
            if (observer.isAlive) observer.removeOnPreDrawListener(preDrawListener)
            appliedCorners = null
            appliedColor = Int.MIN_VALUE
            appliedAlpha = -1
            background = null
            blurDrawable = null
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
