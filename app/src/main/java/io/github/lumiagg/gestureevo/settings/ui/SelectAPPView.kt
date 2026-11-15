package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.room.withTransaction
import com.drake.brv.BindingAdapter
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.DatabaseManager
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.utils.ToastHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DefaultSelectAPPBottomSheet(activity: Activity, coroutineScope: LifecycleCoroutineScope,
    selectAPPCallBack: SelectAPPCallBack) : BaseSelectAPPBottomSheet(activity, coroutineScope, selectAPPCallBack)

class ShortcutSelectAPPBottomSheet(activity: Activity, coroutineScope: LifecycleCoroutineScope,
    selectAPPCallBack: SelectAPPCallBack) : BaseSelectAPPBottomSheet(activity, coroutineScope, selectAPPCallBack) {
    override suspend fun queryApps(flag: Int): List<APPModel> = activity.packageManager
        .queryIntentActivities(Intent("android.intent.action.CREATE_SHORTCUT"), 0)
        .mapNotNull { getAPPDao().findByPkg(it.activityInfo.packageName) }
        .filter { it.getState(flag) }
        .distinctBy { it.pkgName to it.userId }
}

class SelectActivityBottomSheet(activity: Activity, coroutineScope: LifecycleCoroutineScope,
    private val selectActivityCallBack: SelectActivityCallBack,
    private val activityFilter: ActivityFilter? = null,
) : BaseSelectAPPBottomSheet(activity, coroutineScope, object : SelectAPPCallBack {}) {
    override suspend fun queryApps(flag: Int): List<APPModel> {
        val apps = getAPPDao().getAll(flag)
        val filter = activityFilter ?: return apps
        return apps.filter { filter(activity.packageManager, it.pkgName).isNotEmpty() }
    }

    override fun onItemClick(model: APPModel) {
        ActivitySelectBottomSheet(activity, model.pkgName, coroutineScope, object : SelectActivityCallBack {
            override fun selectCompleted(model: ActivityModel) {
                selectActivityCallBack.selectCompleted(model)
                bottomSheetDialog.dismiss()
            }
        }, activityFilter).show()
    }
}

/** A switch represents membership in the named list; bulk edits affect only visible search results. */
abstract class MultiSelectAPPBottomSheet(activity: Activity, coroutineScope: LifecycleCoroutineScope,
    selectAPPCallBack: SelectAPPCallBack, private val whitelist: Boolean,
) : BaseSelectAPPBottomSheet(activity, coroutineScope, selectAPPCallBack) {
    protected abstract fun selected(model: APPModel): Boolean
    protected abstract suspend fun saveSelection(apps: List<APPModel>, enabled: Boolean)

    override fun setupSelection() {
        bottomSheetDialog.findViewById<TextView>(R.id.selectionTitle)?.setText(
            if (whitelist) R.string.app_select_whitelist_title else R.string.app_select_blacklist_title)
        bottomSheetDialog.findViewById<TextView>(R.id.selectionTitle)?.let { title ->
            TooltipCompat.setTooltipText(title, activity.getString(
                if (whitelist) R.string.app_select_whitelist_hint else R.string.app_select_blacklist_hint))
        }
        bottomSheetDialog.findViewById<View>(R.id.selectionCount)?.visibility = View.VISIBLE
        bottomSheetDialog.findViewById<View>(R.id.selectionActions)?.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                val apps = visibleApps.toList()
                val count = apps.count(::selected)
                PopupMenu(bottomSheetDialog.context, this).apply {
                    menu.add(0, 1, 0, R.string.app_select_enable_all).isEnabled = canInteract && count < apps.size
                    menu.add(0, 2, 1, R.string.app_select_disable_all).isEnabled = canInteract && count > 0
                    setOnMenuItemClickListener {
                        change(apps, it.itemId == 1)
                        true
                    }
                    show()
                }
            }
        }
    }

    override fun onAppsRendered() {
        val count = visibleApps.count(::selected)
        bottomSheetDialog.findViewById<TextView>(R.id.selectionCount)?.text =
            activity.getString(R.string.app_select_enabled_count, count, visibleApps.size)
        bottomSheetDialog.findViewById<View>(R.id.selectionActions)?.isEnabled = canInteract && visibleApps.isNotEmpty()
    }

    override fun onBindItem(holder: BindingAdapter.BindingViewHolder) {
        super.onBindItem(holder)
        val app = holder.getModel<APPModel>()
        holder.findView<MaterialSwitch>(R.id.appEnabledSwitch).apply {
            visibility = View.VISIBLE
            isChecked = selected(app)
            isEnabled = canInteract
            contentDescription = app.name
            setOnCheckedChangeListener { _, enabled -> change(listOf(app), enabled) }
        }
    }

    override fun onItemClick(model: APPModel) = change(listOf(model), !selected(model))

    private fun change(apps: List<APPModel>, enabled: Boolean) {
        if (!canInteract) return
        val changed = apps.filter { selected(it) != enabled }
        if (changed.isEmpty()) return
        setEditing(true)
        coroutineScope.launch {
            try { saveSelection(changed, enabled) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ToastHelper.showInUIThread(activity, R.string.app_select_save_failed) }
            finally { setEditing(false) }
        }
    }
}

class BlackListSelectAPPBottomSheet(activity: Activity, coroutineScope: LifecycleCoroutineScope,
    selectAPPCallBack: SelectAPPCallBack,
    private val blackListFlag: Int = APPModel.FLAG_GESTURE_DISABLE,
) : MultiSelectAPPBottomSheet(activity, coroutineScope, selectAPPCallBack,
    whitelist = blackListFlag == APPModel.FLAG_NOTIFICATION_APPS_ENABLE) {
    override fun selected(model: APPModel): Boolean = model.getState(blackListFlag)

    override suspend fun queryApps(flag: Int): List<APPModel> =
        getAPPDao().getAll(flag).sortedByDescending(::selected)

    override suspend fun saveSelection(apps: List<APPModel>, enabled: Boolean) {
        // Update only this bit, preserving concurrent changes to the other application settings.
        withContext(Dispatchers.IO) {
            DatabaseManager.db.withTransaction {
                apps.map { it.id }.chunked(500).forEach { ids ->
                    getAPPDao().setSelectionFlag(ids, blackListFlag, enabled)
                }
            }
        }
        apps.forEach {
            it.setState(blackListFlag, enabled)
            selectAPPCallBack.selectCompleted(it)
        }
    }
}

class BlackListWithNoDBSelectAPPBottomSheet(activity: Activity, coroutineScope: LifecycleCoroutineScope,
    private val blackListSelectAPPCallBack: BlackListSelectAPPCallBack,
    private val blackList: MutableList<String>,
    whitelist: Boolean = false,
    selectAPPCallBack: SelectAPPCallBack = object : SelectAPPCallBack {},
) : MultiSelectAPPBottomSheet(activity, coroutineScope, selectAPPCallBack, whitelist) {
    override fun selected(model: APPModel): Boolean = model.pkgName in blackList

    override suspend fun queryApps(flag: Int): List<APPModel> =
        getAPPDao().getAll(flag).sortedByDescending(::selected)

    override suspend fun saveSelection(apps: List<APPModel>, enabled: Boolean) {
        val packages = apps.map { it.pkgName }.toSet()
        if (enabled) blackList.addAll(packages.filterNot { it in blackList })
        else blackList.removeAll(packages)
        blackListSelectAPPCallBack.selectCompleted(blackList.toList())
    }

    interface BlackListSelectAPPCallBack {
        fun selectCompleted(blackList: List<String>) {}
    }
}
