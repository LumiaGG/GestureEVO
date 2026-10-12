package io.github.lumiagg.gestureevo.gesture.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.core.view.setMargins
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import kotlin.math.min
import kotlin.math.sqrt

class RoundImageTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var _imageFilterView: ImageFilterView? = null
    private var _textView: TextView? = null
    private var _badgeView: ImageFilterView? = null
    val imageFilterView: ImageFilterView
        get() = ensureImageFilterView()
    val textView: TextView
        get() = ensureTextView()
    private var cornerRadius = 0f

    private val maxTextSize = 24f
    private val minTextSize = 8f
    private val badgeSizeRatio = 0.3f
    private val badgeMarginRatio = 0.02f
    private val badgePaddingRatio = 0.05f
    private val badgeCornerRadiusRatio = 0.09f

    // Ratio of text display area relative to circle bounds (0.0-1.0)
    private val textAreaRatio = 0.8f

    private var imagePadding = 0f

    var applyGlobalImageRoundPercent = true

    private var isUsingBackplate = false
    private var transparentBackground = false
    private var usesSelectionBackground = false
    private var elevationBeforeTransparentBackground: Float? = null

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (_textView?.isVisible == true) {
            adjustTextSizeToFit()
        }
        _badgeView?.let {
            updateBadgeLayout(it)
            updateBadgeAppearance(it)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        refreshTheme()
    }

    fun refreshTheme() {
        if (isUsingBackplate) {
            setImageWithBackplate(imageFilterView.drawable)
        }
        _textView?.takeIf { it.isVisible }?.setTextColor(DynamicColorUtils.onPrimaryContainerColor)
        _badgeView?.let(::updateBadgeAppearance)
        if (usesSelectionBackground) {
            updateSelectionBackground(isSelected)
        }
    }

    private fun ensureTextView(): TextView {
        _textView?.let { return it }
        ensureImageFilterView()
        return TextView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(3f.dp2px.toInt())
                gravity = Gravity.CENTER
            }
            gravity = Gravity.CENTER
            visibility = GONE
            maxLines = 2
            includeFontPadding = false
        }.also {
            _textView = it
            addView(it)
        }
    }

    private fun ensureImageFilterView(): ImageFilterView {
        _imageFilterView?.let { return it }
        return ImageFilterView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
            )
        }.also {
            _imageFilterView = it
            addView(it, 0)
        }
    }

    private fun ensureBadgeView(): ImageFilterView {
        _badgeView?.let { return it }
        ensureImageFilterView()
        return ImageFilterView(context).apply {
            layoutParams = LayoutParams(0, 0).apply {
                gravity = Gravity.END or Gravity.BOTTOM
            }
            visibility = GONE
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            updateBadgeLayout(this)
            updateBadgeAppearance(this)
        }.also {
            _badgeView = it
            addView(it)
        }
    }

    private fun updateBadgeLayout(badgeView: ImageFilterView) {
        val minSide = min(width, height).takeIf { it > 0 } ?: 0
        val badgeSize = if (minSide > 0) {
            (minSide * badgeSizeRatio).toInt().coerceAtLeast(12.dp2px.toInt())
        } else {
            14.dp2px.toInt()
        }
        val badgeMargin = if (minSide > 0) {
            (minSide * badgeMarginRatio).toInt().coerceAtLeast(0)
        } else {
            0
        }
        val badgePadding = if (minSide > 0) {
            (minSide * badgePaddingRatio).toInt().coerceAtLeast(2.dp2px.toInt())
        } else {
            2.5f.dp2px.toInt()
        }

        val params = (badgeView.layoutParams as? LayoutParams) ?: LayoutParams(0, 0)
        params.width = badgeSize
        params.height = badgeSize
        params.gravity = Gravity.END or Gravity.BOTTOM
        params.setMargins(badgeMargin, badgeMargin, badgeMargin, badgeMargin)
        badgeView.layoutParams = params
        badgeView.setPadding(badgePadding, badgePadding, badgePadding, badgePadding)
        badgeView.elevation = 0f
    }

    private fun updateBadgeAppearance(badgeView: ImageFilterView) {
        val minSide = min(width, height).takeIf { it > 0 } ?: 0
        val badgeBackground = (badgeView.background as? GradientDrawable) ?: GradientDrawable().also {
            badgeView.background = it
        }
        badgeBackground.shape = GradientDrawable.RECTANGLE
        badgeBackground.cornerRadius = if (minSide > 0) {
            (minSide * badgeCornerRadiusRatio).coerceAtLeast(4.dp2px)
        } else {
            4.dp2px
        }
        badgeBackground.setColor(DynamicColorUtils.secondaryContainerColor)
        badgeBackground.setStroke(1.dp2px.toInt(), DynamicColorUtils.surfaceContainerHighestColor)
        badgeView.imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
        if (badgeView.drawable is ColorDrawable) {
            badgeView.setImageDrawable(null)
        }
    }

    private fun calculateInscribedSquareSize(): Float {
        var diameter = min(width, height).toFloat()
        diameter -= imagePadding
        // Inscribed square side length: diameter * sqrt(2)/2
        return diameter * textAreaRatio
    }

    private fun adjustTextSizeToFit() {
        val textView = _textView ?: return
        val text = textView.text?.toString() ?: return

        // Available square area size inside circle
        val squareSize = calculateInscribedSquareSize()
        if (squareSize <= 0) return

        val paint = TextPaint(textView.paint)

        var low = minTextSize
        var high = maxTextSize
        val threshold = 0.5f

        while (high - low > threshold) {
            val textSize = (low + high) / 2
            paint.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                textSize,
                resources.displayMetrics
            )

            val lineSpacingExtra = if (text.contains("\n")) -paint.descent() * 0.3f else 0f
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, squareSize.toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(lineSpacingExtra, 1f)
                .setIncludePad(false)
                .setEllipsize(textView.ellipsize)
                .setMaxLines(textView.maxLines)
                .build()

            // Verify whether text fits within inscribed square area
            if (layout.lineCount <= textView.maxLines && layout.height <= squareSize) {
                low = textSize
            } else {
                high = textSize
            }
        }

        // Use calculated optimum size
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, low)

        // Adjust line spacing for compact presentation
        if (text.contains("\n")) {
            textView.setLineSpacing(-paint.descent() * 0.3f, 1f)
        } else {
            textView.setLineSpacing(0f, 1f)
        }
    }

    private fun processText(text: CharSequence): String {
        val raw = text.toString().trim()
        if (raw.isEmpty()) return raw

        val hasCjk = containsCjk(raw)
        if (!hasCjk) {
            return raw.replace("\\s+".toRegex(), " ")
        }

        val chars = raw.replace("\\s+".toRegex(), "")
        return when {
            chars.length <= 2 -> chars
            chars.length <= 4 -> {
                if (chars.length == 3) {
                    // For three characters: two on first line, one centered on second line
                    chars.take(2) + "\n" + chars[2]
                } else {
                    // For four characters: two characters per line
                    chars.take(2) + "\n" + chars.substring(2, 4)
                }
            }

            else -> chars.take(2) + "\n" + chars.substring(2, 4)
        }
    }

    /**
     * Sets corner radius.
     */
    fun setCornerRadius(radius: Float) {
        this.cornerRadius = radius
        invalidateOutline()
    }

    fun setImage(source: Any?) {
        isUsingBackplate = false
        when (source) {
            is Bitmap -> imageFilterView.setImageBitmap(source)
            is Drawable -> imageFilterView.setImageDrawable(source)
            is Int -> imageFilterView.setImageResource(source)
            is Uri -> imageFilterView.setImageURI(source)
            null -> imageFilterView.setImageURI(null)
            else -> throw IllegalArgumentException("Unsupported source type")
        }
        imageFilterView.background = null
        imageFilterView.setPadding(0, 0, 0, 0)
        clearTint()
        imageFilterView.visibility = VISIBLE
        _textView?.visibility = GONE

        if (applyGlobalImageRoundPercent) {
            setImageRoundPercent(IconHelper.iconImageRoundPercent)
        }
    }

    fun setImageWithBackplate(
        bitmap: Bitmap,
        tintColor: Int? = null,
        backplateColor: Int = DynamicColorUtils.surfaceContainerHighColor
    ) {
        setImageWithBackplate(bitmap.toDrawable(resources), tintColor, backplateColor)
    }

    fun setImageWithBackplate(
        resId: Int,
        tintColor: Int? = DynamicColorUtils.primaryColor,
        backplateColor: Int = DynamicColorUtils.surfaceContainerHighColor,
        iconInset: Int? = null,
    ) {
        val drawable = ContextCompat.getDrawable(context, resId)
        if (drawable == null) {
            setImage(resId)
            return
        }
        setImageWithBackplate(drawable, tintColor, backplateColor, iconInset)
    }

    fun setImageWithBackplate(
        drawable: Drawable,
        tintColor: Int? = DynamicColorUtils.primaryColor,
        backplateColor: Int = DynamicColorUtils.surfaceContainerHighColor,
        iconInset: Int? = null,
    ) {
        isUsingBackplate = true
        val icon = drawable.mutate()
        val backplate = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(backplateColor)
        }
        val backplateInset = 0
        val resolvedIconInset = iconInset ?: (8.dp2px - imagePadding * 0.3f).toInt()
        imageFilterView.background = InsetDrawable(
            backplate,
            backplateInset,
            backplateInset,
            backplateInset,
            backplateInset
        )
        imageFilterView.setPadding(
            resolvedIconInset,
            resolvedIconInset,
            resolvedIconInset,
            resolvedIconInset
        )
        imageFilterView.setImageDrawable(icon)
        if (tintColor != null) {
            imageFilterView.imageTintMode = android.graphics.PorterDuff.Mode.SRC_IN
            imageFilterView.imageTintList = ColorStateList.valueOf(tintColor)
        } else {
            imageFilterView.imageTintList = null
            imageFilterView.clearColorFilter()
        }
        imageFilterView.visibility = VISIBLE
        _textView?.visibility = GONE

        if (applyGlobalImageRoundPercent) {
            setImageRoundPercent(IconHelper.iconImageRoundPercent)
        }
    }

    fun copyImagePresentationFrom(source: RoundImageTextView) {
        val sourceImage = source.imageFilterView
        val sourceDrawable = sourceImage.drawable
        val drawableCopy = sourceDrawable?.constantState?.newDrawable()?.mutate() ?: sourceDrawable
        setImage(drawableCopy)

        val sourceBackground = sourceImage.background
        if (sourceBackground != null) {
            imageFilterView.background =
                sourceBackground.constantState?.newDrawable()?.mutate() ?: sourceBackground
            imageFilterView.setPadding(
                sourceImage.paddingLeft,
                sourceImage.paddingTop,
                sourceImage.paddingRight,
                sourceImage.paddingBottom
            )
        } else {
            imageFilterView.background = null
            imageFilterView.setPadding(0, 0, 0, 0)
        }

        imageFilterView.imageTintList = sourceImage.imageTintList
        imageFilterView.imageTintMode = sourceImage.imageTintMode
    }

    fun setImagePadding(padding: Int) {
        imagePadding = padding.toFloat()
        val params = imageFilterView.layoutParams as MarginLayoutParams
        params.setMargins(padding, padding, padding, padding)
        imageFilterView.layoutParams = params
    }

    /**
     * Sets corner mask.
     */
    fun setImageRoundPercent(roundPercent: Float) {
        imageFilterView.setRoundPercent(roundPercent)
    }

    /**
     * Sets text content.
     */
    // LineBreaker integer constants are inlined and match the API 23 Layout constants.
    @SuppressLint("InlinedApi")
    fun setText(text: CharSequence) {
        val textView = ensureTextView()
        val processed = processText(text)
        val hasCjk = containsCjk(processed)
        if (hasCjk) {
            textView.ellipsize = null
            textView.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
            textView.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        } else {
            textView.ellipsize = TextUtils.TruncateAt.END
            textView.breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
            textView.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
        }
        textView.text = processed
        textView.visibility = VISIBLE
        textView.setTextColor(DynamicColorUtils.onPrimaryContainerColor)
        imageFilterView.visibility = GONE
        post { adjustTextSizeToFit() }
    }

    fun setBadge(source: Any?) {
        val badgeView = if (source == null) _badgeView else ensureBadgeView()
        when (source) {
            is Bitmap -> badgeView?.setImageBitmap(source)
            is Drawable -> badgeView?.setImageDrawable(source)
            is Int -> badgeView?.setImageResource(source)
            is Uri -> badgeView?.setImageURI(source)
            null -> {
                badgeView?.setImageDrawable(null)
                badgeView?.visibility = GONE
                return
            }

            else -> throw IllegalArgumentException("Unsupported badge source type")
        }
        badgeView?.visibility = VISIBLE
    }

    /**
     * Sets color filter.
     */
    fun setFilterSaturation(saturation: Float) {
        imageFilterView.saturation = saturation
    }

    fun setFilterContrast(contrast: Float) {
        imageFilterView.contrast = contrast
    }

    fun setFilterBrightness(brightness: Float) {
        imageFilterView.brightness = brightness
    }

    fun setTint() {
        imageFilterView.imageTintList =
            ColorStateList.valueOf(DynamicColorUtils.primaryColor)
    }

    fun clearTint() {
        imageFilterView.imageTintList = null
    }

    fun setTransparentBackground(enabled: Boolean) {
        usesSelectionBackground = true
        if (enabled != transparentBackground) {
            if (enabled) {
                elevationBeforeTransparentBackground = elevation
                elevation = 0f
            } else {
                elevationBeforeTransparentBackground?.let { elevation = it }
                elevationBeforeTransparentBackground = null
            }
        }
        transparentBackground = enabled
        updateSelectionBackground(isSelected)
    }

    fun setIsSelected(value: Boolean) {
        usesSelectionBackground = true
        isSelected = value
        updateSelectionBackground(value)
    }

    private fun updateSelectionBackground(selected: Boolean) {
        setBackgroundColor(
            when {
                transparentBackground -> Color.TRANSPARENT
                selected -> DynamicColorUtils.secondaryContainerColor
                else -> DynamicColorUtils.inverseOnSurfaceColor
            }
        )
    }

    private fun containsCjk(text: CharSequence): Boolean {
        for (char in text) {
            val block = Character.UnicodeBlock.of(char) ?: continue
            when (block.toString()) {
                "CJK_UNIFIED_IDEOGRAPHS",
                "CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A",
                "CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B",
                "CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C",
                "CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D",
                "CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E",
                "CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F",
                "CJK_COMPATIBILITY_IDEOGRAPHS",
                "CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT",
                "HANGUL_SYLLABLES",
                "HANGUL_JAMO",
                "HANGUL_COMPATIBILITY_JAMO",
                "HIRAGANA",
                "KATAKANA",
                "KATAKANA_PHONETIC_EXTENSIONS" -> return true
                else -> Unit
            }
        }
        return false
    }
}
