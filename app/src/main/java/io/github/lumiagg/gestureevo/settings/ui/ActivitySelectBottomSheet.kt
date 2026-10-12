package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.BindingAdapter
import com.drake.brv.utils.grid
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ActivityModel(
    val packageName: String,
    val activityName: String,
    val label: String,
    val appLabel: String,
    var isExported: Boolean
)

interface SelectActivityCallBack {
    fun selectCompleted(model: ActivityModel)
}

/**
 * Activity filter interface.
 * Returns a set of matching full Activity names for fast lookup.
 */
typealias ActivityFilter = (packageManager: PackageManager, targetPackage: String) -> Set<String>

class ActivitySelectBottomSheet(
    private val activity: Activity,
    private val packageName: String,
    private val coroutineScope: LifecycleCoroutineScope,
    private val selectActivityCallBack: SelectActivityCallBack,
    private val activityFilter: ActivityFilter? = null
) {
    private lateinit var bottomSheetDialog: BottomSheetDialog
    private lateinit var recyclerView: RecyclerView
    private var fullActivityList: List<ActivityModel> = listOf()
    private var searchJob: Job? = null
    private var bindingAdapter: BindingAdapter? = null

    fun show() {
        initBottomSheet()
        setupBottomSheetBehavior()
        setupRecyclerView()
        setupSearch()
        loadActivities()
        showDialog()
    }

    private fun initBottomSheet() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        bottomSheetDialog =
            BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        bottomSheetDialog.setContentView(R.layout.app_select_bottomsheet)
        bottomSheetDialog.setCanceledOnTouchOutside(false)

        bottomSheetDialog.findViewById<View>(R.id.filterChipGroup)?.visibility = View.GONE
    }

    private fun setupBottomSheetBehavior() {
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

    private fun setupRecyclerView() {
        recyclerView = bottomSheetDialog.findViewById(R.id.appList)!!
        recyclerView.grid(spanCount = 1)
        bindingAdapter = recyclerView.setup {
            addType<ActivityModel>(R.layout.app_activity_select_list_item)
            onBind {
                val model = getModel<ActivityModel>()
                findView<TextView>(R.id.activityLabel).text = model.label
                findView<TextView>(R.id.activityName).text =
                    activity.getString(R.string.app_activity_label, model.appLabel, model.activityName)
                findView<Chip>(R.id.activityIsExport).apply {
                    text = if (model.isExported) {
                        activity.getString(R.string.activity_exported_true)
                    } else {
                        activity.getString(R.string.activity_exported_false)
                    }
                    isEnabled = model.isExported
                }
            }
            onClick(R.id.appItem) {
                val model = getModel<ActivityModel>()
                selectActivityCallBack.selectCompleted(model)
                bottomSheetDialog.dismiss()
            }
            onLongClick(R.id.activityIsExport) {
                val model = getModel<ActivityModel>()
                model.isExported = !model.isExported
                // Notify current item refresh
                notifyItemChanged(bindingAdapterPosition)
            }

        }
    }

    private fun setupSearch() {
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
                                recyclerView.models = fullActivityList
                            }
                            return@launch
                        }
                        withContext(Dispatchers.Default) {
                            val filteredList = filterActivities(searchText)
                            withContext(Dispatchers.Main) {
                                recyclerView.models = filteredList
                            }
                        }
                    }
                }
            })
    }

    private fun loadActivities() {
        coroutineScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                bottomSheetDialog.findViewById<LinearProgressIndicator>(R.id.loadingProgress)?.visibility =
                    View.VISIBLE
            }
            val pm = activity.packageManager
            try {
                fullActivityList = if (activityFilter != null) {
                    loadFilteredActivities(pm)
                } else {
                    loadPackageActivities(pm)
                }

                withContext(Dispatchers.Main) {
                    recyclerView.models = fullActivityList
                }
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    bottomSheetDialog.findViewById<LinearProgressIndicator>(R.id.loadingProgress)?.visibility =
                        View.INVISIBLE
                }
            }
        }
    }

    private fun loadFilteredActivities(pm: PackageManager): List<ActivityModel> {
        val allowedActivities = activityFilter?.invoke(pm, packageName).orEmpty()
        if (allowedActivities.isEmpty()) return emptyList()

        val intents = listOf(
            Intent(Intent.ACTION_SEND).apply { type = "text/plain" },
            Intent(Intent.ACTION_SEND).apply { type = "image/*" },
            Intent(Intent.ACTION_VIEW)
        )

        val resolveList = LinkedHashMap<String, ActivityModel>()
        intents.forEach { intent ->
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .filter { it.activityInfo?.packageName == packageName }
                .filter { allowedActivities.contains(it.activityInfo?.name) }
                .forEach { resolveInfo ->
                    val model = createActivityModel(resolveInfo, pm)
                    resolveList[model.activityName] = model
                }
        }

        if (resolveList.isNotEmpty()) {
            return resolveList.values.sortedBy { !it.isExported }
        }

        return loadPackageActivities(pm).filter { allowedActivities.contains(it.activityName) }
    }

    private fun loadPackageActivities(pm: PackageManager): List<ActivityModel> {
        val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
        return packageInfo.activities?.map { activityInfo ->
            createActivityModel(activityInfo, pm)
        }?.sortedBy { !it.isExported } ?: emptyList()
    }

    private fun createActivityModel(activityInfo: ActivityInfo, pm: PackageManager): ActivityModel {
        val label = try {
            activityInfo.loadLabel(pm).toString()
        } catch (e: Exception) {
            activityInfo.name
        }
        val appLabel = try {
            activityInfo.applicationInfo.loadLabel(pm).toString()
        } catch (e: Exception) {
            activityInfo.packageName
        }

        return ActivityModel(
            packageName = activityInfo.packageName,
            activityName = activityInfo.name,
            label = label,
            appLabel = appLabel,
            isExported = activityInfo.exported
        )
    }

    private fun createActivityModel(resolveInfo: ResolveInfo, pm: PackageManager): ActivityModel {
        val activityInfo = resolveInfo.activityInfo
        val label = try {
            resolveInfo.loadLabel(pm)?.toString().orEmpty().ifEmpty {
                activityInfo.loadLabel(pm).toString()
            }
        } catch (e: Exception) {
            activityInfo.name
        }
        val appLabel = try {
            activityInfo.applicationInfo.loadLabel(pm).toString()
        } catch (e: Exception) {
            activityInfo.packageName
        }

        return ActivityModel(
            packageName = activityInfo.packageName,
            activityName = activityInfo.name,
            label = label,
            appLabel = appLabel,
            isExported = activityInfo.exported
        )
    }

    private fun filterActivities(searchText: String): List<ActivityModel> {
        return fullActivityList.filter { activity ->
            activity.label.lowercase().contains(searchText) ||
                    activity.appLabel.lowercase().contains(searchText) ||
                    activity.activityName.lowercase().contains(searchText)
        }
    }

    private fun showDialog() {
        bottomSheetDialog.show()
        bottomSheetDialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}

// ============ Predefined Filters ============

object ActivityFilters {

    /**
     * Filters activities supporting text sharing.
     * Queries all activities capable of handling ACTION_SEND + text/plain.
     */
    fun textShareFilter(): ActivityFilter = { pm, targetPackage ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
        }
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName == targetPackage }
            .map { it.name }
            .toSet()
    }

    fun imageShareFilter(): ActivityFilter = { pm, targetPackage ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
        }
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName == targetPackage }
            .map { it.name }
            .toSet()
    }

    /**
     * Filters activities supporting specific Action and Type.
     */
    fun intentFilter(
        action: String,
        type: String? = null,
        data: android.net.Uri? = null
    ): ActivityFilter = { pm, targetPackage ->
        val intent = Intent(action).apply {
            type?.let { this.type = it }
            data?.let { this.data = it }
        }
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName == targetPackage }
            .map { it.name }
            .toSet()
    }

    /**
     * Filters activities supporting viewing specific MIME types.
     */
    fun viewTypeFilter(mimeType: String): ActivityFilter = { pm, targetPackage ->
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = mimeType
        }
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName == targetPackage }
            .map { it.name }
            .toSet()
    }

    /**
     * Filters activities supporting specific URL schemes.
     */
    fun urlSchemeFilter(scheme: String, host: String? = null): ActivityFilter =
        { pm, targetPackage ->
            val uriBuilder = android.net.Uri.Builder().scheme(scheme)
            host?.let { uriBuilder.authority(it) }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = uriBuilder.build()
            }
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo }
                .filter { it.packageName == targetPackage }
                .map { it.name }
                .toSet()
        }

    /**
     * Filters only exported activities.
     */
    fun exportedOnlyFilter(): ActivityFilter = { pm, targetPackage ->
        try {
            val packageInfo = pm.getPackageInfo(targetPackage, PackageManager.GET_ACTIVITIES)
            packageInfo.activities?.filter { it.exported }?.map { it.name }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /**
     * Custom name matching filter.
     */
    fun nameContainsFilter(keyword: String, ignoreCase: Boolean = true): ActivityFilter =
        { pm, targetPackage ->
            try {
                val packageInfo = pm.getPackageInfo(targetPackage, PackageManager.GET_ACTIVITIES)
                packageInfo.activities?.filter {
                    it.name.contains(keyword, ignoreCase)
                }?.map { it.name }?.toSet() ?: emptySet()
            } catch (e: Exception) {
                emptySet()
            }
        }

    /**
     * Combines multiple filters (intersection).
     */
    fun intersect(vararg filters: ActivityFilter): ActivityFilter = { pm, targetPackage ->
        if (filters.isEmpty()) {
            emptySet()
        } else {
            filters.map { it(pm, targetPackage) }
                .reduce { acc, set -> acc.intersect(set) }
        }
    }

    /**
     * Combines multiple filters (union).
     */
    fun union(vararg filters: ActivityFilter): ActivityFilter = { pm, targetPackage ->
        filters.flatMap { it(pm, targetPackage) }.toSet()
    }
}
