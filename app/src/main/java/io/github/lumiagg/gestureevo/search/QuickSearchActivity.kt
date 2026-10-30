package io.github.lumiagg.gestureevo.search

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.ContentInfoCompat
import androidx.core.view.OnReceiveContentListener
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle

import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityQuickSearchBinding
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_SEARCH_BACKGROUND_BLUR
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_SEARCH_CARD_BACKGROUND_BLUR
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_SEARCH_BACKGROUND_COLOR
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_BOTTOM_INPUT_LAYOUT
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_ENTER_FIRST_RESULT
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_KEEP_LAST_SEARCH_RESULT
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_KEYBOARD_FOLLOW_ANIMATION
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.TransparentActivityLauncher
import io.github.lumiagg.gestureevo.utils.PhoneDetector
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.UrlDetector
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.SettingsSnapshotCache
import io.github.lumiagg.gestureevo.utils.uriBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class QuickSearchActivity : AppCompatActivity() {
    companion object {
        private const val KEEP_BACKGROUND_BLUR_HANDOFF_TIMEOUT_MS = 1_000L
        private var keepBackgroundBlurHandoffTime = 0L
        private var releaseBackgroundBlurHandoff: (() -> Unit)? = null

        fun start(
            context: Context,
            query: String? = null,
            previewSortMode: Boolean = false,
            side: String? = null,
        ) {
            val intent = Intent(context, QuickSearchActivity::class.java).apply {
                query?.let { putExtra("query", it) }
                if (previewSortMode) {
                    putExtra("previewSortMode", true)
                }
                side?.let { putExtra(TriggerSide.EXTRA_TRIGGER_SIDE, it) }
            }
            TransparentActivityLauncher.start(context, intent)
        }

        fun markKeepBackgroundBlurOnNextEnter(releaseHandoff: () -> Unit) {
            keepBackgroundBlurHandoffTime = SystemClock.uptimeMillis()
            releaseBackgroundBlurHandoff = releaseHandoff
        }

        private fun consumeKeepBackgroundBlurOnNextEnter(): (() -> Unit)? {
            val handoffTime = keepBackgroundBlurHandoffTime
            val release = releaseBackgroundBlurHandoff
            keepBackgroundBlurHandoffTime = 0L
            releaseBackgroundBlurHandoff = null
            val isValid = handoffTime > 0L &&
                    SystemClock.uptimeMillis() - handoffTime <= KEEP_BACKGROUND_BLUR_HANDOFF_TIMEOUT_MS
            return if (isValid) release else null
        }
    }

    private val uiDispatcher = Dispatchers.Main.immediate
    private val ioDispatcher = Dispatchers.IO

    private lateinit var binding: ActivityQuickSearchBinding
    private lateinit var motionController: QuickSearchMotionController
    private lateinit var positionManager: QuickSearchPositionManager
    private lateinit var resultManager: SearchResultManager
    private lateinit var engineManager: SearchEngineManager
    private var pendingImageEngine: SearchEngine? = null
    private var pendingImageSearchFreeform = false
    private val attachedImageUris = mutableListOf<Uri>()
    private var pendingCameraImageUri: Uri? = null

    private var showBlur = false
    private var cardBackgroundBlurEnabled = false
    val searchWindowBlurEnabled: Boolean
        get() = showBlur
    val searchCardBlurEnabled: Boolean
        get() = cardBackgroundBlurEnabled
    val keyboardInsetPixels: Int
        get() = if (::motionController.isInitialized) motionController.keyboardInsetPixels else 0

    fun onKeyboardInsetChanged() {
        if (::resultManager.isInitialized) {
            resultManager.onAvailableSpaceChanged()
        }
    }
    private var keepBackgroundBlurOnEnter = false
    private var pendingBlurHandoff: (() -> Unit)? = null
    private val contentReady = CompletableDeferred<Unit>()
    private var searchBackgroundColor = 0
    private var enterFirstResultEnabled = false
    private var bottomInputLayoutEnabled = false
    private var keyboardFollowAnimationEnabled = false
    private var keepLastSearchResultEnabled = true
    private var isHighlighting = false
    private var isClosing = false
    private var inputRevision = 0
    private var observedInputText = ""
    private var inputReadyForSave = false
    private var enterSearchJob: Job? = null

    internal val canExecuteSearch: Boolean
        get() = !isClosing && !isFinishing && !isDestroyed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun releaseBackgroundBlurHandoffIfNeeded() {
        val release = pendingBlurHandoff
        pendingBlurHandoff = null
        release?.invoke()
    }

    private fun cancelPendingSearch() {
        enterSearchJob?.cancel()
        if (::engineManager.isInitialized) engineManager.cancelPendingSearch()
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) {
            clearPendingImageSearch()
            return@registerForActivityResult
        }
        lifecycleScope.launch(uiDispatcher) {
            addAttachedImages(uris)
            executePendingImageSearchIfNeeded()
        }
    }

    private val searchImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            clearPendingImageSearch()
            return@registerForActivityResult
        }
        lifecycleScope.launch(uiDispatcher) {
            addAttachedImages(listOf(uri))
            executePendingImageSearchIfNeeded()
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraImageUri
        pendingCameraImageUri = null
        if (!success || uri == null) {
            clearPendingImageSearch()
            return@registerForActivityResult
        }
        lifecycleScope.launch(uiDispatcher) {
            addAttachedImages(listOf(uri))
            executePendingImageSearchIfNeeded()
        }
    }

    var triggerSide: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        triggerSide = intent?.getStringExtra(TriggerSide.EXTRA_TRIGGER_SIDE)
        pendingBlurHandoff = consumeKeepBackgroundBlurOnNextEnter()
        keepBackgroundBlurOnEnter = pendingBlurHandoff != null
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithAnimation()
            }
        })
        val cachedPreferences = SettingsSnapshotCache.current
        if (cachedPreferences != null) {
            // Finish the framework lifecycle transaction before attaching the full view tree.
            // This needs one main-queue turn, without a redundant trip through the IO pool.
            lifecycleScope.launch(Dispatchers.Main) {
                if (!isClosing) createSearchContent(cachedPreferences)
            }
            return
        }
        lifecycleScope.launch(uiDispatcher) {
            val preferences = withContext(ioDispatcher) {
                settingsStore.data.first()
            }
            if (isClosing) return@launch
            createSearchContent(preferences)
        }
    }

    private fun createSearchContent(preferences: Preferences) {
        keyboardFollowAnimationEnabled = preferences.readKey(KEY_KEYBOARD_FOLLOW_ANIMATION, false)
        // When keyboard follow animation is disabled, let the system window handle resizing (adjustResize);
        // when enabled, switch to edge-to-edge and manage system bar and keyboard insets manually.
        WindowCompat.setDecorFitsSystemWindows(window, !keyboardFollowAnimationEnabled)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.setSoftInputMode(
            if (keyboardFollowAnimationEnabled) {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            }
        )
        bottomInputLayoutEnabled = preferences.readKey(KEY_BOTTOM_INPUT_LAYOUT, false)
        val layoutRes = if (bottomInputLayoutEnabled) {
            R.layout.activity_quick_search_bottom_input
        } else {
            R.layout.activity_quick_search
        }
        val root = layoutInflater.inflate(layoutRes, null, false)
        binding = ActivityQuickSearchBinding.bind(root)
        val initialBias = QuickSearchPositionManager.resolveBias(
            preferences.readKey(
                QuickSearchPositionManager.getBiasKeyForSide(triggerSide),
                QuickSearchPositionManager.BIAS_FOLLOW_TRIGGER
            ),
            triggerSide
        )
        positionManager = QuickSearchPositionManager(this, binding, initialBias)
        positionManager.applyInitialBias()
        // Keep the XML's opaque card colors off screen until the motion controller
        // installs the configured style.
        binding.topSearchCard.alpha = 0f
        binding.bottomEngineCard.alpha = 0f
        // Match decorFitsSystemWindows direction to avoid lost top margins and system inset handling.
        binding.root.fitsSystemWindows = !keyboardFollowAnimationEnabled
        binding.topSearchCard.clipToOutline = true
        setContentView(binding.root)

        // Initialize managers
        resultManager = SearchResultManager(this, binding)
        engineManager = SearchEngineManager(this, binding)

        initialize(preferences)
        contentReady.complete(Unit)
    }

    private val inputWindowReady = CompletableDeferred<Unit>()

    private fun initialize(preferences: Preferences) {
        initConfigData(preferences)
        motionController = QuickSearchMotionController(
            activity = this@QuickSearchActivity,
            binding = binding,
            window = window,
            keyboardFollowAnimationEnabled = keyboardFollowAnimationEnabled,
            bottomInputLayoutEnabled = bottomInputLayoutEnabled,
            showBlur = showBlur,
            cardBackgroundBlurEnabled = cardBackgroundBlurEnabled,
            keepBackgroundBlurOnEnter = keepBackgroundBlurOnEnter,
            searchBackgroundColor = searchBackgroundColor
        )
        positionManager.setup()
        // Reserve the final engine geometry before the first frame; data and icons
        // can arrive later without moving the bottom-anchored combined card.
        engineManager.configureEngineRecycler(preferences)
        initView()
        motionController.bind()
        if (keepBackgroundBlurOnEnter) {
            releaseBackgroundBlurHandoffIfNeeded()
        }
        showWithAnimation()
        // The input and IME must not wait for engine JSON, icons, or the results database.
        val firstFrame = CompletableDeferred<Unit>()
        resultManager.initialize(preferences, firstFrame, inputWindowReady)
        restoreInitialInput()
        binding.root.doOnPreDraw {
            binding.root.post {
                firstFrame.complete(Unit)
                if (!isClosing && !isDestroyed) {
                    lifecycleScope.launch(uiDispatcher) {
                        engineManager.initEngineData()
                        updateLandscapeEnginesVisibility(binding.searchInput.text?.toString().orEmpty())
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        binding.searchContainer.visibility = View.VISIBLE
        // Request input focus and pop up soft keyboard
        binding.searchInput.requestFocus()
        setupSearchInputClipboard()

        // Clear input button
        binding.clearButton.setOnClickListener {
            binding.searchInput.text?.clear()
        }
        binding.imageAttachButton.setOnClickListener {
            pickImages()
        }
        binding.imageAttachButton.setOnLongClickListener {
            captureImage()
            true
        }
        binding.imageCountBadge.setOnClickListener {
            clearAttachedImages()
        }
        binding.engineIcon.setOnClickListener {
            engineManager.performSearchByCurrentEngine(
                query = binding.searchInput.text?.toString().orEmpty()
            )
        }
        binding.engineIcon.setOnLongClickListener {
            engineManager.performSearchByCurrentEngine(
                query = binding.searchInput.text?.toString().orEmpty(),
                freeform = true
            )
        }

        // Listen for input text changes
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                // Rejecting an IME newline or updating spans can notify this listener
                // without changing the query. Keep an Enter request alive in that case.
                if (text != observedInputText) {
                    observedInputText = text
                    inputRevision++
                    inputReadyForSave = true
                    cancelPendingSearch()
                }
                val analysis = SearchResultManager.QueryAnalysis(
                    text = text,
                    urls = UrlDetector.findUrls(text),
                    phoneMatches = PhoneDetector.findPhoneMatches(text)
                )
                if (!isHighlighting) {
                    isHighlighting = true
                    try {
                        highlightUrlsInInput(s, analysis)
                    } finally {
                        isHighlighting = false
                    }
                }
                this@QuickSearchActivity.updateLandscapeEnginesVisibility(text)
                resultManager.updateSearchResults(analysis)
                updateInputLines(text)
                updateInputActionVisibility(text)
            }
        })

        // Filter newline characters from input
        binding.searchInput.filters = arrayOf(
            InputFilter { source, start, end, dest, dstart, dend ->
                // If input contains newline, trigger search and swallow newline
                if (source != null && source.endsWith("\n")) {
                    val text = binding.searchInput.text?.toString() ?: ""
                    if (text.isNotEmpty() || attachedImageUris.isNotEmpty()) {
                        submitInputSearch(text)
                    }
                    return@InputFilter ""
                }
                null // Allow other characters normally
            }
        )

        binding.searchContainer.setOnClickListener {
            finishWithAnimation()
        }
    }

    private fun submitInputSearch(text: String) {
        enterSearchJob?.cancel()
        val revision = inputRevision
        enterSearchJob = lifecycleScope.launch(uiDispatcher) {
            if (enterFirstResultEnabled) resultManager.awaitCurrentSearch()
            if (revision != inputRevision || !canExecuteSearch) return@launch
            val handled = enterFirstResultEnabled && resultManager.triggerFirstResult()
            if (!handled) engineManager.performSearchByCurrentEngine(text)
        }
    }

    private fun initConfigData(preferences: Preferences) {
        showBlur = preferences.readKey(KEY_SEARCH_BACKGROUND_BLUR, false)
        cardBackgroundBlurEnabled = preferences.readKey(KEY_SEARCH_CARD_BACKGROUND_BLUR, false)
        enterFirstResultEnabled = preferences.readKey(KEY_ENTER_FIRST_RESULT, false)
        keepLastSearchResultEnabled = preferences.readKey(KEY_KEEP_LAST_SEARCH_RESULT, true)
        val savedColor = preferences.readKey(KEY_SEARCH_BACKGROUND_COLOR, 0)
        val cardRegionBlur = GlassEffectUtils.usesCardRegionBlur(showBlur, cardBackgroundBlurEnabled)
        val defaultAlpha = if (cardRegionBlur) 0.32f else 0.7f
        val baseColor = if (savedColor != 0) {
            savedColor
        } else {
            DynamicColorUtils.refreshSurfaceColors(applicationContext)
            ColorUtils.setAlphaComponent(
                DynamicColorUtils.surfaceContainerHighestColor,
                (defaultAlpha * 255).toInt()
            )
        }
        searchBackgroundColor = if (cardRegionBlur) {
            ColorUtils.setAlphaComponent(
                baseColor,
                minOf(Color.alpha(baseColor), (defaultAlpha * 255).toInt())
            )
        } else {
            baseColor
        }
    }

    private fun updateInputActionVisibility(text: String) {
        binding.clearButton.post {
            binding.clearButton.isVisible = text.isNotEmpty()
            binding.imageAttachButton.isVisible = text.isEmpty() || attachedImageUris.isNotEmpty()
            binding.imageCountBadge.isVisible = attachedImageUris.isNotEmpty()
            binding.imageCountBadge.text = getString(
                R.string.quick_search_image_count,
                attachedImageUris.size
            )
            binding.imageCountBadge.contentDescription = resources.getQuantityString(
                R.plurals.quick_search_image_count_desc,
                attachedImageUris.size, attachedImageUris.size
            )
            binding.imageAttachButton.contentDescription =
                getString(R.string.quick_search_add_image)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::binding.isInitialized) return
        val text = binding.searchInput.text?.toString().orEmpty()
        updateLandscapeEnginesVisibility(text)
    }

    private fun updateLandscapeEnginesVisibility(text: String) {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (!bottomInputLayoutEnabled) {
            val shouldShow = if (isLandscape) {
                text.isEmpty() && engineManager.getSearchEngines().isNotEmpty()
            } else {
                engineManager.getSearchEngines().isNotEmpty()
            }
            if (binding.bottomEngineCard.isVisible != shouldShow) {
                binding.bottomEngineCard.isVisible = shouldShow
                resultManager.onAvailableSpaceChanged()
            }
        }
    }

    private fun updateInputLines(text: String) = binding.searchInput.post {
        val defaultTextSize = 18f // sp
        val smallTextSize = 14f // sp

        // Measure text width
        val paint = binding.searchInput.paint
        val textWidth = paint.measureText(text)
        val availableWidth =
            binding.searchInput.width - binding.searchInput.paddingLeft - binding.searchInput.paddingRight
        // If text width exceeds available width, scale down font size
        val targetTextSize = if (textWidth > availableWidth) smallTextSize else defaultTextSize
        val targetTextSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            targetTextSize,
            resources.displayMetrics
        )
        if (binding.searchInput.textSize != targetTextSizePx) {
            binding.searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetTextSize)
        }

    }

    private fun highlightUrlsInInput(
        editable: Editable?,
        analysis: SearchResultManager.QueryAnalysis
    ) {
        if (editable == null) return
        val text = analysis.text
        val existingSpans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        existingSpans.forEach { editable.removeSpan(it) }
        if (text.isEmpty()) return

        val highlightColor = DynamicColorUtils.primaryColor

        var searchStart = 0
        analysis.urls.forEach { url ->
            if (url.isEmpty()) return@forEach
            val start = text.indexOf(url, searchStart)
            if (start < 0) return@forEach
            val end = start + url.length
            editable.setSpan(
                ForegroundColorSpan(highlightColor),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            searchStart = end
        }

        analysis.phoneMatches.forEach { match ->
            editable.setSpan(
                ForegroundColorSpan(highlightColor),
                match.start,
                match.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun showWithAnimation() {
        isClosing = false
        motionController.show()
    }

    private fun hideWithAnimation(onEnd: () -> Unit = {}) {
        motionController.hide(onEnd)
    }

    @Suppress("DEPRECATION")
    fun finishWithAnimation() = lifecycleScope.launch(uiDispatcher) {
        if (isClosing) return@launch
        isClosing = true
        cancelPendingSearch()
        releaseBackgroundBlurHandoffIfNeeded()

        if (::binding.isInitialized && ::motionController.isInitialized && binding.root.isVisible) {
            hideWithAnimation {
                finish()
                overridePendingTransition(0, 0)
            }
        } else {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onPause() {
        super.onPause()
        cancelPendingSearch()
        if (!::binding.isInitialized || !inputReadyForSave) return
        val currentText = binding.searchInput.text?.toString() ?: ""
        val currentImages = attachedImageUris.map(Uri::toString)
        lifecycleScope.launch(ioDispatcher) {
            SearchHistoryManager.saveDraft(
                applicationContext,
                currentText,
                currentImages
            )
            SearchHistoryManager.add(
                applicationContext,
                currentText
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::motionController.isInitialized) {
            motionController.onWindowFocusChanged(hasFocus)
        }
    }

    internal fun onKeyboardRequestDispatched() {
        if (!inputWindowReady.isCompleted) {
            binding.root.postOnAnimation {
                if (!isClosing) inputWindowReady.complete(Unit)
            }
        }
    }

    override fun onDestroy() {
        releaseBackgroundBlurHandoffIfNeeded()
        if (::motionController.isInitialized) {
            motionController.release()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cancelPendingSearch()
        inputRevision++
        triggerSide = intent.getStringExtra(TriggerSide.EXTRA_TRIGGER_SIDE)
        if (!::binding.isInitialized) return
        if (::positionManager.isInitialized) {
            positionManager.onTriggerSideChanged()
        }
        val query = intent.getStringExtra("query")
        if (!query.isNullOrEmpty()) {
            applySearchInput(query)
        } else {
            lifecycleScope.launch(uiDispatcher) {
                val revision = inputRevision
                keepLastSearchResultEnabled = settingsStore.readKey(KEY_KEEP_LAST_SEARCH_RESULT, true)
                if (keepLastSearchResultEnabled) {
                    val draft = withContext(ioDispatcher) {
                        SearchHistoryManager.getLastDraft(this@QuickSearchActivity)
                    }
                    if (revision != inputRevision || isClosing) return@launch
                    restoreAttachedImages(draft.imageUris.map(Uri::parse), revision)
                    if (revision != inputRevision || isClosing) return@launch
                    applySearchInput(draft.text, selectAll = true)
                } else {
                    if (revision != inputRevision || isClosing) return@launch
                    restoreAttachedImages(emptyList(), revision)
                    if (revision != inputRevision || isClosing) return@launch
                    applySearchInput("")
                }
            }
        }
    }

    private fun restoreInitialInput() = lifecycleScope.launch(uiDispatcher) {
        val revision = inputRevision
        val directQuery = intent.getStringExtra("query").orEmpty()
        val draft = if (directQuery.isEmpty() && keepLastSearchResultEnabled) {
            withContext(ioDispatcher) {
                SearchHistoryManager.getLastDraft(this@QuickSearchActivity)
            }
        } else {
            SearchHistoryManager.SearchDraft(text = directQuery)
        }
        if (revision != inputRevision || isClosing) return@launch
        restoreAttachedImages(draft.imageUris.map(Uri::parse), revision)
        if (revision != inputRevision || isClosing) return@launch
        applySearchInput(draft.text, selectAll = true)
    }

    fun applySearchInput(text: String, selectAll: Boolean = false) {
        inputReadyForSave = true
        binding.searchInput.setText(text)
        if (selectAll) {
            binding.searchInput.selectAllSafe()
        } else {
            binding.searchInput.safeSetSelection(text.length)
        }
    }

    fun refreshSearchResultsFromInput(text: String) {
        resultManager.updateSearchResults(text)
        updateInputLines(text)
        updateInputActionVisibility(text)
    }

    fun openLink(link: String, freeform: Boolean) {
        lifecycleScope.launch(uiDispatcher) {
            val targetLink = uriBuilder(GestureActionType.OTHER_LINK)
                .appendQueryParameter("uri", link)
                .appendQueryParameter("freeform", freeform.toString())
                .apply {
                    triggerSide?.let { appendQueryParameter("triggerSide", it) }
                }
                .build()
            ActionProxy(applicationContext, targetLink.toString()).execute(side = triggerSide)
            finishWithAnimation()
        }
    }

    fun pickImageAndSearch(engine: SearchEngine, freeform: Boolean) {
        pendingImageEngine = engine
        pendingImageSearchFreeform = freeform
        searchImagePickerLauncher.launch("image/*")
    }

    fun captureImageAndSearch(engine: SearchEngine, freeform: Boolean) {
        pendingImageEngine = engine
        pendingImageSearchFreeform = freeform
        captureImage()
    }

    fun pickImages() {
        imagePickerLauncher.launch("image/*")
    }

    fun getAttachedImageUris(): List<Uri> = attachedImageUris.toList()

    private fun captureImage() {
        pendingCameraImageUri = createCameraImageUri()
        val uri = pendingCameraImageUri ?: return
        cameraLauncher.launch(uri)
    }

    private fun createCameraImageUri(): Uri? {
        return try {
            val imageDir = File(cacheDir, "images").apply { mkdirs() }
            val imageFile = File(imageDir, "camera_${System.currentTimeMillis()}.jpg")
            FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                imageFile
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun addAttachedImages(uris: List<Uri>) {
        // Activity results can be delivered in onStart, before async onCreate has finished.
        contentReady.await()
        inputRevision++
        cancelPendingSearch()
        val persistedUris = withContext(ioDispatcher) {
            uris.mapNotNull { persistImageUri(it) }
        }
        val newUris = persistedUris.filterNot { attachedImageUris.contains(it) }
        if (newUris.isEmpty()) return
        inputReadyForSave = true
        attachedImageUris.addAll(newUris)
        updateInputActionVisibility(binding.searchInput.text?.toString().orEmpty())
    }

    private suspend fun restoreAttachedImages(uris: List<Uri>, revision: Int = inputRevision) {
        val validUris = if (uris.isEmpty()) emptyList() else withContext(ioDispatcher) {
            uris.filter { uri ->
                runCatching {
                    contentResolver.openInputStream(uri)?.use { true } ?: false
                }.getOrDefault(false)
            }
        }
        if (revision != inputRevision || isClosing) return
        attachedImageUris.clear()
        attachedImageUris.addAll(validUris)
        updateInputActionVisibility(binding.searchInput.text?.toString().orEmpty())
    }

    private fun setupSearchInputClipboard() {
        ViewCompat.setOnReceiveContentListener(
            binding.searchInput,
            arrayOf("image/*")
        ) { _, payload ->
            val split = payload.partition { item ->
                val uri = item.uri
                uri != null && (ClipboardUtils.isImageUri(applicationContext, uri) || payload.clip.description?.hasMimeType("image/*") == true)
            }
            val imageParts = split.first
            val remaining = split.second
            if (imageParts != null) {
                val clip = imageParts.clip
                val uris = ArrayList<Uri>(clip.itemCount)
                for (i in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(i).uri
                    if (uri != null) {
                        uris.add(uri)
                    }
                }
                if (uris.isNotEmpty()) {
                    lifecycleScope.launch(uiDispatcher) {
                        addAttachedImages(uris)
                        ToastHelper.showInUIThread(applicationContext, R.string.quick_search_image_added)
                        if (::resultManager.isInitialized) {
                            resultManager.updateSearchResults(binding.searchInput.text?.toString().orEmpty())
                        }
                    }
                }
            }
            remaining
        }

        val actionCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = true

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                if (menu != null && ClipboardUtils.hasImage(applicationContext)) {
                    if (menu.findItem(android.R.id.paste) == null) {
                        menu.add(Menu.NONE, android.R.id.paste, 0, android.R.string.paste)
                        return true
                    }
                }
                return false
            }

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                if (item?.itemId == android.R.id.paste) {
                    val clipboardImage = ClipboardUtils.getImage(applicationContext)
                    if (clipboardImage != null) {
                        lifecycleScope.launch(uiDispatcher) {
                            addAttachedImages(listOf(clipboardImage))
                            ToastHelper.showInUIThread(applicationContext, R.string.quick_search_image_added)
                            if (::resultManager.isInitialized) {
                                resultManager.updateSearchResults(binding.searchInput.text?.toString().orEmpty())
                            }
                        }
                        val clipboardText = ClipboardUtils.getText(applicationContext)
                        if (clipboardText.isNotEmpty()) {
                            val start = binding.searchInput.selectionStart.coerceAtLeast(0)
                            val end = binding.searchInput.selectionEnd.coerceAtLeast(0)
                            binding.searchInput.text?.replace(minOf(start, end), maxOf(start, end), clipboardText)
                        }
                        mode?.finish()
                        return true
                    }
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode?) {}
        }

        binding.searchInput.customInsertionActionModeCallback = actionCallback
        binding.searchInput.customSelectionActionModeCallback = actionCallback

        binding.searchInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed && event.action == KeyEvent.ACTION_DOWN) {
                val clipboardImage = ClipboardUtils.getImage(applicationContext)
                if (clipboardImage != null) {
                    lifecycleScope.launch(uiDispatcher) {
                        addAttachedImages(listOf(clipboardImage))
                        ToastHelper.showInUIThread(applicationContext, R.string.quick_search_image_added)
                        if (::resultManager.isInitialized) {
                            resultManager.updateSearchResults(binding.searchInput.text?.toString().orEmpty())
                        }
                    }
                    val clipboardText = ClipboardUtils.getText(applicationContext)
                    if (clipboardText.isNotEmpty()) {
                        val start = binding.searchInput.selectionStart.coerceAtLeast(0)
                        val end = binding.searchInput.selectionEnd.coerceAtLeast(0)
                        binding.searchInput.text?.replace(minOf(start, end), maxOf(start, end), clipboardText)
                    }
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    fun attachClipboardImage(uri: Uri) = lifecycleScope.launch(uiDispatcher) {
        addAttachedImages(listOf(uri))
        ToastHelper.showInUIThread(applicationContext, R.string.quick_search_image_added)
        if (::resultManager.isInitialized) {
            resultManager.updateSearchResults(binding.searchInput.text?.toString().orEmpty())
        }
    }

    private fun clearAttachedImages() {
        if (attachedImageUris.isEmpty()) return
        inputRevision++
        inputReadyForSave = true
        cancelPendingSearch()
        attachedImageUris.clear()
        updateInputActionVisibility(binding.searchInput.text?.toString().orEmpty())
        ToastHelper.showInUIThread(applicationContext, R.string.quick_search_image_cleared)
        if (::resultManager.isInitialized) {
            resultManager.updateSearchResults(binding.searchInput.text?.toString().orEmpty())
        }
    }

    private fun executePendingImageSearchIfNeeded() {
        val engine = pendingImageEngine ?: return
        pendingImageEngine = null
        SearchEngineExecutor(this, engine, pendingImageSearchFreeform) {
            finishWithAnimation()
        }.execute(
            query = binding.searchInput.text?.toString().orEmpty(),
            imageUris = attachedImageUris.toList()
        )
    }

    private fun clearPendingImageSearch() {
        pendingImageEngine = null
        pendingImageSearchFreeform = false
    }

    private fun persistImageUri(uri: Uri): Uri? {
        if (uri.authority == "${packageName}.fileprovider") {
            return uri
        }
        return runCatching {
            val imageDir = File(cacheDir, "images").apply { mkdirs() }
            val mimeType = try { contentResolver.getType(uri) } catch (_: Exception) { null }
            val ext = when (mimeType) {
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "image/gif" -> ".gif"
                else -> {
                    val path = uri.path ?: uri.toString()
                    when {
                        path.endsWith(".png", true) -> ".png"
                        path.endsWith(".webp", true) -> ".webp"
                        path.endsWith(".gif", true) -> ".gif"
                        else -> ".jpg"
                    }
                }
            }
            val targetFile =
                File(imageDir, "picked_${System.currentTimeMillis()}_${attachedImageUris.size}$ext")
            val inputStream = if (uri.scheme == "file" || uri.scheme == null) {
                val file = File(uri.path ?: uri.schemeSpecificPart ?: uri.toString())
                if (file.exists()) file.inputStream() else contentResolver.openInputStream(uri)
            } else {
                contentResolver.openInputStream(uri)
            }
            inputStream?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                targetFile
            )
        }.getOrNull()
    }
}

fun EditText.safeSetSelection(pos: Int) {
    val length = text?.length ?: 0
    setSelection(minOf(pos, length))
}

fun EditText.selectAllSafe() {
    val len = text?.length ?: 0
    if (len > 0) setSelection(0, len)
}
