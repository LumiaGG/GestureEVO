package io.github.lumiagg.gestureevo.settings.ui

import android.content.res.ColorStateList
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.BottomSheetActionSelectBinding
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.GestureActionLink
import io.github.lumiagg.gestureevo.utils.dp2px

/** One catalog for gesture, wheel and external-action editors. Null parameters require configuration. */
internal data class ActionChoice(
    val type: GestureActionType,
    val title: Int? = null,
    val parameters: Map<String, String>? = null
) {
    fun label(activity: AppCompatActivity): String = title?.let(activity::getString) ?: type.showName(activity)
}

internal fun showActionSelectBottomSheet(
    activity: AppCompatActivity,
    current: GestureActionLink,
    allowWheel: Boolean = true,
    allowNone: Boolean = true,
    allowClickThrough: Boolean = true,
    onSelected: (ActionChoice) -> Unit
) {
    val context = DynamicColorUtils.dynamicThemedContext(activity)
    val dialog = BottomSheetDialog(context, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
    val binding = BottomSheetActionSelectBinding.inflate(android.view.LayoutInflater.from(context))
    dialog.setContentView(binding.root)
    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = dialog.dismiss()
    }
    activity.lifecycle.addObserver(observer)
    dialog.setOnDismissListener { activity.lifecycle.removeObserver(observer) }

    val categories = actionCategories(Build.VERSION.SDK_INT, allowWheel, allowNone, allowClickThrough)
    fun render(query: String) {
        binding.actionCategories.removeAllViews()
        var count = 0
        categories.forEach { (category, choices) ->
            val visible = choices.filter {
                query.isBlank() || it.label(activity).contains(query, true) ||
                    it.type.name.contains(query, true) || activity.getString(category).contains(query, true)
            }
            if (visible.isEmpty()) return@forEach
            binding.actionCategories.addView(TextView(context).apply {
                ViewCompat.setAccessibilityHeading(this, true)
                text = activity.getString(category)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTextColor(DynamicColorUtils.primaryColor)
                setPadding(dp(4), dp(20), dp(4), dp(6))
            })
            visible.forEach { choice ->
                count++
                val selected = current.actionType == choice.type &&
                    (choice.parameters?.all { (key, value) -> current.queryList.any { it.first == key && it.second == value } } ?: true)
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setBackgroundResource(R.drawable.bg_settings_tile)
                    isClickable = true
                    isFocusable = true
                    isSelected = selected
                    setOnClickListener { dialog.dismiss(); onSelected(choice) }
                }
                row.addView(ImageView(context).apply {
                    setImageResource(when (choice.type) {
                        GestureActionType.ACC_ACTION -> when (choice.parameters?.get("accAction")) {
                            "1" -> R.drawable.baseline_arrow_back_24
                            "2" -> R.drawable.baseline_home_24
                            "4" -> R.drawable.baseline_circle_notifications_24
                            "8" -> R.drawable.icon_lock
                            "9" -> R.drawable.baseline_screenshot_24
                            else -> R.drawable.baseline_menu_24
                        }
                        GestureActionType.OPEN_APP, GestureActionType.OPEN_ACTIVITY, GestureActionType.OTHER_SHORTCUT -> R.drawable.baseline_image_24
                        GestureActionType.SEARCH, GestureActionType.SCREEN_SEARCH -> R.drawable.search_24px
                        GestureActionType.SCREEN_CONTENT, GestureActionType.SCREEN_CONTENT_FULLSCREEN -> R.drawable.ic_screen_content_fullscreen_text_24
                        GestureActionType.KEYBOARD -> R.drawable.baseline_keyboard_24
                        GestureActionType.PIN_POOL -> R.drawable.baseline_push_pin_24
                        GestureActionType.OTHER_LINK -> R.drawable.ic_baseline_insert_link_24
                        GestureActionType.CLIPBOARD_ACTION -> R.drawable.ic_baseline_content_copy_24
                        GestureActionType.SHOW_ACTION_VIEW -> R.drawable.baseline_menu_24
                        else -> R.drawable.baseline_gesture_24
                    })
                    imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
                val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                text.addView(TextView(context).apply {
                    this.text = choice.label(activity)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                    setTextColor(DynamicColorUtils.onSurfaceColor)
                })
                text.addView(TextView(context).apply {
                    setText(if (choice.parameters == null) R.string.action_requires_config else R.string.action_ready)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(DynamicColorUtils.onSurfaceVariantColor)
                })
                row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                if (selected) row.addView(ImageView(context).apply {
                    setImageResource(R.drawable.baseline_check_24)
                    imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
                    contentDescription = activity.getString(R.string.action_selected)
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                binding.actionCategories.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }
        binding.actionEmpty.visibility = if (count == 0) View.VISIBLE else View.GONE
    }
    binding.actionSearch.doAfterTextChanged { render(it?.toString()?.trim().orEmpty()) }
    render("")
    dialog.setOnShowListener {
        DynamicColorUtils.applyBottomSheetSurface(dialog)
        dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            it.layoutParams.height = activity.window.decorView.height
            BottomSheetBehavior.from(it).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }
    dialog.show()
}

private fun dp(value: Int): Int = (value.toFloat().dp2px).toInt()

/** SDK and entry-point restrictions are applied before search, so hidden actions cannot leak into results. */
internal fun actionCategories(
    sdk: Int,
    allowWheel: Boolean = true,
    allowNone: Boolean = true,
    allowClickThrough: Boolean = true
): List<Pair<Int, List<ActionChoice>>> {
    fun simple(type: GestureActionType) = ActionChoice(type, parameters = emptyMap())
    fun navigation(id: Int, title: Int) = ActionChoice(GestureActionType.ACC_ACTION, title, mapOf("accAction" to id.toString()))
    val system = mutableListOf(
        navigation(1, R.string.acc_action_select_back), navigation(2, R.string.acc_action_select_home),
        navigation(3, R.string.acc_action_select_recents), simple(GestureActionType.RECENT_APP),
        navigation(4, R.string.acc_action_select_notifications), navigation(5, R.string.acc_action_select_quick_settings),
        navigation(6, R.string.acc_action_select_power_dialog)
    )
    if (sdk >= 28) {
        system += navigation(9, R.string.acc_action_select_screenshot)
        system += navigation(8, R.string.acc_action_select_lock_screen)
    }
    return listOf(
        R.string.quick_setup_category_system to system,
        R.string.quick_setup_category_tools to listOf(
            simple(GestureActionType.SEARCH), simple(GestureActionType.SCREEN_SEARCH),
            simple(GestureActionType.SCREEN_CONTENT), simple(GestureActionType.SCREEN_CONTENT_FULLSCREEN),
            simple(GestureActionType.PIN_POOL), simple(GestureActionType.POINTER), simple(GestureActionType.KEYBOARD)
        ),
        R.string.quick_setup_category_apps to listOf(
            ActionChoice(GestureActionType.OPEN_APP), ActionChoice(GestureActionType.OPEN_ACTIVITY),
            ActionChoice(GestureActionType.OTHER_SHORTCUT), ActionChoice(GestureActionType.FREEZE_APP),
            simple(GestureActionType.FREEFORM_FOREGROUND)
        ),
        R.string.action_category_gestures to listOfNotNull(
            ActionChoice(GestureActionType.SHOW_ACTION_VIEW).takeIf { allowWheel },
            ActionChoice(GestureActionType.PAUSE_GESTURE),
            simple(GestureActionType.CLICK_THROUGH).takeIf { allowClickThrough }
        ),
        R.string.quick_setup_category_other to listOfNotNull(
            ActionChoice(GestureActionType.OTHER_LINK), ActionChoice(GestureActionType.OPEN_SHELL),
            ActionChoice(GestureActionType.CLIPBOARD_ACTION), ActionChoice(GestureActionType.TEMP_DND),
            simple(GestureActionType.NONE).takeIf { allowNone }
        )
    )
}
