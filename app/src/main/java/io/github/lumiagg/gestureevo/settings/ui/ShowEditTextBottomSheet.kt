package io.github.lumiagg.gestureevo.settings.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils

fun showEditTextBottomSheet(
    activity: AppCompatActivity,
    coroutineScope: LifecycleCoroutineScope,
    title: String,
    hint: String,
    defaultText: String? = null,
    isMultiLine: Boolean = false,
    disableDragToDismiss: Boolean = false,
    onConfirm: (String) -> Unit,
    showDeleteButton: Boolean = false,
    deleteConfirmationMessage: String? = null,
    onDelete: (() -> Unit)? = null
) {
    val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
    val bottomSheetDialog =
        BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)

    // Set soft input mode
    bottomSheetDialog.window?.setSoftInputMode(
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
    )

    bottomSheetDialog.setContentView(R.layout.edit_bottomsheet)

    // Prevent automatic dismiss when touching outside
    bottomSheetDialog.setCanceledOnTouchOutside(!disableDragToDismiss)

    // Set title
    bottomSheetDialog.findViewById<TextView>(R.id.title)?.apply {
        text = title
    }

    // Get input field and buttons
    val inputLayout = bottomSheetDialog.findViewById<TextInputLayout>(R.id.textInputLayout)
    val inputField = bottomSheetDialog.findViewById<TextInputEditText>(R.id.input_field)
    val confirmButton = bottomSheetDialog.findViewById<MaterialButton>(R.id.btn_confirm)
    val deleteButton = bottomSheetDialog.findViewById<MaterialButton>(R.id.btn_delete)
    val cancelButton = bottomSheetDialog.findViewById<MaterialButton>(R.id.btn_cancel)

    // Set visibility for delete button
    deleteButton?.apply {
        isVisible = showDeleteButton && onDelete != null

        setOnClickListener {
            val confirmMessage =
                deleteConfirmationMessage ?: activity.getString(R.string.edit_delete_confirm_message)
            // Show confirmation dialog
            AlertDialog.Builder(activity)
                .setTitle(R.string.edit_delete_confirm_title)
                .setMessage(confirmMessage)
                .setPositiveButton(R.string.edit_delete_confirm_action) { _, _ ->
                    onDelete?.invoke()
                    bottomSheetDialog.dismiss()
                }
                .setNegativeButton(R.string.edit_delete_confirm_cancel, null)
                .show()
        }
    }

    // Setup cancel button
    cancelButton?.apply {
        setOnClickListener {
            bottomSheetDialog.dismiss()
        }
    }

    inputField?.apply {
        inputLayout?.hint = hint
        defaultText?.let {
            inputField.setText(it)
        }
        if (isMultiLine) {
            inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            maxLines = 10
            isSingleLine = false
        } else {
            inputType = InputType.TYPE_CLASS_TEXT
            minLines = 1
            maxLines = 1
            isSingleLine = true
        }
        // Listen for input changes
        doAfterTextChanged { text ->
            confirmButton?.isEnabled = !text.isNullOrEmpty()
        }
    }

    // Setup confirm button click listener
    confirmButton?.apply {
        isEnabled = !inputField?.text.isNullOrEmpty()  // Determine button state based on initial text
        setOnClickListener {
            val inputText = inputField?.text?.toString() ?: ""
            onConfirm(inputText)
            bottomSheetDialog.dismiss()
        }
    }

    // Configure bottom sheet behavior
    bottomSheetDialog.setOnShowListener { dialog ->
        val bottomSheet = (dialog as BottomSheetDialog)
            .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as FrameLayout?

        bottomSheet?.let { sheet ->
            DynamicColorUtils.applyBottomSheetSurface(dialog)
            val behavior = BottomSheetBehavior.from(sheet)
            behavior.apply {
                sheet.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                state = BottomSheetBehavior.STATE_EXPANDED
                isDraggable = !disableDragToDismiss
                skipCollapsed = true
                isFitToContents = true
                isHideable = !disableDragToDismiss
            }

            // Increase expanded height for multi-line inputs
            val windowHeight = activity.window.decorView.height
            sheet.layoutParams.height = (windowHeight * 0.9).toInt()
        }

        inputField?.post {
            inputField.requestFocus()
            inputField.setSelection(inputField.text?.length ?: 0)
            val inputMethodManager =
                activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputMethodManager?.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // Display bottom sheet
    bottomSheetDialog.show()
}
