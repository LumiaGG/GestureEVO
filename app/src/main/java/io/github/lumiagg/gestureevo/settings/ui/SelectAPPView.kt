package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.drake.brv.BindingAdapter
import com.drake.brv.utils.models
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.db.updateDB
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ShowItem(val appModel: APPModel, val icon: Drawable?)

// Default implementation
class DefaultSelectAPPBottomSheet(
    activity: Activity,
    coroutineScope: LifecycleCoroutineScope,
    selectAPPCallBack: SelectAPPCallBack
) : BaseSelectAPPBottomSheet(activity, coroutineScope, selectAPPCallBack) {

    override fun onBindItem(holder: BindingAdapter.BindingViewHolder) {
        // Default binding implementation
        holder.findView<MaterialTextView>(R.id.name).text = holder.getModel<APPModel>().name
        holder.findView<RoundImageTextView>(R.id.icon).setImage(
            IconHelper.getIcon(holder.context, holder.getModel<APPModel>().pkgName)
        )
    }
}

// Default implementation
class ShortcutSelectAPPBottomSheet(
    activity: Activity,
    coroutineScope: LifecycleCoroutineScope,
    selectAPPCallBack: SelectAPPCallBack
) : BaseSelectAPPBottomSheet(activity, coroutineScope, selectAPPCallBack) {

    override fun onBindItem(holder: BindingAdapter.BindingViewHolder) {
        // Default binding implementation
        holder.findView<MaterialTextView>(R.id.name).text = holder.getModel<APPModel>().name
        holder.findView<RoundImageTextView>(R.id.icon).setImage(
            IconHelper.getIcon(holder.context, holder.getModel<APPModel>().pkgName)
        )
    }

    override fun loadApps() {
        coroutineScope.launch(Dispatchers.IO) {
            val shortcutIntent = Intent("android.intent.action.CREATE_SHORTCUT")
            val activities = activity.packageManager.queryIntentActivities(shortcutIntent, 0)
            val appList = ArrayList<APPModel>()
            activities.forEach {
                val pkgName = it.activityInfo.packageName
                val app = getAPPDao().findByPkg(pkgName)
                if (app != null) {
                    appList.add(app)
                }
            }
            fullAppList = appList
            withContext(Dispatchers.Main) {
                recyclerView.models = fullAppList
            }
        }
    }
}

// Activity implementation
class SelectActivityBottomSheet(
    activity: Activity,
    coroutineScope: LifecycleCoroutineScope,
    private val selectActivityCallBack: SelectActivityCallBack,
    private val activityFilter: ActivityFilter? = null // Optional filter
) : BaseSelectAPPBottomSheet(activity, coroutineScope, object : SelectAPPCallBack {}) {

    override fun loadApps() {
        coroutineScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                bottomSheetDialog.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(
                    R.id.loadingProgress
                )?.visibility = android.view.View.VISIBLE
            }

            val apps = getAPPDao().getAll(getAppFlag())
            val pm = activity.packageManager
            fullAppList = if (activityFilter == null) {
                apps
            } else {
                apps.filter { app ->
                    activityFilter.invoke(pm, app.pkgName).isNotEmpty()
                }
            }

            withContext(Dispatchers.Main) {
                recyclerView.models = fullAppList
                bottomSheetDialog.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(
                    R.id.loadingProgress
                )?.visibility = android.view.View.INVISIBLE
            }
        }
    }

    override fun onBindItem(holder: BindingAdapter.BindingViewHolder) {
        // Default binding implementation
        holder.findView<MaterialTextView>(R.id.name).text = holder.getModel<APPModel>().name
        holder.findView<RoundImageTextView>(R.id.icon).setImage(
            IconHelper.getIcon(holder.context, holder.getModel<APPModel>().pkgName)
        )
    }

    override fun onItemClick(model: APPModel) {
        ActivitySelectBottomSheet(
            activity = activity,
            packageName = model.pkgName,
            coroutineScope = coroutineScope,
            selectActivityCallBack = object : SelectActivityCallBack {
                override fun selectCompleted(model: ActivityModel) {
                    selectActivityCallBack.selectCompleted(model)
                    bottomSheetDialog.dismiss()
                }
            }, activityFilter
        ).show()
    }
}

class BlackListSelectAPPBottomSheet(
    activity: Activity,
    coroutineScope: LifecycleCoroutineScope,
    selectAPPCallBack: SelectAPPCallBack,
    val blackListFlag: Int = APPModel.FLAG_GESTURE_DISABLE,
    val disableIcon: Drawable? = ContextCompat.getDrawable(
        activity.applicationContext,
        R.drawable.baseline_clear_24
    )
) : BaseSelectAPPBottomSheet(activity, coroutineScope, selectAPPCallBack) {

    override fun loadApps() {
        coroutineScope.launch(Dispatchers.IO) {
            // Get all apps and sort by blacklist status
            fullAppList = getAPPDao().getAll(getAppFlag()).sortedByDescending {
                it.getState(blackListFlag)
            }
            withContext(Dispatchers.Main) {
                recyclerView.models = fullAppList
            }
        }
    }

    override fun onBindItem(holder: BindingAdapter.BindingViewHolder) {
        val app = holder.getModel<APPModel>()
        holder.findView<MaterialTextView>(R.id.name).text = app.name
        updateIcon(holder, app)
    }

    private fun updateIcon(holder: BindingAdapter.BindingViewHolder, app: APPModel) {
        val iconView = holder.findView<RoundImageTextView>(R.id.icon)
        iconView.setImage(null)

        if (app.getState(blackListFlag)) {
            val appIcon = IconHelper.getIcon(holder.context, app.pkgName)
            val layerDrawable = LayerDrawable(arrayOf(appIcon, disableIcon))
            layerDrawable.setLayerInset(1, 0, 0, 0, 0)
            iconView.setImage(layerDrawable)
        } else {
            iconView.setImage(
                IconHelper.getIcon(holder.context, app.pkgName)
            )
        }
    }

    override fun onItemClick(model: APPModel) {
        coroutineScope.launch(Dispatchers.IO) {
            // Toggle state
            model.setState(
                blackListFlag,
                !model.getState(blackListFlag)
            )

            updateDB(model)

            // Reload and re-sort entire list
            loadApps()

            withContext(Dispatchers.Main) {
                selectAPPCallBack.selectCompleted(model)
            }
        }
    }
}


class BlackListWithNoDBSelectAPPBottomSheet(
    activity: Activity,
    coroutineScope: LifecycleCoroutineScope,
    val blackListSelectAPPCallBack: BlackListSelectAPPCallBack,
    val blackList: MutableList<String>,
    val disableIcon: Drawable? = ContextCompat.getDrawable(
        activity.applicationContext,
        R.drawable.baseline_clear_24
    ),
    selectAPPCallBack: SelectAPPCallBack = object : SelectAPPCallBack {}
) : BaseSelectAPPBottomSheet(activity, coroutineScope, selectAPPCallBack) {

    override fun loadApps() {
        coroutineScope.launch(Dispatchers.IO) {
            // Get all apps and sort by blacklist status
            fullAppList = getAPPDao().getAll(getAppFlag()).sortedBy { app ->
                !blackList.contains(app.pkgName) // Blacklisted apps appear first
            }

            withContext(Dispatchers.Main) {
                recyclerView.models = fullAppList
            }
        }
    }

    override fun onBindItem(holder: BindingAdapter.BindingViewHolder) {
        val app = holder.getModel<APPModel>()
        holder.findView<MaterialTextView>(R.id.name).text = app.name
        updateIcon(holder, app)
    }

    private fun updateIcon(holder: BindingAdapter.BindingViewHolder, app: APPModel) {
        val iconView = holder.findView<RoundImageTextView>(R.id.icon)
        iconView.setImage(null)

        if (app.pkgName in blackList) {
            val appIcon = IconHelper.getIcon(holder.context, app.pkgName)
            val layerDrawable = LayerDrawable(arrayOf(appIcon, disableIcon))
            layerDrawable.setLayerInset(1, 0, 0, 0, 0)
            iconView.setImage(layerDrawable)
        } else {
            iconView.setImage(
                IconHelper.getIcon(holder.context, app.pkgName)
            )
        }
    }

    override fun onItemClick(model: APPModel) {
        coroutineScope.launch(Dispatchers.IO) {
            // Toggle blacklist state
            if (model.pkgName in blackList) {
                blackList.remove(model.pkgName)
            } else {
                blackList.add(model.pkgName)
            }

            // Reload and re-sort entire list
            loadApps()

            withContext(Dispatchers.Main) {
                blackListSelectAPPCallBack.selectCompleted(blackList)
            }
        }
    }

    interface BlackListSelectAPPCallBack {
        fun selectCompleted(blackList: List<String>) {}
    }
}
