package io.github.lumiagg.gestureevo.settings.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
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
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.uriBuilder
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

@JsonClass(generateAdapter = true)
data class ShortcutResponse(
    @Json(name = "name") val name: String,
    @Json(name = "pkg") val packageName: String,
    @Json(name = "uploadedBy") val uploadedBy: String,
    @Json(name = "scheme") val scheme: String,
    @Json(name = "introduction") val introduction: String
)

data class ShortcutModel(
    val name: String,
    val packageName: String,
    val uploadedBy: String,
    var scheme: String,
    val introduction: String,
    var appName: String = "",
    var installed: Boolean = false
)

interface SelectShortcutCallback {
    fun selectCompleted(model: ShortcutModel) {}
    fun onError(message: String) {}
}

class LocalUserShortcutSheet(
    private val activity: AppCompatActivity,
    private val coroutineScope: LifecycleCoroutineScope,
    private val selectShortcutCallback: SelectShortcutCallback
) {
    private lateinit var bottomSheetDialog: BottomSheetDialog
    private lateinit var recyclerView: RecyclerView
    private var fullShortcutList: List<ShortcutModel> = listOf()
    private var searchJob: Job? = null
    private var bindingAdapter: BindingAdapter? = null

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    companion object {
        private const val LOCAL_SHORTCUTS_ASSET = "shortcuts/other_user_shortcuts.json"
    }

    fun show() {
        initBottomSheet()
        setupBottomSheetBehavior()
        setupRecyclerView()
        setupSearch()
        loadShortcuts()
        showDialog()
    }

    private fun initBottomSheet() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        bottomSheetDialog =
            BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        bottomSheetDialog.setContentView(R.layout.app_select_bottomsheet)
        bottomSheetDialog.setCanceledOnTouchOutside(false)

        // Hide filter ChipGroup
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
            addType<ShortcutModel>(R.layout.shortcut_select_list_item)
            onBind {
                val model = getModel<ShortcutModel>()

                // Basic info
                findView<ImageView>(R.id.appIcon).setImageDrawable(
                    IconHelper.getIcon(
                        activity,
                        model.packageName
                    )
                )
                findView<TextView>(R.id.appName).text = model.appName
                findView<Chip>(R.id.uploadInfo).text =
                    activity.getString(R.string.shortcut_added_by, model.uploadedBy)

                // Shortcut details
                findView<TextView>(R.id.shortcutName).text = model.name
                findView<TextView>(R.id.shortcutDescription).apply {
                    text = model.introduction
                    visibility = if (model.introduction.isNotEmpty()) View.VISIBLE else View.GONE
                }
                findView<TextView>(R.id.shortcutScheme).text = model.scheme
            }
            onClick(R.id.previewButton) {
                val model = getModel<ShortcutModel>()
                val link = uriBuilder(GestureActionType.OTHER_SHORTCUT)
                    .appendQueryParameter("pkg", model.packageName)
                    .appendQueryParameter("uri", model.scheme)
                    .appendQueryParameter("newWindow", true.toString())
                    .build()
                activity.lifecycleScope.launch {
                    ActionProxy(activity, link.toString()).execute()
                }
            }
            onClick(R.id.installButton) {
                val model = getModel<ShortcutModel>()
                selectShortcutCallback.selectCompleted(model)
                bottomSheetDialog.dismiss()
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
                                recyclerView.models = fullShortcutList
                            }
                            return@launch
                        }
                        withContext(Dispatchers.Default) {
                            val filteredList = filterShortcuts(searchText)
                            withContext(Dispatchers.Main) {
                                recyclerView.models = filteredList
                            }
                        }
                    }
                }
            })
    }

    private suspend fun loadLocalShortcutResponses(): List<ShortcutResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val jsonString = activity.assets.open(LOCAL_SHORTCUTS_ASSET).bufferedReader().use {
                    it.readText()
                }
                val adapter = moshi.adapter<List<ShortcutResponse>>(
                    Types.newParameterizedType(List::class.java, ShortcutResponse::class.java)
                )
                adapter.fromJson(jsonString) ?: emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
                selectShortcutCallback.onError(
                    activity.getString(
                        R.string.shortcut_load_failed,
                        e.message ?: ""
                    )
                )
                emptyList()
            }
        }
    }

    private fun loadShortcuts() {
        coroutineScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                bottomSheetDialog.findViewById<LinearProgressIndicator>(R.id.loadingProgress)?.visibility =
                    View.VISIBLE
            }
            try {
                val responses = loadLocalShortcutResponses()
                val shortcuts = responses.map { response -> convertToModel(response) }
                enrichShortcutInfo(shortcuts)
                val installedShortcuts = shortcuts.filter { it.installed }
                fullShortcutList = installedShortcuts
                withContext(Dispatchers.Main) {
                    recyclerView.models = installedShortcuts
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    selectShortcutCallback.onError(
                        activity.getString(
                            R.string.shortcut_load_failed,
                            e.message ?: ""
                        )
                    )
                }
            } finally {
                withContext(Dispatchers.Main) {
                    bottomSheetDialog.findViewById<LinearProgressIndicator>(R.id.loadingProgress)?.visibility =
                        View.INVISIBLE
                }
            }
        }
    }

    private fun convertToModel(response: ShortcutResponse) = ShortcutModel(
        name = response.name,
        packageName = response.packageName,
        uploadedBy = response.uploadedBy,
        scheme = response.scheme,
        introduction = response.introduction
    )

    private fun parUri(scheme: String): String {
        val uri = scheme.toUri()
        uri.getQueryParameter("url")?.let { encodedUrl ->
            try {
                val decodedUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                return decodedUrl
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return ""
    }

    private suspend fun enrichShortcutInfo(shortcuts: List<ShortcutModel>) {
        shortcuts.forEach { shortcut ->
            val appModel = getAPPDao().findByPkg(shortcut.packageName)
            shortcut.appName = appModel?.name ?: shortcut.packageName
            shortcut.installed = appModel != null
            shortcut.scheme = parUri(shortcut.scheme)
        }
    }

    private fun filterShortcuts(searchText: String): List<ShortcutModel> {
        return fullShortcutList.filter { shortcut ->
            shortcut.appName.lowercase().contains(searchText) ||
                    shortcut.name.lowercase().contains(searchText) ||
                    shortcut.introduction.lowercase().contains(searchText)
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
