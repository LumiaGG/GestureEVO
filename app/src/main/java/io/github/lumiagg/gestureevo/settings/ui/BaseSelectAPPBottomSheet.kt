package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.BindingAdapter
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface SelectAPPCallBack {
    fun selectCompleted(model: APPModel) {}
}

abstract class BaseSelectAPPBottomSheet(
    protected val activity: Activity,
    protected val coroutineScope: LifecycleCoroutineScope,
    protected val selectAPPCallBack: SelectAPPCallBack,
) {
    protected lateinit var bottomSheetDialog: BottomSheetDialog
    protected lateinit var recyclerView: RecyclerView
    protected var fullAppList: List<APPModel> = emptyList()
    protected var visibleApps: List<APPModel> = emptyList()
    protected var bindingAdapter: BindingAdapter? = null
    protected var appFlags = APPModel.FLAG_USER
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var loadGeneration = 0
    private var loading = false
    private var editing = false
    private var dismissed = false
    protected val canInteract: Boolean get() = !loading && !editing

    open fun show() {
        initBottomSheet()
        setupBottomSheetBehavior()
        setupRecyclerView()
        setupSearch()
        setupFilter()
        setupSelection()
        loadApps()
        showDialog()
    }

    private fun initBottomSheet() {
        bottomSheetDialog = BottomSheetDialog(DynamicColorUtils.dynamicThemedContext(activity),
            R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        bottomSheetDialog.setContentView(R.layout.app_select_bottomsheet)
        bottomSheetDialog.setCanceledOnTouchOutside(false)
        dismissed = false
        val owner = activity as? LifecycleOwner
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { bottomSheetDialog.dismiss() }
        }
        owner?.lifecycle?.addObserver(observer)
        bottomSheetDialog.setOnDismissListener {
            dismissed = true
            searchJob?.cancel()
            loadJob?.cancel()
            owner?.lifecycle?.removeObserver(observer)
        }
    }

    private fun setupBottomSheetBehavior() {
        bottomSheetDialog.setOnShowListener { dialog ->
            val sheet = (dialog as BottomSheetDialog)
                .findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                DynamicColorUtils.applyBottomSheetSurface(dialog)
                it.layoutParams.height = activity.window.decorView.height
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    isDraggable = true
                    skipCollapsed = true
                    isFitToContents = true
                    isHideable = true
                }
            }
        }
    }

    private fun setupRecyclerView() {
        recyclerView = bottomSheetDialog.findViewById(R.id.appList)!!
        recyclerView.layoutManager = LinearLayoutManager(bottomSheetDialog.context)
        bindingAdapter = recyclerView.setup {
            addType<APPModel>(R.layout.app_select_list_item)
            onBind { onBindItem(this) }
            onClick(R.id.appItem) { if (canInteract) onItemClick(getModel()) }
        }
    }

    private fun setupFilter() {
        bottomSheetDialog.findViewById<ChipGroup>(R.id.filterChipGroup)?.setOnCheckedStateChangeListener { _, ids ->
            appFlags = if (ids.firstOrNull() == R.id.chipSystem) APPModel.FLAG_SYSTEM else APPModel.FLAG_USER
            loadApps()
        }
    }

    private fun setupSearch() {
        bottomSheetDialog.findViewById<TextInputEditText>(R.id.searchInput)?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = coroutineScope.launch {
                    delay(200)
                    renderApps()
                }
            }
        })
    }

    protected open suspend fun queryApps(flag: Int): List<APPModel> = getAPPDao().getAll(flag)

    private fun loadApps() {
        val request = ++loadGeneration
        val flag = appFlags
        loadJob?.cancel()
        loading = true
        renderApps()
        loadJob = coroutineScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) { queryApps(flag) }
                if (!dismissed && request == loadGeneration) fullAppList = apps
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ToastHelper.showInUIThread(activity, R.string.app_select_load_failed) }
            finally {
                if (!dismissed && request == loadGeneration) {
                    loading = false
                    renderApps()
                }
            }
        }
    }

    protected fun setEditing(value: Boolean) {
        editing = value
        renderApps()
    }

    protected fun renderApps() {
        if (dismissed) return
        val query = bottomSheetDialog.findViewById<TextInputEditText>(R.id.searchInput)
            ?.text?.toString()?.trim()?.lowercase().orEmpty()
        visibleApps = if (query.isBlank()) fullAppList else filterApps(query)
        recyclerView.models = visibleApps
        bottomSheetDialog.findViewById<LinearProgressIndicator>(R.id.loadingProgress)?.visibility =
            if (loading || editing) View.VISIBLE else View.INVISIBLE
        bottomSheetDialog.findViewById<View>(R.id.emptyAppList)?.visibility =
            if (!loading && visibleApps.isEmpty()) View.VISIBLE else View.GONE
        bottomSheetDialog.findViewById<ChipGroup>(R.id.filterChipGroup)?.children?.forEach { it.isEnabled = !editing }
        onAppsRendered()
    }

    protected open fun onBindItem(holder: BindingAdapter.BindingViewHolder) {
        val model = holder.getModel<APPModel>()
        holder.findView<TextView>(R.id.name).text = model.name
        holder.findView<TextView>(R.id.packageName).text = model.pkgName
        holder.findView<RoundImageTextView>(R.id.icon).setImage(IconHelper.getIcon(holder.context, model.pkgName))
        holder.findView<MaterialSwitch>(R.id.appEnabledSwitch).apply {
            setOnCheckedChangeListener(null)
            visibility = View.GONE
        }
    }

    protected open fun setupSelection() {}
    protected open fun onAppsRendered() {}
    protected open fun onItemClick(model: APPModel) {
        selectAPPCallBack.selectCompleted(model)
        bottomSheetDialog.dismiss()
    }

    private fun showDialog() {
        bottomSheetDialog.show()
        bottomSheetDialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    protected open fun filterApps(searchText: String): List<APPModel> {
        return fullAppList.filter { app ->
            app.name.lowercase().contains(searchText) ||
                    app.pkgName.lowercase().contains(searchText) ||
                    app.pinyinFK.lowercase().contains(searchText) ||
                    app.initialsFK.lowercase().contains(searchText) ||
                    app.pinyinDK.lowercase().contains(searchText) ||
                    app.initialsDK.lowercase().contains(searchText) ||
                    app.pinyinT9.contains(searchText) ||
                    app.initialsT9.contains(searchText)
        }
    }

}
