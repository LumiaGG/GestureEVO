package io.github.lumiagg.gestureevo.settings.guide

import android.content.res.ColorStateList
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.BottomSheetGuideActionSelectBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.gesture.ui.applyWheelStyle
import io.github.lumiagg.gestureevo.settings.ui.DefaultSelectAPPBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.SelectAPPCallBack
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.app.syncAppCatalog
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun showGuideActionSelectBottomSheet(
    activity: AppCompatActivity,
    allowWheel: Boolean,
    allowNone: Boolean,
    currentAction: GestureActionItem? = null,
    onActionSelected: (GestureActionItem) -> Unit,
    onMoreSettings: () -> Unit
) {
    val context = DynamicColorUtils.dynamicThemedContext(activity)
    val dialog = BottomSheetDialog(context, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
    val binding = BottomSheetGuideActionSelectBinding.inflate(activity.layoutInflater)
    dialog.setContentView(binding.root)
    binding.moreSettingsButton.setOnClickListener {
        dialog.dismiss()
        onMoreSettings()
    }
    val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
    }
    activity.lifecycle.addObserver(lifecycleObserver)
    dialog.setOnDismissListener { activity.lifecycle.removeObserver(lifecycleObserver) }

    fun createActionRow(action: GestureActionItem): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_settings_tile)
            minimumHeight = 64f.dp2px.toInt()
            setPadding(12f.dp2px.toInt(), 10f.dp2px.toInt(), 12f.dp2px.toInt(), 10f.dp2px.toInt())
            isClickable = true
            isFocusable = true
        }
        row.addView(RoundImageTextView(context).apply {
            val size = 40f.dp2px.toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = 12f.dp2px.toInt() }
            applyWheelStyle(ActionConfig(), size / 55f.dp2px)
            bindGuideAction(action)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(context).apply {
            this.text = action.label(context)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(DynamicColorUtils.onSurfaceColor)
        })
        text.addView(TextView(context).apply {
            this.text = action.description(context)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(DynamicColorUtils.onSurfaceVariantColor)
            setPadding(0, 2f.dp2px.toInt(), 0, 0)
        })
        row.addView(text)
        if (currentAction?.firstStageLink == action.firstStageLink) {
            row.addView(ImageView(context).apply {
                val size = 24f.dp2px.toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = 8f.dp2px.toInt() }
                setImageResource(R.drawable.baseline_check_24)
                imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        row.setOnClickListener {
            if (action.guideActionType == GestureActionType.OPEN_APP) {
                dialog.dismiss()
                activity.lifecycleScope.launch {
                    try {
                        syncAppCatalog(activity.applicationContext)
                        if (activity.isFinishing || activity.isDestroyed) return@launch
                        DefaultSelectAPPBottomSheet(activity, activity.lifecycleScope, object : SelectAPPCallBack {
                            override fun selectCompleted(model: APPModel) {
                                onActionSelected(createGuideAction(GestureActionType.OPEN_APP,
                                    mapOf("pkg" to model.pkgName, "userId" to model.userId.toString()), model.name))
                            }
                        }).show()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("GuideActionSelect", "Unable to load applications", e)
                        android.widget.Toast.makeText(activity, R.string.guide_load_failed, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                dialog.dismiss()
                onActionSelected(action.copy())
            }
        }
        return row
    }

    fun addActions(container: LinearLayout, actions: List<GestureActionItem>) {
        actions.forEach { action ->
            container.addView(createActionRow(action), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6f.dp2px.toInt() })
        }
    }
    val system = mutableListOf(createAccessibilityAction(1), createAccessibilityAction(2), createAccessibilityAction(3),
        createGuideAction(GestureActionType.RECENT_APP), createAccessibilityAction(4), createAccessibilityAction(5))
    if (Build.VERSION.SDK_INT >= 28) system.add(createAccessibilityAction(9))
    addActions(binding.systemActionsContainer, system)
    addActions(binding.toolsActionsContainer, listOf(createGuideAction(GestureActionType.SEARCH),
        createGuideAction(GestureActionType.SCREEN_SEARCH), createGuideAction(GestureActionType.SCREEN_CONTENT),
        createGuideAction(GestureActionType.PIN_POOL)))
    addActions(binding.appsActionsContainer, listOf(
        currentAction?.takeIf { it.guideActionType == GestureActionType.OPEN_APP } ?: createGuideAction(GestureActionType.OPEN_APP)))
    binding.categoryWheel.isVisible = allowWheel
    if (allowWheel) addActions(binding.wheelActionsContainer, listOf(createGuideAction(GestureActionType.SHOW_ACTION_VIEW,
        mapOf("actionItemsTag" to QuickSetupRepository.WHEEL_TAG))))
    binding.categoryOther.isVisible = allowNone
    if (allowNone) addActions(binding.otherActionsContainer, listOf(createGuideAction(GestureActionType.NONE)))
    dialog.setOnShowListener {
        dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            DynamicColorUtils.applyBottomSheetSurface(dialog)
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isFitToContents = true
                isHideable = true
            }
        }
    }
    dialog.show()
}
