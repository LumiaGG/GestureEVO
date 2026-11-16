package io.github.lumiagg.gestureevo.settings.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px

/** Shared sizing and form controls for the link settings panels. */
internal fun BottomSheetDialog.prepareLinkSettingsSheet(content: View) {
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    setOnShowListener {
        DynamicColorUtils.applyBottomSheetSurface(this)
        val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return@setOnShowListener
        sheet.layoutParams = sheet.layoutParams.apply {
            height = (context.resources.displayMetrics.heightPixels * 0.9f).toInt()
        }
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        val bottomPadding = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomPadding + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }
}

internal class LinkSettingsForm(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setPadding(24f.dp2px.toInt(), 8f.dp2px.toInt(), 24f.dp2px.toInt(), 0)
    }

    fun field(hint: Int, value: String = "", multiline: Boolean = false): TextInputLayout {
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) {
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1
            maxLines = if (multiline) 4 else 1
            setText(value)
        }
        return TextInputLayout(context, null, com.google.android.material.R.attr.textInputStyle).apply {
            setHint(hint)
            setBoxCornerRadii(12f.dp2px, 12f.dp2px, 12f.dp2px, 12f.dp2px)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            addView(input, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            this@LinkSettingsForm.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 12f.dp2px.toInt()
            })
        }
    }
}
