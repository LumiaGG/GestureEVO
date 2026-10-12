package io.github.lumiagg.gestureevo.inspire.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.InspireEditSheetViewBinding
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils

class InspireEditSheetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val binding =
        InspireEditSheetViewBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        orientation = VERTICAL
    }

    fun bind(
        title: String,
        hint: String,
        scopeText: String,
        defaultText: String,
        onCancel: () -> Unit,
        onConfirm: (String) -> Unit,
    ) = with(binding) {
        this.title.text = title
        textInputLayout.hint = hint
        scopeValue.text = scopeText
        inputField.apply {
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            maxLines = 10
            isSingleLine = false
            setText(defaultText)
            setSelection(text?.length ?: 0)
        }
        btnCancel.setOnClickListener { onCancel() }
        btnConfirm.setOnClickListener {
            onConfirm(inputField.text?.toString().orEmpty())
        }
    }

    fun focusEditorAndShowKeyboard() {
        binding.inputField.post {
            binding.inputField.requestFocus()
            binding.inputField.setSelection(binding.inputField.text?.length ?: 0)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.inputField, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}

fun showInspireEditBottomSheet(
    context: Context,
    title: String,
    hint: String,
    scopeText: String,
    defaultText: String,
    disableDragToDismiss: Boolean = true,
    onConfirm: (String) -> Unit,
) {
    val themedContext = DynamicColorUtils.dynamicThemedContext(context)
    val dialog =
        BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
    dialog.window?.setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
    )
    dialog.setCanceledOnTouchOutside(!disableDragToDismiss)

    val contentView = InspireEditSheetView(themedContext)
    contentView.bind(
        title = title,
        hint = hint,
        scopeText = scopeText,
        defaultText = defaultText,
        onCancel = { dialog.dismiss() },
        onConfirm = {
            onConfirm(it)
            dialog.dismiss()
        }
    )
    dialog.setContentView(contentView)

    dialog.setOnShowListener { shownDialog ->
        val bottomSheet = (shownDialog as BottomSheetDialog)
            .findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)

        bottomSheet?.let { sheet ->
            DynamicColorUtils.applyBottomSheetSurface(shownDialog)
            val behavior = BottomSheetBehavior.from(sheet)
            behavior.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                isDraggable = !disableDragToDismiss
                skipCollapsed = true
                isFitToContents = true
                isHideable = !disableDragToDismiss
            }
            val windowHeight = sheet.context.resources.displayMetrics.heightPixels
            sheet.layoutParams.height = (windowHeight * 0.9f).toInt()
            sheet.requestLayout()
        }

        contentView.focusEditorAndShowKeyboard()
    }

    dialog.show()
}
