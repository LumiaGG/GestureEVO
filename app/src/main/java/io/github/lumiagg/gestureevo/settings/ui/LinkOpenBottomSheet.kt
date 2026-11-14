package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import androidx.lifecycle.LifecycleCoroutineScope
import com.drake.brv.BindingAdapter
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.utils.icon.IconHelper

/**
 * App picker used when a link rule needs a target application.
 *
 * The dispatch rules live in [io.github.lumiagg.gestureevo.link] now; this file only keeps the
 * picker so rule editing can reuse the same app list as the rest of the settings screens.
 */
class LinkHandlerSelectAPPBottomSheet(
    activity: Activity,
    coroutineScope: LifecycleCoroutineScope,
    selectAPPCallBack: SelectAPPCallBack
) : BaseSelectAPPBottomSheet(activity, coroutineScope, selectAPPCallBack) {

    override fun onBindItem(holder: BindingAdapter.BindingViewHolder) {
        holder.findView<MaterialTextView>(R.id.name).text = holder.getModel<APPModel>().name
        holder.findView<RoundImageTextView>(R.id.icon).setImage(
            IconHelper.getIcon(holder.context, holder.getModel<APPModel>().pkgName)
        )
    }
}
