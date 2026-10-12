package io.github.lumiagg.gestureevo.settings.ui

import io.github.lumiagg.gestureevo.databinding.BottomSheetDefaultLinkOpenBinding

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drake.brv.BindingAdapter
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.MoshiUtils
import io.github.lumiagg.gestureevo.utils.app.appDisplayNameFromDatabase
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

data class DomainAppModel(
    val domain: String,
    val packageName: String,
    val appName: String,
    val appIcon: Drawable?
)

object LinkOpenDomainUtils {
    fun normalizeDomainInput(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        return try {
            val host = URL(normalizeUrlForParsing(trimmed)).host.orEmpty()
            normalizeHost(host)
        } catch (_: Exception) {
            null
        }
    }

    fun matches(url: String, domain: String): Boolean {
        val host = extractHost(url) ?: return false
        return host == domain || host.endsWith(".$domain")
    }

    private fun extractHost(url: String): String? {
        return try {
            normalizeHost(URL(normalizeUrlForParsing(url)).host.orEmpty())
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeUrlForParsing(raw: String): String {
        return when {
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> raw

            raw.startsWith("//") -> "https:$raw"
            else -> "https://$raw"
        }
    }

    private fun normalizeHost(host: String): String? {
        val normalized = host.trim().trim('.').lowercase().removePrefix("www.")
        return normalized.takeIf { it.contains('.') && !it.contains(' ') }
    }
}

interface DefaultLinkOpenCallBack {
    suspend fun onDomainAppListChanged(domainAppList: List<DomainAppModel>) {}
}

class DefaultLinkOpenBottomSheet(
    private val activity: Activity,
    private val coroutineScope: LifecycleCoroutineScope,
    private val defaultLinkOpenCallBack: DefaultLinkOpenCallBack,
    private val domainAppList: MutableList<DomainAppModel> = mutableListOf()
) {

    private lateinit var bottomSheetDialog: BottomSheetDialog
    private lateinit var recyclerView: RecyclerView
    private lateinit var addButton: View

    fun show() {
        setupBottomSheet()
        setupRecyclerView()
        refreshList()
        bottomSheetDialog.show()
    }

    private fun setupBottomSheet() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        bottomSheetDialog = BottomSheetDialog(themedContext)
        val view =
            BottomSheetDefaultLinkOpenBinding.inflate(LayoutInflater.from(themedContext)).root
        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.setOnShowListener { dialog ->
            DynamicColorUtils.applyBottomSheetSurface(dialog as BottomSheetDialog)
        }

        recyclerView = view.findViewById(R.id.recyclerView)
        addButton = view.findViewById(R.id.addButton)

        view.findViewById<MaterialTextView>(R.id.title)?.text =
            activity.getString(R.string.link_open_default_title)
        view.findViewById<MaterialTextView>(R.id.description)?.text =
            activity.getString(R.string.link_open_description)

        addButton.setOnClickListener {
            showDomainInputDialog()
        }
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.setup {
            addType<DomainAppModel>(R.layout.item_domain_app)
            onBind {
                val domainApp = getModel<DomainAppModel>()
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@onBind

                findView<MaterialTextView>(R.id.domain).text = domainApp.domain
                findView<MaterialTextView>(R.id.appName).text =
                    activity.getString(R.string.link_open_app_summary, domainApp.appName)
                findView<ShapeableImageView>(R.id.appIcon).setImageDrawable(domainApp.appIcon)

                findView<MaterialButton>(R.id.editButton).apply {
                    visibility = View.VISIBLE
                    icon = ContextCompat.getDrawable(activity, R.drawable.baseline_edit_24)
                    contentDescription = activity.getString(R.string.link_open_action_edit_domain)
                    setOnClickListener {
                        showDomainInputDialog(domainApp, position)
                    }
                }

                findView<MaterialButton>(R.id.deleteButton).apply {
                    visibility = View.VISIBLE
                    icon = ContextCompat.getDrawable(activity, R.drawable.ic_delete)
                    contentDescription = activity.getString(R.string.link_open_action_delete)
                    setOnClickListener {
                        showDeleteConfirmDialog(domainApp)
                    }
                }

                itemView.setOnClickListener {
                    showSelectAppDialog(domainApp.domain, domainApp, position)
                }
            }
        }
    }

    private fun refreshList() {
        domainAppList.sortBy { it.domain.lowercase() }
        recyclerView.models = domainAppList
    }

    private fun showDomainInputDialog(domainApp: DomainAppModel? = null, position: Int = -1) {
        val editText = EditText(activity).apply {
            hint = activity.getString(R.string.link_open_domain_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(domainApp?.domain.orEmpty())
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(
                if (domainApp == null) {
                    R.string.link_open_add_domain_title
                } else {
                    R.string.link_open_edit_domain_title
                }
            )
            .setView(editText)
            .setPositiveButton(R.string.link_open_next_step) { _, _ ->
                val normalizedDomain = LinkOpenDomainUtils.normalizeDomainInput(
                    editText.text?.toString().orEmpty()
                )

                when {
                    normalizedDomain == null -> showInvalidDomainDialog()
                    isDomainExists(normalizedDomain, position) -> showDomainExistsDialog()
                    domainApp != null && position >= 0 -> {
                        domainAppList[position] = domainApp.copy(domain = normalizedDomain)
                        persistAndRefresh()
                    }

                    else -> showSelectAppDialog(normalizedDomain)
                }
            }
            .setNegativeButton(R.string.link_open_cancel, null)
            .show()
    }

    private fun showSelectAppDialog(
        domain: String,
        originalDomainApp: DomainAppModel? = null,
        position: Int = -1
    ) {
        LinkHandlerSelectAPPBottomSheet(
            activity = activity,
            coroutineScope = coroutineScope,
            selectAPPCallBack = object : SelectAPPCallBack {
                override fun selectCompleted(model: APPModel) {
                    coroutineScope.launch(Dispatchers.IO) {
                        val updatedDomainApp = DomainAppModel(
                            domain = domain,
                            packageName = model.pkgName,
                            appName = model.name,
                            appIcon = IconHelper.getIcon(activity, model.pkgName)
                        )

                        withContext(Dispatchers.Main) {
                            if (originalDomainApp != null && position in domainAppList.indices) {
                                domainAppList[position] = updatedDomainApp
                            } else {
                                domainAppList.add(updatedDomainApp)
                            }
                            persistAndRefresh()
                        }
                    }
                }
            }
        ).show()
    }

    private fun showDeleteConfirmDialog(domainApp: DomainAppModel) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.link_open_delete_title)
            .setMessage(activity.getString(R.string.link_open_delete_message, domainApp.domain))
            .setPositiveButton(R.string.link_open_action_delete) { _, _ ->
                domainAppList.removeAll { it.domain == domainApp.domain }
                persistAndRefresh()
            }
            .setNegativeButton(R.string.link_open_cancel, null)
            .show()
    }

    private fun showDomainExistsDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.link_open_domain_exists_title)
            .setMessage(R.string.link_open_domain_exists_message)
            .setPositiveButton(R.string.link_open_ok, null)
            .show()
    }

    private fun showInvalidDomainDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.link_open_invalid_domain_title)
            .setMessage(R.string.link_open_invalid_domain_message)
            .setPositiveButton(R.string.link_open_ok, null)
            .show()
    }

    private fun persistAndRefresh() {
        refreshList()
        coroutineScope.launch {
            defaultLinkOpenCallBack.onDomainAppListChanged(domainAppList)
        }
    }

    private fun isDomainExists(domain: String, excludePosition: Int = -1): Boolean {
        return domainAppList.withIndex().any { (index, item) ->
            index != excludePosition && item.domain.equals(domain, ignoreCase = true)
        }
    }

    fun dismiss() {
        if (::bottomSheetDialog.isInitialized) {
            bottomSheetDialog.dismiss()
        }
    }
}

@JsonClass(generateAdapter = true)
data class StoreDomainApp(
    val pkg: String,
    val domain: String
)

class DomainAppManager(private val context: Context) {
    companion object {
        private const val DOMAIN_APP_LIST = "domain_app_list_simple"
    }

    suspend fun saveDomainAppList(domainAppList: List<DomainAppModel>) {
        val storeList = domainAppList.map {
            StoreDomainApp(pkg = it.packageName, domain = it.domain)
        }
        context.settingsStore.saveKey(DOMAIN_APP_LIST, MoshiUtils.toJson(storeList))
    }

    suspend fun loadDomainAppList(): MutableList<DomainAppModel> {
        val json = context.settingsStore.readKey(DOMAIN_APP_LIST, "[]")
        val storeList = MoshiUtils.toModel<List<StoreDomainApp>>(json).orEmpty()
        return storeList.mapNotNull { storeDomainApp ->
            val normalizedDomain = LinkOpenDomainUtils.normalizeDomainInput(storeDomainApp.domain)
                ?: return@mapNotNull null
            DomainAppModel(
                domain = normalizedDomain,
                packageName = storeDomainApp.pkg,
                appName = appDisplayNameFromDatabase(context, storeDomainApp.pkg).orEmpty(),
                appIcon = IconHelper.getIcon(context, storeDomainApp.pkg)
            )
        }.toMutableList()
    }

    suspend fun findMatchingDomainApp(originalUrl: String): DomainAppModel? {
        return loadDomainAppList()
            .filter { LinkOpenDomainUtils.matches(originalUrl, it.domain) }
            .maxByOrNull { it.domain.length }
    }

    suspend fun processLinkRedirect(originalUrl: String): Pair<String, String> {
        val matchingDomainApp = findMatchingDomainApp(originalUrl) ?: return "" to originalUrl
        return matchingDomainApp.packageName to originalUrl
    }
}

class DefaultLinkOpenManager(
    private val activity: Activity,
    private val coroutineScope: LifecycleCoroutineScope
) {
    private val domainAppManager = DomainAppManager(activity)

    suspend fun showDefaultLinkOpenSettings() {
        val domainAppList = domainAppManager.loadDomainAppList()

        DefaultLinkOpenBottomSheet(
            activity = activity,
            coroutineScope = coroutineScope,
            defaultLinkOpenCallBack = object : DefaultLinkOpenCallBack {
                override suspend fun onDomainAppListChanged(domainAppList: List<DomainAppModel>) {
                    domainAppManager.saveDomainAppList(domainAppList)
                }
            },
            domainAppList = domainAppList
        ).show()
    }
}

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
