package io.github.lumiagg.gestureevo.settings

import io.github.lumiagg.gestureevo.databinding.BottomSheetIconSelectBinding


import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsService
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivitySearchEngineAddBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.gesture.config.IconType
import io.github.lumiagg.gestureevo.search.config.EngineType
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import io.github.lumiagg.gestureevo.search.config.SearchEngineDataManager
import io.github.lumiagg.gestureevo.search.setEngineIcon
import io.github.lumiagg.gestureevo.settings.ui.ActivityFilters
import io.github.lumiagg.gestureevo.settings.ui.ActivityModel
import io.github.lumiagg.gestureevo.settings.ui.DefaultSelectAPPBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.SelectAPPCallBack
import io.github.lumiagg.gestureevo.settings.ui.SelectActivityBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.SelectActivityCallBack
import io.github.lumiagg.gestureevo.settings.ui.showEditTextBottomSheet
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.icon.IconFileUtils
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.icon.IconImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchEngineAddActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySearchEngineAddBinding
    private var editMode = false
    private var searchEngine = SearchEngine()
    private val iconFileUtils = IconFileUtils(this)
    private var currentEngineType = EngineType.DIRECT_LINK
    private var showImageBadge = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchEngineAddBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        initView()
        initData(savedInstanceState)
    }

    private fun setupToolbar() {
        binding.toolbar.title = getString(
            if (editMode) {
                R.string.search_engine_edit_page_title
            } else {
                R.string.search_engine_add_page_title
            }
        )
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun initView() {
        // Setup icon selection
        binding.engineIcon.setImage(R.drawable.baseline_image_24)
        binding.engineIcon.setOnClickListener {
            selectIcon()
        }
        lifecycleScope.launch {
            showImageBadge = settingsStore.readKey(SearchEngineSettingsActivity.KEY_SHOW_IMAGE_BADGE, true)
            updateEngineIconPreview()
        }

        // Setup save button
        binding.fabSave.setOnClickListener {
            saveSearchEngine()
        }

        // Setup share button
        binding.fabShare.setOnClickListener {
            shareEngineConfig()
        }

        setupModeSelector()

        // Initially display configuration for first engine type
        showConfigForType(EngineType.DIRECT_LINK)
    }

    private fun setupModeSelector() {
        binding.modeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedChipId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val selectedType = engineTypeForChipId(selectedChipId)
            if (selectedType != currentEngineType) {
                showConfigForType(selectedType)
            }
        }
    }

    private fun chipIdForEngineType(type: EngineType): Int = when (type) {
        EngineType.DIRECT_LINK -> R.id.chipDirectLink
        EngineType.SHARE_TO_APP -> R.id.chipShareToApp
        EngineType.SHARE_IMAGE_TO_APP -> R.id.chipShareImageToApp
        EngineType.JUMP_TO_ACTIVITY -> R.id.chipJumpActivity
        EngineType.EXTERN_JUMP_LINK -> R.id.chipExternJump
    }

    private fun engineTypeForChipId(chipId: Int): EngineType = when (chipId) {
        R.id.chipShareToApp -> EngineType.SHARE_TO_APP
        R.id.chipShareImageToApp -> EngineType.SHARE_IMAGE_TO_APP
        R.id.chipJumpActivity -> EngineType.JUMP_TO_ACTIVITY
        R.id.chipExternJump -> EngineType.EXTERN_JUMP_LINK
        else -> EngineType.DIRECT_LINK
    }

    private fun showConfigForType(type: EngineType) {
        currentEngineType = type
        searchEngine.engineType = type
        // Hide all configuration layouts
        binding.directLinkConfig.root.visibility = View.GONE
        binding.jumpActivityConfig.root.visibility = View.GONE
        binding.externJumpConfig.root.visibility = View.GONE
        binding.shareToAppConfig.root.visibility = View.GONE
        binding.shareImageToAppConfig.root.visibility = View.GONE

        // Show corresponding configuration layout
        when (type) {
            EngineType.DIRECT_LINK -> {
                binding.directLinkConfig.root.visibility = View.VISIBLE
                setupDirectLinkConfig()
            }

            EngineType.JUMP_TO_ACTIVITY -> {
                binding.jumpActivityConfig.root.visibility = View.VISIBLE
                setupJumpActivityConfig()
            }

            EngineType.EXTERN_JUMP_LINK -> {
                binding.externJumpConfig.root.visibility = View.VISIBLE
                setupExternJumpConfig()
            }

            EngineType.SHARE_TO_APP -> {
                binding.shareToAppConfig.root.visibility = View.VISIBLE
                setupShareToTextAppConfig()
            }

            EngineType.SHARE_IMAGE_TO_APP -> {
                binding.shareImageToAppConfig.root.visibility = View.VISIBLE
                setupShareToImageActivityConfig()
            }
        }
        updateEngineIconPreview()
    }

    private fun setupDirectLinkConfig() {
        updateDirectLinkCustomTabsSwitch()

        binding.directLinkConfig.btnEditSearchLink.setOnClickListener {
            showEditTextBottomSheet(
                activity = this,
                coroutineScope = lifecycleScope,
                title = getString(R.string.search_engine_direct_link_title),
                hint = getString(R.string.search_engine_direct_link_hint),
                defaultText = searchEngine.searchLink,
                onConfirm = { inputText ->
                    if (inputText.isNotEmpty()) {
                        searchEngine.searchLink = inputText
                        binding.directLinkConfig.tvSearchLink.text = inputText
                    }
                }
            )
        }

        binding.directLinkConfig.directLinkSelectActivity.setOnClickListener {
            DefaultSelectAPPBottomSheet(this, lifecycleScope, object : SelectAPPCallBack {
                override fun selectCompleted(model: APPModel) {
                    searchEngine.targetPackage = model.pkgName
                    binding.directLinkConfig.directLinkTargetPackage.text = model.pkgName
                    updateDirectLinkCustomTabsSwitch()

                    // Auto-populate icon
                    if (searchEngine.iconType == IconType.OTHER) {
                        IconHelper.getIcon(applicationContext, model.pkgName)?.let { drawable ->
                            updateAndSaveIcon(drawable)
                        }
                    }
                }
            }).show()
        }

    }

    private fun setupJumpActivityConfig() {
        binding.jumpActivityConfig.jumpActivitySelectActivity.setOnClickListener {
            SelectActivityBottomSheet(this, lifecycleScope, object : SelectActivityCallBack {
                override fun selectCompleted(model: ActivityModel) {
                    searchEngine.targetPackage = model.packageName
                    searchEngine.targetActivity = model.activityName
                    binding.jumpActivityConfig.jumpActivityTargetPackage.text = model.packageName
                    binding.jumpActivityConfig.jumpActivityTargetActivity.text = model.activityName

                    // Auto-populate icon
                    if (searchEngine.iconType == IconType.OTHER) {
                        IconHelper.getIcon(applicationContext, model.packageName)?.let { drawable ->
                            updateAndSaveIcon(drawable)
                        }
                    }
                }
            }).show()
        }
        binding.jumpActivityConfig.jumpActivityAutoInputEnter.setOnCheckedChangeListener { _, isChecked ->
            searchEngine.autoInputEnter = isChecked
        }
    }

    private fun setupExternJumpConfig() {
        // Select external launch package name
        binding.externJumpConfig.btnSelectPackage.setOnClickListener {
            DefaultSelectAPPBottomSheet(this, lifecycleScope, object : SelectAPPCallBack {
                override fun selectCompleted(model: APPModel) {
                    searchEngine.externJumpPackage = model.pkgName
                    binding.externJumpConfig.tvExternPackage.text = model.pkgName

                    // Auto-populate icon
                    if (searchEngine.iconType == IconType.OTHER) {
                        IconHelper.getIcon(applicationContext, model.pkgName)?.let { drawable ->
                            updateAndSaveIcon(drawable)
                        }
                    }
                }
            }).show()
        }

        // Edit external launch URL
        binding.externJumpConfig.btnEditExternLink.setOnClickListener {
            showEditTextBottomSheet(
                activity = this,
                coroutineScope = lifecycleScope,
                title = getString(R.string.search_engine_extern_jump_title),
                hint = getString(R.string.search_engine_extern_jump_hint),
                defaultText = searchEngine.externJumpLink,
                onConfirm = { inputText ->
                    if (inputText.isNotEmpty()) {
                        searchEngine.externJumpLink = inputText
                        binding.externJumpConfig.tvExternLink.text = inputText
                    }
                }
            )
        }

        // Select target Activity
        binding.externJumpConfig.btnSelectTargetActivity.setOnClickListener {
            SelectActivityBottomSheet(this, lifecycleScope, object : SelectActivityCallBack {
                override fun selectCompleted(model: ActivityModel) {
                    searchEngine.targetPackage = model.packageName
                    searchEngine.targetActivity = model.activityName
                    binding.externJumpConfig.externJumpTargetActivity.text =
                        getString(R.string.package_activity_label, model.packageName, model.activityName)
                    // Auto-populate icon
                    if (searchEngine.iconType == IconType.OTHER) {
                        IconHelper.getIcon(applicationContext, model.packageName)?.let { drawable ->
                            updateAndSaveIcon(drawable)
                        }
                    }
                }
            }).show()
        }

        binding.externJumpConfig.externJumpAutoInputEnter.setOnCheckedChangeListener { _, isChecked ->
            searchEngine.autoInputEnter = isChecked
        }
    }

    private fun setupShareToTextAppConfig() {
        binding.shareToAppConfig.shareAppSelectActivity.setOnClickListener {
            SelectActivityBottomSheet(this, lifecycleScope, object : SelectActivityCallBack {
                override fun selectCompleted(model: ActivityModel) {
                    searchEngine.targetPackage = model.packageName
                    searchEngine.targetActivity = model.activityName
                    binding.shareToAppConfig.shareAppTargetActivity.text =
                        getString(R.string.package_activity_label, model.packageName, model.activityName)
                    if (searchEngine.iconType == IconType.OTHER) {
                        IconHelper.getIcon(applicationContext, model.packageName)
                            ?.let { drawable -> updateAndSaveIcon(drawable) }
                    }
                }
            }, ActivityFilters.textShareFilter()).show()
        }
    }

    private fun setupShareToImageActivityConfig() {
        binding.shareImageToAppConfig.shareImageSelectActivity.setOnClickListener {
            SelectActivityBottomSheet(this, lifecycleScope, object : SelectActivityCallBack {
                override fun selectCompleted(model: ActivityModel) {
                    searchEngine.targetPackage = model.packageName
                    searchEngine.targetActivity = model.activityName
                    binding.shareImageToAppConfig.shareImageTargetActivity.text =
                        getString(R.string.package_activity_label, model.packageName, model.activityName)
                    if (searchEngine.iconType == IconType.OTHER) {
                        IconHelper.getIcon(applicationContext, model.packageName)
                            ?.let { drawable -> updateAndSaveIcon(drawable) }
                    }
                }
            }, ActivityFilters.imageShareFilter()).show()
        }
    }

    private fun initData(savedInstanceState: Bundle?) {
        editMode = intent.getBooleanExtra("editMode", false)
        setupToolbar()

        if (editMode || savedInstanceState?.containsKey("engine_draft") == true) {
            (savedInstanceState?.getString("engine_draft") ?: intent.getStringExtra("engine"))?.let { engineJson ->
                SearchEngine.toModel(engineJson)?.let { engine ->
                    searchEngine = engine
                    fillEngineData()
                }
            }
        } else {
            searchEngine.tag = SearchEngineDataManager.buildTag()
            updateEngineIconPreview()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        searchEngine.name = binding.etEngineName.text.toString()
        searchEngine.engineType = currentEngineType
        outState.putString("engine_draft", searchEngine.toJson())
        super.onSaveInstanceState(outState)
    }

    private fun fillEngineData() {
        // Populate name
        binding.etEngineName.setText(searchEngine.name)

        binding.modeChipGroup.check(chipIdForEngineType(searchEngine.engineType))

        // Populate icon
        setEngineIcon(applicationContext, binding.engineIcon, searchEngine, showImageBadge)

        // Populate specific configuration
        when (searchEngine.engineType) {
            EngineType.DIRECT_LINK -> {
                binding.directLinkConfig.tvSearchLink.text = searchEngine.searchLink
                binding.directLinkConfig.directLinkTargetPackage.text = searchEngine.targetPackage
                updateDirectLinkCustomTabsSwitch()
            }

            EngineType.JUMP_TO_ACTIVITY -> {
                binding.jumpActivityConfig.jumpActivityTargetPackage.text = searchEngine.targetPackage
                binding.jumpActivityConfig.jumpActivityTargetActivity.text = searchEngine.targetActivity
                binding.jumpActivityConfig.jumpActivityAutoInputEnter.isChecked =
                    searchEngine.autoInputEnter
            }

            EngineType.EXTERN_JUMP_LINK -> {
                binding.externJumpConfig.tvExternPackage.text = searchEngine.externJumpPackage
                binding.externJumpConfig.tvExternLink.text = searchEngine.externJumpLink
                binding.externJumpConfig.externJumpTargetActivity.text =
                    getString(R.string.package_activity_label, searchEngine.targetPackage, searchEngine.targetActivity)
                binding.externJumpConfig.externJumpAutoInputEnter.isChecked =
                    searchEngine.autoInputEnter
            }

            EngineType.SHARE_TO_APP -> {
                binding.shareToAppConfig.shareAppTargetActivity.text =
                    getString(R.string.package_activity_label, searchEngine.targetPackage, searchEngine.targetActivity)
            }

            EngineType.SHARE_IMAGE_TO_APP -> {
                binding.shareImageToAppConfig.shareImageTargetActivity.text =
                    getString(R.string.package_activity_label, searchEngine.targetPackage, searchEngine.targetActivity)
            }
        }
    }

    private fun updateDirectLinkCustomTabsSwitch() {
        val switch = binding.directLinkConfig.useCustomTabsSwitch
        val supportsCustomTabs = supportsCustomTabs(searchEngine.targetPackage)
        switch.setOnCheckedChangeListener(null)
        val row = switch.parent as? View ?: switch
        if (supportsCustomTabs) {
            row.visibility = View.VISIBLE
            switch.isChecked = searchEngine.useCustomTabs
            switch.setOnCheckedChangeListener { _, isChecked ->
                searchEngine.useCustomTabs = isChecked
            }
        } else {
            row.visibility = View.GONE
            switch.isChecked = false
            searchEngine.useCustomTabs = false
        }
    }

    private fun supportsCustomTabs(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val serviceIntent = Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).apply {
            setPackage(packageName)
        }
        return packageManager.queryIntentServices(serviceIntent, 0).isNotEmpty()
    }

    private fun selectIcon() {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, R.style.ThemeOverlay_BottomSheetDialog_Scrollable
        )
        val bottomSheetView = BottomSheetIconSelectBinding.inflate(layoutInflater).root

        bottomSheetView.findViewById<View>(R.id.select_gallery)
            .setOnClickListener {
                bottomSheet.dismiss()
                selectImageLauncher.launch(IconEditorRequest(
                    imageBadge = searchEngine.engineType == EngineType.SHARE_IMAGE_TO_APP && showImageBadge,
                ))
            }

        bottomSheetView.findViewById<View>(R.id.select_app_icon)
            .setOnClickListener {
                bottomSheet.dismiss()
                DefaultSelectAPPBottomSheet(this, lifecycleScope, object : SelectAPPCallBack {
                    override fun selectCompleted(model: APPModel) {
                        selectImageLauncher.launch(IconEditorRequest(
                            appPackage = model.pkgName,
                            imageBadge = searchEngine.engineType == EngineType.SHARE_IMAGE_TO_APP && showImageBadge,
                        ))
                    }
                }).show()
            }

        bottomSheetView.findViewById<View>(R.id.select_text_icon)
            .setOnClickListener {
                bottomSheet.dismiss()
                selectTextIconLauncher.launch(TextIconEditorRequest(
                    text = if (searchEngine.iconType == IconType.TEXT) searchEngine.textIcon.orEmpty() else "",
                    imageBadge = searchEngine.engineType == EngineType.SHARE_IMAGE_TO_APP && showImageBadge,
                ))
            }

        bottomSheet.setContentView(bottomSheetView)
        bottomSheet.show()
    }

    private val selectTextIconLauncher = registerForActivityResult(TextIconEditorContract()) { text ->
        if (text != null) {
            iconFileUtils.clear()
            searchEngine.textIcon = text
            searchEngine.iconType = IconType.TEXT
            searchEngine.icon = null
            updateEngineIconPreview()
        }
    }

    private val selectImageLauncher = registerForActivityResult(IconEditorContract()) { path ->
        if (path != null) lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { IconImageIO.read(path) }
            if (bitmap != null) {
                iconFileUtils.setImgBitmap(bitmap)
                iconFileUtils.imgPath = path
                searchEngine.iconType = IconType.URI
                searchEngine.icon = path
                binding.engineIcon.setImage(bitmap)
                updateEngineIconBadgePreview()
            } else {
                Toast.makeText(this@SearchEngineAddActivity, R.string.icon_editor_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateAndSaveIcon(drawable: Drawable) {
        searchEngine.iconType = IconType.URI
        binding.engineIcon.setImage(drawable)
        updateEngineIconBadgePreview()
        drawable.let { d ->
            val bitmap = createBitmap(d.intrinsicWidth, d.intrinsicHeight)
            val canvas = android.graphics.Canvas(bitmap)
            d.setBounds(0, 0, canvas.width, canvas.height)
            d.draw(canvas)
            iconFileUtils.setImgBitmap(bitmap)
        }
    }

    private fun updateEngineIconPreview() {
        if (searchEngine.iconType == IconType.URI && searchEngine.icon.isNullOrBlank()) {
            iconFileUtils.getImgBitmap()?.let { bitmap ->
                binding.engineIcon.setImage(bitmap)
                updateEngineIconBadgePreview()
                return
            }
        }

        val hasCustomPreview = searchEngine.iconType == IconType.URI ||
                searchEngine.iconType == IconType.TEXT ||
                !searchEngine.targetPackage.isNullOrEmpty() ||
                !searchEngine.name.isNullOrEmpty()
        if (hasCustomPreview) {
            setEngineIcon(applicationContext, binding.engineIcon, searchEngine, showImageBadge)
        } else {
            binding.engineIcon.setImage(R.drawable.baseline_image_24)
            updateEngineIconBadgePreview()
        }
    }

    private fun updateEngineIconBadgePreview() {
        if (searchEngine.engineType == EngineType.SHARE_IMAGE_TO_APP && showImageBadge) {
            binding.engineIcon.setBadge(R.drawable.baseline_image_24)
        } else {
            binding.engineIcon.setBadge(null)
        }
    }


    private suspend fun checkSearchEngine(): Boolean {
        // Get engine name
        val engineName = binding.etEngineName.text.toString().trim()
        if (engineName.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@SearchEngineAddActivity,
                    getString(R.string.search_engine_name_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }

        // Validate configuration
        val isValid = when (currentEngineType) {
            EngineType.DIRECT_LINK -> {
                !searchEngine.searchLink.isNullOrEmpty()
            }

            EngineType.JUMP_TO_ACTIVITY -> {
                !searchEngine.targetPackage.isNullOrEmpty() &&
                        !searchEngine.targetActivity.isNullOrEmpty()
            }

            EngineType.EXTERN_JUMP_LINK -> {
                !searchEngine.externJumpLink.isNullOrEmpty() &&
                        !searchEngine.externJumpPackage.isNullOrEmpty()
            }

            EngineType.SHARE_TO_APP -> {
                !searchEngine.targetPackage.isNullOrEmpty()
            }

            EngineType.SHARE_IMAGE_TO_APP -> {
                !searchEngine.targetPackage.isNullOrEmpty()
            }
        }

        if (!isValid) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@SearchEngineAddActivity,
                    getString(R.string.search_engine_config_incomplete),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }

        return true
    }

    private fun saveSearchEngine() = lifecycleScope.launch(Dispatchers.IO) {
        if (!checkSearchEngine()) return@launch

        val engineName = binding.etEngineName.text.toString().trim()
        searchEngine.name = engineName
        searchEngine.engineType = currentEngineType
        // Save icon
        if (iconFileUtils.saveIcon()) {
            searchEngine.icon = iconFileUtils.imgPath
        }

        // Save or update search engine
        if (editMode) {
            val success = SearchEngineDataManager.updateSearchEngine(
                applicationContext,
                searchEngine
            )
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(
                        this@SearchEngineAddActivity,
                        getString(R.string.search_engine_update_success),
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                } else {
                    Toast.makeText(
                        this@SearchEngineAddActivity,
                        getString(R.string.search_engine_update_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            SearchEngineDataManager.addSearchEngine(
                applicationContext,
                searchEngine
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@SearchEngineAddActivity,
                    getString(R.string.search_engine_add_success),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }

        Log.d(TAG, "saveSearchEngine: ${searchEngine.toJson()}")
    }

    private fun shareEngineConfig() = lifecycleScope.launch(Dispatchers.IO) {
        if (!checkSearchEngine()) return@launch

        val json = SearchEngine.toExportJsonList(listOf(searchEngine))

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
                this@SearchEngineAddActivity,
                getString(R.string.search_engine_clipboard_copied),
                Toast.LENGTH_SHORT
            ).show()
        }
        Log.d(TAG, "shareSearchEngine: ${searchEngine.toJson()}")
    }


    companion object {
        const val TAG = "SearchEngineAddActivity"
    }
}
