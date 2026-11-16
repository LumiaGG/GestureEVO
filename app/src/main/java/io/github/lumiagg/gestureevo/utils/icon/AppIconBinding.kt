package io.github.lumiagg.gestureevo.utils.icon

import android.graphics.drawable.Drawable
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.ioResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Original app colors and the same global icon shape used by search results. */
internal fun RoundImageTextView.setAppIcon(icon: Drawable?, placeholder: Int = R.drawable.baseline_image_24) {
    applyGlobalImageRoundPercent = icon != null
    if (icon == null) setImageWithBackplate(placeholder, iconInset = 6.dp2px.toInt())
    else setImage(icon) // setImage clears tint and applies IconHelper's global round percentage.
}

/** Cancel stale row loads; IconHelper owns the shared icon cache. */
internal fun RoundImageTextView.bindAppIcon(scope: CoroutineScope, packageName: String, iconPath: String = "") {
    (getTag(R.id.app_icon_binding_job) as? Job)?.cancel()
    setAppIcon(null)
    if (packageName.isBlank()) { setTag(R.id.app_icon_binding_job, null); return }
    val job = scope.launch(Dispatchers.Main.immediate) {
        val icon = ioResult { IconHelper.getIconDrawable(context, packageName, iconPath) }.getOrNull()
        if (icon != null) setAppIcon(icon)
    }
    setTag(R.id.app_icon_binding_job, job)
}
