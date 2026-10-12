package io.github.lumiagg.gestureevo.settings.guide

import android.app.Dialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px

internal fun showGuideWheelEditor(
    activity: AppCompatActivity,
    onDismiss: () -> Unit
): Dialog {
    val context = DynamicColorUtils.dynamicThemedContext(activity)
    val dialog = Dialog(context, R.style.Theme_GestureEVO)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val root = FrameLayout(context).apply {
        setBackgroundColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, 0))
    }
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(24f.dp2px.toInt(), 0, 8f.dp2px.toInt(), 0)
    }
    root.addView(panel, FrameLayout.LayoutParams(-1, 56f.dp2px.toInt(), Gravity.TOP))
    panel.addView(MaterialTextView(context).apply {
        setText(R.string.quick_setup_edit_wheel)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(DynamicColorUtils.onSurfaceColor)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    panel.addView(AppCompatImageButton(context).apply {
        setImageResource(R.drawable.baseline_check_24)
        imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
        contentDescription = context.getString(R.string.quick_practice_done)
        TooltipCompat.setTooltipText(this, contentDescription)
        val background = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
        setBackgroundResource(background.resourceId)
        setPadding(12f.dp2px.toInt(), 12f.dp2px.toInt(), 12f.dp2px.toInt(), 12f.dp2px.toInt())
        setOnClickListener { dialog.dismiss() }
    }, LinearLayout.LayoutParams(48f.dp2px.toInt(), 48f.dp2px.toInt()))
    val instructions = ScrollView(context)
    instructions.addView(MaterialTextView(context).apply {
        setText(R.string.quick_setup_edit_hint)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(DynamicColorUtils.onSurfaceVariantColor)
        setPadding(24f.dp2px.toInt(), 12f.dp2px.toInt(), 24f.dp2px.toInt(), 12f.dp2px.toInt())
    })
    root.addView(instructions, FrameLayout.LayoutParams(-1, 80f.dp2px.toInt(), Gravity.TOP).apply {
        topMargin = 56f.dp2px.toInt()
    })
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
    }
    activity.lifecycle.addObserver(observer)
    dialog.setOnDismissListener {
        activity.lifecycle.removeObserver(observer)
        onDismiss()
    }
    dialog.setContentView(root)
    dialog.show()
    dialog.window?.let {
        WindowCompat.setDecorFitsSystemWindows(it, false)
        it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }
    ViewCompat.requestApplyInsets(root)
    return dialog
}
