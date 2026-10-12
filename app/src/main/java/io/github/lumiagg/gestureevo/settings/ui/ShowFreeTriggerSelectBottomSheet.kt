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
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerDataManager
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import kotlinx.coroutines.launch

private data class FreeTriggerSelectOption(
    val trigger: FreeTriggerConfig,
    val isMirror: Boolean
) {
    val tag: String get() = trigger.tag
}

fun showFreeTriggerSelectBottomSheet(
    activity: AppCompatActivity,
    coroutineScope: LifecycleCoroutineScope,
    title: String = activity.getString(R.string.external_call_trigger_select_title),
    subtitle: String = activity.getString(R.string.external_call_trigger_select_subtitle),
    currentSelectedTag: String,
    currentSelectedMirror: Boolean = false,
    onSelect: (String, Boolean) -> Unit
) {
    val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
    val bottomSheetDialog =
        BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)

    bottomSheetDialog.setContentView(R.layout.action_items_tag_select_bottomsheet)
    bottomSheetDialog.findViewById<TextView>(R.id.title)?.text = title
    bottomSheetDialog.findViewById<TextView>(R.id.subtitle)?.text = subtitle

    val recyclerView = bottomSheetDialog.findViewById<RecyclerView>(R.id.pieGroupsRecyclerView)
    recyclerView?.linear()

    coroutineScope.launch {
        val triggers = FreeTriggerDataManager.readTriggers(activity.applicationContext)
        if (triggers.isEmpty()) {
            bottomSheetDialog.dismiss()
            return@launch
        }

        val options = triggers.flatMap { trigger ->
            buildList {
                add(FreeTriggerSelectOption(trigger, false))
                if (trigger.getState(FreeTriggerConfig.FLAG_MIRROR)) {
                    add(FreeTriggerSelectOption(trigger, true))
                }
            }
        }

        recyclerView?.setup {
            addType<FreeTriggerSelectOption>(R.layout.action_items_tags_list_item)

            onBind {
                val item = getModel<FreeTriggerSelectOption>()
                findView<TextView>(R.id.pieGroupName).text = triggerDisplayName(
                    activity,
                    item.trigger,
                    item.isMirror
                )
                findView<RadioButton>(R.id.radioButton).isChecked =
                    item.tag == currentSelectedTag && item.isMirror == currentSelectedMirror
            }

            onClick(R.id.root) {
                val item = getModel<FreeTriggerSelectOption>()
                onSelect(item.tag, item.isMirror)
                bottomSheetDialog.dismiss()
            }
        }

        (recyclerView?.adapter as? BindingAdapter)?.models = options
    }

    bottomSheetDialog.setOnShowListener { dialog ->
        val bottomSheet = (dialog as BottomSheetDialog)
            .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as FrameLayout?

        bottomSheet?.let { sheet ->
            DynamicColorUtils.applyBottomSheetSurface(dialog)
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                isDraggable = true
                skipCollapsed = true
                isFitToContents = true
                isHideable = true
            }
            val windowHeight = activity.window.decorView.height
            sheet.layoutParams.height = (windowHeight * 0.7).toInt()
        }
    }

    bottomSheetDialog.show()
}

fun triggerDisplayName(
    activity: AppCompatActivity,
    trigger: FreeTriggerConfig,
    isMirror: Boolean
): String {
    return if (isMirror) {
        activity.getString(R.string.action_view_trigger_mirror_name, trigger.name)
    } else {
        trigger.name
    }
}
