package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.BindingAdapter
import com.drake.brv.utils.grid
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
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
    protected val selectAPPCallBack: SelectAPPCallBack
) {
    protected lateinit var bottomSheetDialog: BottomSheetDialog
    protected lateinit var recyclerView: RecyclerView
    protected var fullAppList: List<APPModel> = listOf()
    protected var searchJob: Job? = null
    protected var bindingAdapter: BindingAdapter? = null
    protected var appFlags = APPModel.FLAG_USER

    open fun show() {
        initBottomSheet()
        setupBottomSheetBehavior()
        setupRecyclerView()
        setupSearch()
        setupFilter()
        loadApps()
        showDialog()
    }

    protected open fun initBottomSheet() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        bottomSheetDialog =
            BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        bottomSheetDialog.setContentView(R.layout.app_select_bottomsheet)
        bottomSheetDialog.setCanceledOnTouchOutside(false)
    }

    protected open fun setupBottomSheetBehavior() {
        bottomSheetDialog.setOnShowListener { dialog ->
            val bottomSheet = (dialog as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as FrameLayout?

            bottomSheet?.let { sheet ->
                DynamicColorUtils.applyBottomSheetSurface(dialog)
                val windowHeight = activity.window.decorView.height
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.apply {
                    sheet.layoutParams.height = windowHeight
                    state = BottomSheetBehavior.STATE_EXPANDED
                    isDraggable = true
                    skipCollapsed = true
                    isFitToContents = true
                    isHideable = true
                }
            }
        }
    }

    protected open fun setupRecyclerView() {
        recyclerView = bottomSheetDialog.findViewById(R.id.appList)!!
        recyclerView.grid(spanCount = getGridSpanCount())
        bindingAdapter = recyclerView.setup {
            addType<APPModel>(getItemLayoutId())
            onBind {
                onBindItem(this)
            }
            onClick(R.id.appItem) {
                onItemClick(getModel())
            }
            customizeRecyclerView(this)
        }
    }

    protected open fun setupFilter() {
        bottomSheetDialog.findViewById<ChipGroup>(R.id.filterChipGroup)?.apply {
            setOnCheckedStateChangeListener { group, checkedIds ->
                checkedIds.firstOrNull()?.let { checkedId ->
                    when (checkedId) {
                        R.id.chipSystem -> appFlags = APPModel.FLAG_SYSTEM
                        R.id.chipUser -> appFlags = APPModel.FLAG_USER
                    }
                }
                loadApps()
            }
        }
    }

    protected open fun setupSearch() {
        bottomSheetDialog.findViewById<TextInputEditText>(R.id.searchInput)
            ?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchJob?.cancel()
                    searchJob = coroutineScope.launch {
                        delay(300)
                        val searchText = s?.toString()?.trim()?.lowercase() ?: ""
                        if (searchText.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                recyclerView.models = fullAppList
                            }
                            return@launch
                        }
                        withContext(Dispatchers.Default) {
                            val filteredList = filterApps(searchText)
                            withContext(Dispatchers.Main) {
                                recyclerView.models = filteredList
                            }
                        }
                    }
                }
            })
    }

    protected open fun loadApps() {
        coroutineScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                bottomSheetDialog.findViewById<LinearProgressIndicator>(R.id.loadingProgress)?.visibility =
                    View.VISIBLE
            }
            fullAppList = getAPPDao().getAll(getAppFlag())
            val currentSearchText = bottomSheetDialog.findViewById<TextInputEditText>(R.id.searchInput)
                ?.text
                ?.toString()
                ?.trim()
                ?.lowercase()
                .orEmpty()
            val displayList = if (currentSearchText.isEmpty()) {
                fullAppList
            } else {
                filterApps(currentSearchText)
            }
            withContext(Dispatchers.Main) {
                recyclerView.models = displayList
                bottomSheetDialog.findViewById<LinearProgressIndicator>(R.id.loadingProgress)?.visibility =
                    View.INVISIBLE
            }
        }
    }

    protected open fun showDialog() {
        bottomSheetDialog.show()
        bottomSheetDialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    // Required methods to implement
    protected abstract fun onBindItem(holder: BindingAdapter.BindingViewHolder)

    // Optional methods to override
    protected open fun getGridSpanCount(): Int = 3
    protected open fun getItemLayoutId(): Int = R.layout.app_select_list_item
    protected open fun getAppFlag(): Int = appFlags
    protected open fun onItemClick(model: APPModel) {
        selectAPPCallBack.selectCompleted(model)
        bottomSheetDialog.dismiss()
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

    protected open fun customizeRecyclerView(adapter: BindingAdapter) {}
}
