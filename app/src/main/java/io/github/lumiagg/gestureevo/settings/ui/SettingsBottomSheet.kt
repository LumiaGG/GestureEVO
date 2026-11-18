package io.github.lumiagg.gestureevo.settings.ui

import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils

/** Shared settings panels: expanded height, theme surface and navigation-bar clearance. */
internal fun BottomSheetDialog.prepareSettingsSheet(content: View) {
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
