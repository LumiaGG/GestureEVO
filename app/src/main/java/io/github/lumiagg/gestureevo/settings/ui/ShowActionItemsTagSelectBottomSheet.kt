package io.github.lumiagg.gestureevo.settings.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.BindingAdapter
import com.drake.brv.utils.linear
import com.drake.brv.utils.setup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.config.ActionItemsTag
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import kotlinx.coroutines.launch

fun showPieGroupSelectBottomSheet(
    activity: AppCompatActivity,
    coroutineScope: LifecycleCoroutineScope,
    title: String = activity.getString(R.string.action_items_tag_select_title),
    subtitle: String = activity.getString(R.string.action_items_tag_select_subtitle),
    currentSelectedTag: String,
    onSelect: (String) -> Unit
) {
    val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
    val bottomSheetDialog =
        BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)

    bottomSheetDialog.setContentView(R.layout.action_items_tag_select_bottomsheet)

    // Set title and subtitle
    bottomSheetDialog.findViewById<TextView>(R.id.title)?.apply {
        text = title
    }
    bottomSheetDialog.findViewById<TextView>(R.id.subtitle)?.apply {
        text = subtitle
    }

    // Get RecyclerView
    val recyclerView = bottomSheetDialog.findViewById<RecyclerView>(R.id.pieGroupsRecyclerView)

    // Configure RecyclerView with BRV
    recyclerView?.linear()

    // Load wheel group data
    coroutineScope.launch {
        val pieGroups = GestureActionDataManager.readActionListTags(activity.applicationContext)

        if (pieGroups.isEmpty()) {
            // If no groups exist, show empty state or dismiss
            bottomSheetDialog.dismiss()
            return@launch
        }

        // Setup adapter with BRV
        recyclerView?.setup {
            addType<ActionItemsTag>(R.layout.action_items_tags_list_item)

            // Bind view and data
            onBind {
                val item = getModel<ActionItemsTag>()
                findView<TextView>(R.id.pieGroupName).text = item.name
                findView<RadioButton>(R.id.radioButton).isChecked = item.tag == currentSelectedTag
            }

            // Setup click listener
            onClick(R.id.root) {
                val item = getModel<ActionItemsTag>()
                // If clicked item is not current selection, invoke callback and dismiss
                onSelect(item.tag)
                bottomSheetDialog.dismiss()
            }
        }

        // Set data
        (recyclerView?.adapter as? BindingAdapter)?.models = pieGroups
    }

    // Configure bottom sheet behavior
    bottomSheetDialog.setOnShowListener { dialog ->
        val bottomSheet = (dialog as BottomSheetDialog)
            .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as FrameLayout?

        bottomSheet?.let { sheet ->
            DynamicColorUtils.applyBottomSheetSurface(dialog)
            val behavior = BottomSheetBehavior.from(sheet)
            behavior.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                isDraggable = true
                skipCollapsed = true
                isFitToContents = true
                isHideable = true
            }

            // Set maximum height
            val windowHeight = activity.window.decorView.height
            sheet.layoutParams.height = (windowHeight * 0.7).toInt()
        }
    }

    // Display bottom sheet
    bottomSheetDialog.show()
}
