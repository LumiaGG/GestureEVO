package io.github.lumiagg.gestureevo.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.drake.brv.utils.bindingAdapter
import com.drake.brv.utils.linear
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivitySearchEngineSettingsBinding
import io.github.lumiagg.gestureevo.databinding.SearchEngineManageItemBinding
import io.github.lumiagg.gestureevo.databinding.HiddenSearchResultItemBinding
import io.github.lumiagg.gestureevo.db.HiddenSearchResultModel
import io.github.lumiagg.gestureevo.db.getHiddenSearchResultDao
import io.github.lumiagg.gestureevo.search.ContactSearchHelper
import io.github.lumiagg.gestureevo.search.QuickSearchActivity
import io.github.lumiagg.gestureevo.search.config.EngineType
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import io.github.lumiagg.gestureevo.search.config.SearchEngineDataManager
import io.github.lumiagg.gestureevo.search.setEngineIcon
import io.github.lumiagg.gestureevo.search.typeLabelRes
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchEngineSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySearchEngineSettingsBinding
    private val searchEngines = mutableListOf<SearchEngine>()
    private var defaultEngineTag: String? = null
    private var engineColumnCount = 5
    private var engineRowCount = 1 // Default row count
    private var showImageBadge = true

    companion object {
        const val KEY_ENGINE_SHOW_LABEL = "engine_show_label"
        const val KEY_ROW_COUNT = "engine_row_count"
        const val KEY_COLUMN_COUNT = "engine_column_count"
        const val KEY_BOTTOM_INPUT_LAYOUT = "search_bottom_input_layout"
        const val KEY_RESULT_DOUBLE_COLUMN = "search_result_double_column"
        const val KEY_KEYBOARD_FOLLOW_ANIMATION = "search_keyboard_follow_animation"
        const val KEY_SHOW_IMAGE_BADGE = "search_show_image_badge"
        const val KEY_SHOW_CLIPBOARD_CONTENT = "search_show_clipboard_content"
        const val KEY_SEARCH_HISTORY = "search_history"
        const val KEY_SEARCH_CONTACT = "search_contact"
        const val KEY_SEARCH_SETTINGS_SHORTCUTS = "search_settings_shortcuts"
        const val KEY_ENTER_FIRST_RESULT = "search_enter_first_result"
        const val KEY_KEEP_LAST_SEARCH_RESULT = "search_keep_last_search_result"
        const val KEY_DEFAULT_SEARCH_PAGE = "default_search_page"
        const val MIN_ROW_COUNT = 1
        const val MAX_ROW_COUNT = 5
        const val MIN_COLUMN_COUNT = 2
        const val MAX_COLUMN_COUNT = 8
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchEngineSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupDisplayControls()
        setupFunctionControls()
        setupDefaultSearchPageControl()
        setupButtons()
        setupRecyclerView()
        setupHiddenResults()
        loadSearchEngines()
        SettingsSearchNavigator.focusAnchorIfNeeded(binding.root, intent)
    }

    override fun onResume() {
        super.onResume()
        // Refresh list when returning from add/edit page
        loadSearchEngines()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        binding.toolbar.title = getString(R.string.search_engine_manage_title)
    }

    private fun setupDisplayControls() = lifecycleScope.launch {
        binding.engineLabelVisbilitySwitch.isChecked =
            settingsStore.readKey(KEY_ENGINE_SHOW_LABEL, true)
        binding.engineLabelVisbilitySwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_ENGINE_SHOW_LABEL, isChecked)
            }
        }

        binding.previewSortCard.setOnClickListener { openPreviewSortMode() }

        binding.searchBottomLayoutSwitch.isChecked =
            settingsStore.readKey(KEY_BOTTOM_INPUT_LAYOUT, false)
        binding.searchBottomLayoutSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_BOTTOM_INPUT_LAYOUT, isChecked)
            }
        }

        binding.resultDoubleColumnSwitch.isChecked =
            settingsStore.readKey(KEY_RESULT_DOUBLE_COLUMN, false)
        binding.resultDoubleColumnSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_RESULT_DOUBLE_COLUMN, isChecked)
            }
        }

        binding.keyboardFollowAnimationSwitch.isChecked =
            settingsStore.readKey(KEY_KEYBOARD_FOLLOW_ANIMATION, false)
        binding.keyboardFollowAnimationSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_KEYBOARD_FOLLOW_ANIMATION, isChecked)
            }
        }

        showImageBadge = settingsStore.readKey(KEY_SHOW_IMAGE_BADGE, true)
        binding.imageEngineBadgeSwitch.isChecked = showImageBadge
        binding.imageEngineBadgeSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                showImageBadge = isChecked
                settingsStore.saveKey(KEY_SHOW_IMAGE_BADGE, isChecked)
                binding.engineRecycler.bindingAdapter.let { it.notifyItemRangeChanged(0, it.itemCount) }
            }
        }

        engineRowCount = settingsStore.readKey(KEY_ROW_COUNT, 1)
            .coerceIn(MIN_ROW_COUNT, MAX_ROW_COUNT)
        engineColumnCount = settingsStore.readKey(KEY_COLUMN_COUNT, 5)
            .coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        // Display current row count
        updateGridCountDisplay()

        // Decrease row count
        binding.btnDecreaseRows.setOnClickListener {
            if (engineRowCount > MIN_ROW_COUNT) {
                lifecycleScope.launch {
                    engineRowCount -= 1
                    settingsStore.saveKey(KEY_ROW_COUNT, engineRowCount)
                    updateGridCountDisplay()
                }
            }
        }

        // Increase row count
        binding.btnIncreaseRows.setOnClickListener {
            if (engineRowCount < MAX_ROW_COUNT) {
                lifecycleScope.launch {
                    engineRowCount += 1
                    settingsStore.saveKey(KEY_ROW_COUNT, engineRowCount)
                    updateGridCountDisplay()
                }
            }
        }

        binding.btnDecreaseColumns.setOnClickListener {
            if (engineColumnCount > MIN_COLUMN_COUNT) {
                lifecycleScope.launch {
                    engineColumnCount -= 1
                    settingsStore.saveKey(KEY_COLUMN_COUNT, engineColumnCount)
                    updateGridCountDisplay()
                }
            }
        }

        binding.btnIncreaseColumns.setOnClickListener {
            if (engineColumnCount < MAX_COLUMN_COUNT) {
                lifecycleScope.launch {
                    engineColumnCount += 1
                    settingsStore.saveKey(KEY_COLUMN_COUNT, engineColumnCount)
                    updateGridCountDisplay()
                }
            }
        }
    }

    private fun openPreviewSortMode() {
        QuickSearchActivity.start(this, previewSortMode = true)
    }

    private fun setupFunctionControls() = lifecycleScope.launch {
        binding.searchClipboardContentSwitch.isChecked =
            settingsStore.readKey(KEY_SHOW_CLIPBOARD_CONTENT, true)
        binding.searchClipboardContentSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_SHOW_CLIPBOARD_CONTENT, isChecked)
            }
        }

        binding.searchHistorySwitch.isChecked =
            settingsStore.readKey(KEY_SEARCH_HISTORY, true)
        binding.searchHistorySwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_SEARCH_HISTORY, isChecked)
            }
        }

        binding.searchContactSwitch.isChecked = (settingsStore.readKey(KEY_SEARCH_CONTACT, false) &&
                ContactSearchHelper.hasContactPermission(applicationContext))
        binding.searchContactSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                if (isChecked && !ContactSearchHelper.hasContactPermission(applicationContext)) {
                    requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 1)
                    binding.searchContactSwitch.isChecked = false
                } else {
                    settingsStore.saveKey(KEY_SEARCH_CONTACT, isChecked)
                }
                if (isChecked) {
                    ContactSearchHelper.syncContactsToDatabase(applicationContext, force = true)
                }
            }
        }

        binding.searchSettingsShortcutsSwitch.isChecked =
            settingsStore.readKey(KEY_SEARCH_SETTINGS_SHORTCUTS, false)
        binding.searchSettingsShortcutsSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_SEARCH_SETTINGS_SHORTCUTS, isChecked)
            }
        }

        binding.searchEnterFirstResultSwitch.isChecked =
            settingsStore.readKey(KEY_ENTER_FIRST_RESULT, false)
        binding.searchEnterFirstResultSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_ENTER_FIRST_RESULT, isChecked)
            }
        }

        binding.searchKeepLastResultSwitch.isChecked =
            settingsStore.readKey(KEY_KEEP_LAST_SEARCH_RESULT, true)
        binding.searchKeepLastResultSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_KEEP_LAST_SEARCH_RESULT, isChecked)
            }
        }
    }

    private fun setupDefaultSearchPageControl() = lifecycleScope.launch {
        binding.defaultSearchPageSwitch.isChecked =
            settingsStore.readKey(KEY_DEFAULT_SEARCH_PAGE, false)
        binding.defaultSearchPageSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                lifecycleScope.launch {
                    settingsStore.saveKey(KEY_DEFAULT_SEARCH_PAGE, false)
                }
                return@setOnCheckedChangeListener
            }

            MaterialAlertDialogBuilder(this@SearchEngineSettingsActivity)
                .setTitle(R.string.search_engine_settings_default_search_page_dialog_title)
                .setMessage(R.string.search_engine_settings_default_search_page_dialog_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        settingsStore.saveKey(KEY_DEFAULT_SEARCH_PAGE, true)
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    binding.defaultSearchPageSwitch.isChecked = false
                }
                .setOnCancelListener {
                    binding.defaultSearchPageSwitch.isChecked = false
                }
                .show()
        }
    }

    private fun updateGridCountDisplay() {
        binding.tvRowCount.text = getString(R.string.display_integer, engineRowCount)
        binding.tvColumnCount.text = getString(R.string.display_integer, engineColumnCount)

        // Update button enabled states
        binding.btnDecreaseRows.isEnabled = engineRowCount > MIN_ROW_COUNT
        binding.btnDecreaseRows.isEnabled = engineRowCount > MIN_ROW_COUNT
        binding.btnIncreaseRows.isEnabled = engineRowCount < MAX_ROW_COUNT
        binding.btnDecreaseColumns.isEnabled = engineColumnCount > MIN_COLUMN_COUNT
        binding.btnIncreaseColumns.isEnabled = engineColumnCount < MAX_COLUMN_COUNT
    }

    private fun setupButtons() {
        binding.fabAdd.setOnClickListener {
            val intent = Intent(this, SearchEngineAddActivity::class.java)
            startActivity(intent)
        }

        binding.btnImport.setOnClickListener {
            showImportDialog()
        }

        binding.btnExport.setOnClickListener {
            exportSearchEngines()
        }
    }

    private fun setupRecyclerView() {
        binding.engineRecycler.linear().setup {
            addType<SearchEngine>(R.layout.search_engine_manage_item)

            onBind {
                val itemBinding = SearchEngineManageItemBinding.bind(itemView)
                val model = getModel<SearchEngine>()

                // Set engine name
                itemBinding.engineName.text = model.name

                // Set engine type tag
                itemBinding.engineType.text = when (model.engineType) {
                    EngineType.DIRECT_LINK -> getString(R.string.search_engine_type_direct_link)
                    EngineType.SHARE_TO_APP -> getString(R.string.search_engine_type_share)
                    EngineType.SHARE_IMAGE_TO_APP -> getString(R.string.search_engine_type_share_image)
                    EngineType.JUMP_TO_ACTIVITY -> getString(R.string.search_engine_type_jump_activity)
                    EngineType.EXTERN_JUMP_LINK -> getString(R.string.search_engine_type_extern_jump)
                }

                // Set default flag
                val isDefault = model.tag == defaultEngineTag
                itemBinding.defaultBadge.visibility = if (isDefault) View.VISIBLE else View.GONE
                // Set hidden flag
                itemBinding.hideBadge.visibility =
                    if (model.showInSearchEngineList) View.GONE else View.VISIBLE

                setEngineIcon(applicationContext, itemBinding.engineIcon, model, showImageBadge)
            }

            onClick(R.id.engine_container) {
                val engine = getModel<SearchEngine>()
                editSearchEngine(engine)
            }

            onLongClick(R.id.engine_container) {
        val engine = getModel<SearchEngine>()
        showEngineOptionsDialog(engine)
    }


            R.id.drag_handle.onFastClick {
                val engine = getModel<SearchEngine>()
                showEngineOptionsDialog(engine)
            }
        }
    }

    private fun setupHiddenResults() {
        binding.hiddenResultRecycler.linear().setup {
            addType<HiddenSearchResultModel>(R.layout.hidden_search_result_item)
            onBind {
                val itemBinding = HiddenSearchResultItemBinding.bind(itemView)
                val model = getModel<HiddenSearchResultModel>()
                itemBinding.resultName.text = model.displayName
                itemBinding.resultType.setText(model.typeLabelRes())
                itemBinding.resultIcon.clearTint()
                if (model.resultKey.startsWith("app:")) {
                    val packageName = model.resultKey.substringAfter(':').substringAfter(':')
                    itemBinding.resultIcon.setImage(IconHelper.getIcon(this@SearchEngineSettingsActivity, packageName))
                } else {
                    itemBinding.resultIcon.setText(model.displayName.take(1))
                }
                itemBinding.restoreResult.contentDescription = getString(
                    R.string.hidden_search_result_restore_description, model.displayName
                )
            }
            onClick(R.id.restore_result) {
                restoreHiddenResult(getModel<HiddenSearchResultModel>())
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                getHiddenSearchResultDao().observeAll().collect { results ->
                    binding.hiddenResultRecycler.models = results
                    binding.hiddenResultsEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun restoreHiddenResult(result: HiddenSearchResultModel) {
        lifecycleScope.launch {
            try {
                getHiddenSearchResultDao().delete(result.resultKey)
                Toast.makeText(this@SearchEngineSettingsActivity, R.string.hidden_search_result_restored, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@SearchEngineSettingsActivity, R.string.hidden_search_result_update_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSearchEngines() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val engines = SearchEngineDataManager.readSearchEngineList(applicationContext)
            defaultEngineTag = SearchEngineDataManager.getDefaultEngineTag(applicationContext)

            withContext(Dispatchers.Main) {
                searchEngines.clear()
                searchEngines.addAll(engines)
                binding.engineRecycler.models = searchEngines

                binding.progressBar.visibility = View.GONE
                binding.emptyView.visibility = if (engines.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun editSearchEngine(engine: SearchEngine) {
        val intent = Intent(this, SearchEngineAddActivity::class.java).apply {
            putExtra("editMode", true)
            putExtra("engine", engine.toJson())
        }
        startActivity(intent)
    }

    private fun showEngineOptionsDialog(engine: SearchEngine) {
        val isDefault = engine.tag == defaultEngineTag
        val hideOption = if (engine.showInSearchEngineList) {
            getString(R.string.search_engine_option_hide)
        } else {
            getString(R.string.search_engine_option_show)
        }
        val optionEdit = getString(R.string.search_engine_option_edit)
        val optionDelete = getString(R.string.search_engine_option_delete)
        val optionSetDefault = getString(R.string.search_engine_option_set_default)
        val options = mutableListOf(
            optionEdit,
            hideOption,
            optionDelete,
        )

        if (!isDefault) {
            options.add(0, optionSetDefault)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(engine.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    optionSetDefault -> setAsDefault(engine)
                    optionEdit -> editSearchEngine(engine)
                    optionDelete -> showDeleteConfirmDialog(engine)
                    getString(R.string.search_engine_option_hide) ->
                        switchSearchEngineShowInList(engine, false)
                    getString(R.string.search_engine_option_show) ->
                        switchSearchEngineShowInList(engine, true)
                }
            }
            .show()
    }

    private fun setAsDefault(engine: SearchEngine) {
        lifecycleScope.launch(Dispatchers.IO) {
            SearchEngineDataManager.setDefaultEngineTag(applicationContext, engine.tag)
            defaultEngineTag = engine.tag
            withContext(Dispatchers.Main) {
                binding.engineRecycler.bindingAdapter.let { it.notifyItemRangeChanged(0, it.itemCount) }
            }
        }
    }

    private fun showDeleteConfirmDialog(engine: SearchEngine) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_engine_delete_title)
            .setMessage(getString(R.string.search_engine_delete_message, engine.name))
            .setPositiveButton(R.string.search_engine_option_delete) { _, _ ->
                deleteSearchEngine(engine)
            }
            .setNegativeButton(R.string.search_engine_cancel, null)
            .show()
    }

    private fun switchSearchEngineShowInList(engine: SearchEngine, isShow: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            engine.showInSearchEngineList = isShow
            // Save and reorder
            SearchEngineDataManager.saveSearchEngineList(applicationContext, searchEngines)
            searchEngines.clear()
            searchEngines.addAll(SearchEngineDataManager.readSearchEngineList(applicationContext))
            withContext(Dispatchers.Main) {
                binding.engineRecycler.models = searchEngines
            }
        }
    }


    private fun deleteSearchEngine(engine: SearchEngine) {
        lifecycleScope.launch(Dispatchers.IO) {
            SearchEngineDataManager.removeSearchEngine(applicationContext, engine.tag)

            withContext(Dispatchers.Main) {
                searchEngines.remove(engine)
                binding.engineRecycler.models = searchEngines
                binding.emptyView.visibility =
                    if (searchEngines.isEmpty()) View.VISIBLE else View.GONE

                Toast.makeText(
                    this@SearchEngineSettingsActivity,
                    getString(R.string.search_engine_deleted),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showImportDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_engine_import_title)
            .setMessage(R.string.search_engine_import_message)
            .setView(R.layout.search_engine_dialog_edit_text)
            .setPositiveButton(R.string.search_engine_import_action) { dialog, _ ->
                val editText = (dialog as androidx.appcompat.app.AlertDialog)
                    .findViewById<android.widget.EditText>(R.id.edit_text)
                val json = editText?.text?.toString() ?: ""
                importSearchEngines(json)
            }
            .setNegativeButton(R.string.search_engine_cancel, null)
            .show()
    }

    private fun importSearchEngines(json: String) {
        if (json.isEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.search_engine_import_empty),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val engines = SearchEngine.toModelList(json)
                if (engines.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@SearchEngineSettingsActivity,
                            getString(R.string.search_engine_import_json_error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                SearchEngineDataManager.importSearchEngines(
                    applicationContext,
                    engines,
                    replaceExisting = false
                )

                withContext(Dispatchers.Main) {
                    loadSearchEngines()
                    Toast.makeText(
                        this@SearchEngineSettingsActivity,
                        getString(R.string.search_engine_import_success),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SearchEngineSettingsActivity,
                        getString(
                            R.string.search_engine_import_failed,
                            e.localizedMessage ?: ""
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun exportSearchEngines() {
        lifecycleScope.launch(Dispatchers.IO) {
            val json = SearchEngineDataManager.exportSearchEnginesJson(applicationContext)

            withContext(Dispatchers.Main) {
                // Copy to clipboard
                val clipboard =
                    getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText(
                    getString(R.string.search_engine_clipboard_label),
                    json
                )
                clipboard.setPrimaryClip(clip)

                Toast.makeText(
                    this@SearchEngineSettingsActivity,
                    getString(R.string.search_engine_clipboard_copied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Public utility method to retrieve row count preference
    fun getEngineRowCount(): Int = engineRowCount
}
