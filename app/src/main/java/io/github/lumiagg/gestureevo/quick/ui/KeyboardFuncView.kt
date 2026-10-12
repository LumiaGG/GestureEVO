package io.github.lumiagg.gestureevo.quick.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R

enum class FuncEnum {
    BACKSPACE,
    ENTER,
    SETTINGS,
    None
}

class KeyboardFuncView : MaterialCardView {
    private var topLetterTextView: MaterialTextView? = null
    private var iconView: ShapeableImageView? = null
    var func = FuncEnum.BACKSPACE
        private set

    constructor(context: Context) : super(context) {
        initialize(null)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        initialize(attrs)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        initialize(attrs)
    }

    private fun initialize(attrs: AttributeSet?) {
        val layoutInflater = LayoutInflater.from(context)
        layoutInflater.inflate(R.layout.quick_keyboard_func, this, true)

        // Extract custom style attributes
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.KeyboardFuncView)
            func = FuncEnum.valueOf(
                typedArray.getString(R.styleable.KeyboardFuncView_func) ?: FuncEnum.BACKSPACE.name
            )
            val topLetterText = typedArray.getString(R.styleable.KeyboardFuncView_funcTopLetterText)
            val icon = typedArray.getResourceId(
                R.styleable.KeyboardFuncView_icon,
                R.drawable.baseline_check_24
            )
            typedArray.recycle()


            topLetterTextView = findViewById(R.id.topLetter)
            iconView = findViewById(R.id.icon)

            if (topLetterText.isNullOrEmpty()) {
                topLetterTextView?.visibility = GONE
            } else {
                topLetterTextView?.text = topLetterText
            }

            iconView?.setImageResource(icon)
        }
    }

    fun getTopText(): String {
        return topLetterTextView?.text.toString()
    }

    fun setIcon(res: Int) {
        iconView?.setImageResource(res)
    }
}
