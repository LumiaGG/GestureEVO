package io.github.lumiagg.gestureevo.settings.guide

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.BottomSheetGuideActionSelectBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.settings.ui.DefaultSelectAPPBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.SelectAPPCallBack
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.app.syncAppCatalog
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.launch

internal fun showGuideActionSelectBottomSheet(
    activity: AppCompatActivity,
    allowWheel: Boolean,
    allowNone: Boolean,
    currentAction: QuickAction? = null,
    onActionSelected: (QuickAction) -> Unit
) {
    val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
    val dialog = BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
    val binding = BottomSheetGuideActionSelectBinding.inflate(activity.layoutInflater)
    dialog.setContentView(binding.root)

    fun createActionRow(action: QuickAction): View {
        val row = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_settings_tile)
            minimumHeight = 64f.dp2px.toInt()
            val padH = 12f.dp2px.toInt()
            val padV = 10f.dp2px.toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
        }

        // Icon badge
        val iconBadge = ImageView(themedContext).apply {
            val size = 40f.dp2px.toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = 12f.dp2px.toInt()
            }
            val pad = 8f.dp2px.toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(DynamicColorUtils.secondaryContainerColor)
            }
            setImageDrawable(action.icon(themedContext))
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (action.type != QuickActionType.OPEN_APP || action.packageName == null) {
                val tint = com.google.android.material.color.MaterialColors.getColor(
                    themedContext,
                    com.google.android.material.R.attr.colorOnSecondaryContainer,
                    DynamicColorUtils.primaryColor
                )
                imageTintList = ColorStateList.valueOf(tint)
            }
        }
        row.addView(iconBadge)

        // Text column
        val textCol = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleView = TextView(themedContext).apply {
            text = action.label(themedContext)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(DynamicColorUtils.onSurfaceColor)
        }
        textCol.addView(titleView)

        val descView = TextView(themedContext).apply {
            text = action.description(themedContext)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(DynamicColorUtils.onSurfaceVariantColor)
            setPadding(0, 2f.dp2px.toInt(), 0, 0)
        }
        textCol.addView(descView)
        row.addView(textCol)

        // Selected indicator
        val isSelected = currentAction?.type == action.type &&
            (action.type != QuickActionType.OPEN_APP || currentAction?.packageName == action.packageName)
        if (isSelected) {
            val checkmark = ImageView(themedContext).apply {
                val size = 24f.dp2px.toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = 8f.dp2px.toInt()
                }
                setImageResource(R.drawable.baseline_check_24)
                imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
            }
            row.addView(checkmark)
        }

        row.setOnClickListener {
            if (action.type == QuickActionType.OPEN_APP) {
                dialog.dismiss()
                activity.lifecycleScope.launch {
                    syncAppCatalog(activity.applicationContext)
                    DefaultSelectAPPBottomSheet(activity, activity.lifecycleScope, object : SelectAPPCallBack {
                        override fun selectCompleted(model: APPModel) {
                            onActionSelected(QuickAction(QuickActionType.OPEN_APP, model.pkgName, model.name, model.userId))
                        }
                    }).show()
                }
            } else {
                dialog.dismiss()
                onActionSelected(action)
            }
        }

        return row
    }

    fun addActions(container: LinearLayout, actions: List<QuickAction>) {
        actions.forEach { action ->
            val row = createActionRow(action)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 6f.dp2px.toInt()
            }
            container.addView(row, params)
        }
    }

    // 1. System Navigation
    val systemActions = mutableListOf(
        QuickAction(QuickActionType.BACK),
        QuickAction(QuickActionType.HOME),
        QuickAction(QuickActionType.RECENTS),
        QuickAction(QuickActionType.NOTIFICATIONS),
        QuickAction(QuickActionType.QUICK_SETTINGS),
    )
    if (Build.VERSION.SDK_INT >= 28) {
        systemActions.add(QuickAction(QuickActionType.SCREENSHOT))
    }
    addActions(binding.systemActionsContainer, systemActions)

    // 2. Features & Tools
    val toolActions = listOf(
        QuickAction(QuickActionType.SEARCH),
        QuickAction(QuickActionType.SCREEN_SEARCH),
        QuickAction(QuickActionType.SCREEN_CONTENT),
        QuickAction(QuickActionType.PIN_POOL),
    )
    addActions(binding.toolsActionsContainer, toolActions)

    // 3. Application
    val appAction = if (currentAction?.type == QuickActionType.OPEN_APP) currentAction else QuickAction(QuickActionType.OPEN_APP)
    addActions(binding.appsActionsContainer, listOf(appAction))

    // 4. Wheel Menu
    binding.categoryWheel.isVisible = allowWheel
    if (allowWheel) {
        addActions(binding.wheelActionsContainer, listOf(QuickAction(QuickActionType.WHEEL)))
    }

    // 5. Other
    binding.categoryOther.isVisible = allowNone
    if (allowNone) {
        addActions(binding.otherActionsContainer, listOf(QuickAction(QuickActionType.NONE)))
    }

    dialog.setOnShowListener { d ->
        val bottomSheet = (d as BottomSheetDialog)
            .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
        bottomSheet?.let { sheet ->
            DynamicColorUtils.applyBottomSheetSurface(dialog)
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                isDraggable = true
                skipCollapsed = true
                isFitToContents = true
                isHideable = true
            }
        }
    }

    dialog.show()
}
