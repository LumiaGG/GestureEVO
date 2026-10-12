package io.github.lumiagg.gestureevo.quick.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R

class KeyboardLetterView : MaterialCardView {
    private var topLetterTextView: MaterialTextView? = null
    private var leftLetterTextView: MaterialTextView? = null
    private var rightLetterTextView: MaterialTextView? = null

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
        layoutInflater.inflate(R.layout.quick_keyboard_letter, this, true)

        // Extract custom style attributes
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.KeyboardLetterView)
            val topLetterText = typedArray.getString(R.styleable.KeyboardLetterView_topLetterText)
            val leftLetterText = typedArray.getString(R.styleable.KeyboardLetterView_leftLetterText)
            val rightLetterText =
                typedArray.getString(R.styleable.KeyboardLetterView_rightLetterText)
            typedArray.recycle()

            // Apply custom attribute values to child views
            topLetterTextView = findViewById(R.id.topLetter)
            leftLetterTextView = findViewById(R.id.leftLetter)
            rightLetterTextView = findViewById(R.id.rightLetter)

            if (topLetterText.isNullOrEmpty()) {
                topLetterTextView?.visibility = GONE
            } else {
                topLetterTextView?.text = topLetterText
            }

            if (leftLetterText.isNullOrEmpty()) {
                leftLetterTextView?.visibility = GONE
            } else {
                leftLetterTextView?.text = leftLetterText
            }

            if (rightLetterText.isNullOrEmpty()) {
                rightLetterTextView?.visibility = GONE
            } else {
                rightLetterTextView?.text = rightLetterText
            }
        }
    }

    fun getTopText(): String {
        return topLetterTextView?.text.toString()
    }

    fun getLeftText(): String {
        return leftLetterTextView?.text.toString()
    }

    fun getRightText(): String {
        return rightLetterTextView?.text.toString()
    }
}
