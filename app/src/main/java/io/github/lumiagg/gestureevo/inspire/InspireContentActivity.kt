package io.github.lumiagg.gestureevo.inspire

import android.app.ActivityOptions
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.inspire.imageeditor.InspireImageEditorActivity
import io.github.lumiagg.gestureevo.inspire.ui.BigBangView
import io.github.lumiagg.gestureevo.inspire.ui.InspireContentView
import io.github.lumiagg.gestureevo.inspire.ui.showInspireEditBottomSheet
import io.github.lumiagg.gestureevo.search.QuickSearchEnginePager
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_SCREEN_CONTENT_BACKGROUND_BLUR
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_SCREEN_CONTENT_CARD_BACKGROUND_BLUR
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.TransparentActivityLauncher
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class InspireContentActivity : AppCompatActivity() {
    private lateinit var inspireContentView: InspireContentView
    private lateinit var inspireContentRoot: ConstraintLayout
    private lateinit var enginePager: QuickSearchEnginePager
    private lateinit var bottomEngineCard: MaterialCardView
    private lateinit var engineManager: SearchEngineManagerInspire
    private var isFinishing = false
    private var launchingImageEditor = false
    private var strongCopyHandled = false
    private var contentLoadJob: Job? = null
    private var imageActionJob: Job? = null
    private var screenshotBitmapHandle: ManagedBitmap? = null
    private var screenContentBackgroundBlurEnabled = false
    private var screenContentCardBackgroundBlurEnabled = false
    private val imageShareLauncher by lazy { ImageShareLauncher(applicationContext) }
    private val contentLoader by lazy { InspireContentLoader(applicationContext) }
    private val tokenContentController by lazy {
        TokenContentController(
            context = applicationContext,
            scope = lifecycleScope,
            contentView = inspireContentView,
            contentLoader = contentLoader,
        )
    }

    private data class SelectedEditRange(
        val startIndex: Int,
        val endIndex: Int,
        val selectedText: String,
        val isWholeContent: Boolean,
    )

    companion object {
        const val TAG = "InspireContentActivity"
        private const val ANIMATION_DURATION_ENTER = 300L
        private const val ANIMATION_DURATION_EXIT = 200L

        // Material Design emphasized easing curves
        private val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

        fun start(context: Context) {
            TransparentActivityLauncher.start(
                context,
                Intent(context, InspireContentActivity::class.java)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        applySystemBarsVisibility()

        // Disable default Activity transition animation
        overridePendingTransition(R.anim.no_anim, R.anim.no_anim)

        setContentView(R.layout.activity_inspire_content)

        initViews()
        initAppearanceConfig()
        applyScreenContentGlassStyle()
        setupParticleView()

        loadInspireContent()
        showWithAnimation()
    }

    private fun initViews() {
        inspireContentView = findViewById(R.id.participleView)
        inspireContentRoot = findViewById(R.id.participleViewFrame)
        bottomEngineCard = findViewById(R.id.bottom_engine_card)
        enginePager = findViewById(R.id.engine_pager)
        engineManager = SearchEngineManagerInspire(
            activity = this,
            enginePager = enginePager,
            queryProvider = { inspireContentView.getSelectedTextNotEmpty() },
            imageHandleProvider = { acquireCurrentImageHandle() },
        )
        engineManager.initialize()
        applyWindowInsets()


        inspireContentRoot.alpha = 0f
        inspireContentRoot.translationY =
            resources.getDimensionPixelSize(R.dimen.slide_offset).toFloat()

        inspireContentRoot.setOnClickListener {
            finish()
        }
    }

    private fun initAppearanceConfig() {
        screenContentBackgroundBlurEnabled = runBlocking {
            settingsStore.readKey(KEY_SCREEN_CONTENT_BACKGROUND_BLUR, false)
        }
        screenContentCardBackgroundBlurEnabled = runBlocking {
            settingsStore.readKey(KEY_SCREEN_CONTENT_CARD_BACKGROUND_BLUR, false)
        }
    }

    private fun applyScreenContentGlassStyle() {
        GlassEffectUtils.applyWindowBlurProgress(window, 0f)
        inspireContentRoot.setBackgroundColor(Color.TRANSPARENT)
        // The fade runs on this parent, so fixing only the individual cards
        // still leaves their blur holes trapped in the parent's alpha layer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            inspireContentRoot.forceHasOverlappingRendering(
                !GlassEffectUtils.usesCardRegionBlur(
                    screenContentBackgroundBlurEnabled,
                    screenContentCardBackgroundBlurEnabled,
                )
            )
        }
        applyGlassCard(inspireContentView)
        applyGlassCard(bottomEngineCard)
        inspireContentView.applySelectedItemsBackgroundGlassStyle(
            screenContentCardBackgroundBlurEnabled
        )
    }

    private fun applyGlassCard(card: MaterialCardView) {
        GlassEffectUtils.applyCardStyle(
            card = card,
            color = DynamicColorUtils.surfaceContainerColor,
            glassEnabled = screenContentCardBackgroundBlurEnabled,
            backgroundBlurEnabled = screenContentBackgroundBlurEnabled,
            blurRegion = GlassEffectUtils.usesCardRegionBlur(
                screenContentBackgroundBlurEnabled,
                screenContentCardBackgroundBlurEnabled,
            ),
        )
        card.strokeWidth = 0
    }

    private fun applySystemBarsVisibility() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                hide(WindowInsetsCompat.Type.statusBars())
            } else {
                show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    private fun applyWindowInsets() {
        val initialPaddingLeft = inspireContentRoot.paddingLeft
        val initialPaddingTop = inspireContentRoot.paddingTop
        val initialPaddingRight = inspireContentRoot.paddingRight
        val initialPaddingBottom = inspireContentRoot.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(inspireContentRoot) { view, insets ->
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = initialPaddingLeft + systemInsets.left,
                top = initialPaddingTop + systemInsets.top,
                right = initialPaddingRight + systemInsets.right,
                bottom = initialPaddingBottom + systemInsets.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(inspireContentRoot)
    }

    override fun finish() {
        if (isFinishing) return
        if (launchingImageEditor) {
            isFinishing = true
            lifecycleScope.launch(Dispatchers.Main) {
                copyStrongContentToClipboardIfNeeded()
                GlassEffectUtils.applyWindowBlurProgress(window, 0f)
                super@InspireContentActivity.finish()
                overridePendingTransition(R.anim.no_anim, R.anim.no_anim)
            }
            return
        }
        isFinishing = true

        lifecycleScope.launch(Dispatchers.Main) {
            copyStrongContentToClipboardIfNeeded()
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = ANIMATION_DURATION_EXIT
                interpolator = EMPHASIZED_ACCELERATE

                addUpdateListener { animator ->
                    val fraction = animator.animatedValue as Float
                    inspireContentRoot.alpha = fraction
                    inspireContentRoot.translationY = (1f - fraction) *
                            resources.getDimensionPixelSize(R.dimen.slide_offset).toFloat()
                    if (screenContentBackgroundBlurEnabled) {
                        GlassEffectUtils.applyWindowBlurProgress(window, fraction)
                    }
                }

                doOnEnd {
                    super@InspireContentActivity.finish()
                    // Disable exit animation
                    overridePendingTransition(R.anim.no_anim, R.anim.no_anim)
                }

                start()
            }
        }
    }

    private suspend fun copyStrongContentToClipboardIfNeeded() {
        if (strongCopyHandled) return
        strongCopyHandled = true
        val strongCopyEnabled = settingsStore.readKey(
            InspireSettingsActivity.KEY_INSPIRE_STRONG_COPY,
            false
        )
        if (!strongCopyEnabled) return
        val content = inspireContentView.getSelectedTextNotEmpty()
        if (content.isNotEmpty()) {
            ClipboardUtils.copyText(applicationContext, content)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applySystemBarsVisibility()

        // Reset entry state
        isFinishing = false
        strongCopyHandled = false

        loadInspireContent()

        showWithAnimation()
    }

    private fun showWithAnimation() {
        // Appearance and layout must be ready before the first animated frame.
        inspireContentRoot.alpha = 0f
        inspireContentRoot.translationY =
            resources.getDimensionPixelSize(R.dimen.slide_offset).toFloat()

        inspireContentRoot.doOnPreDraw {
            if (isFinishing || isDestroyed) return@doOnPreDraw
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ANIMATION_DURATION_ENTER
                interpolator = EMPHASIZED_DECELERATE

                addUpdateListener { animator ->
                    val fraction = animator.animatedValue as Float
                    inspireContentRoot.alpha = fraction
                    inspireContentRoot.translationY = (1f - fraction) *
                            resources.getDimensionPixelSize(R.dimen.slide_offset).toFloat()
                    if (screenContentBackgroundBlurEnabled) {
                        GlassEffectUtils.applyWindowBlurProgress(window, fraction)
                    }
                }
                start()
            }
        }
    }

    private fun loadInspireContent() {
        contentLoadJob?.cancel()
        tokenContentController.reset()
        contentLoadJob = lifecycleScope.launch {
            screenshotBitmapHandle?.close()
            screenshotBitmapHandle = InspireDataHolder.acquireScreenshotBitmap()
            val loadPlan = contentLoader.buildLoadPlan(screenshotBitmapHandle)
            inspireContentView.setInitialTokenMode(loadPlan.initialTokenMode)
            inspireContentView.setRequestableTokenModes(loadPlan.requestableTokenModes)
            if (loadPlan.initialDisplayMode.isImageTextSelection) {
                showScreenshotPreview()
                inspireContentView.setDisplayMode(loadPlan.initialDisplayMode)
            } else {
                showScreenshotPreview()
                inspireContentView.switchToMode(loadPlan.initialTokenMode)
                requestTokenContent(loadPlan.initialTokenMode)
            }
            processOCRData(loadPlan)
        }
    }

    private suspend fun processOCRData(loadPlan: InspireContentLoadPlan) {
        if (!loadPlan.hasScreenshot) return
        val ocrResult = withContext(Dispatchers.Default) {
            screenshotBitmapHandle?.bitmapOrNull?.let { originalBitmap ->
                contentLoader.buildOcrResult(
                    originalBitmap = originalBitmap,
                )
            }
        } ?: return
        tokenContentController.applyOcrResult(ocrResult, loadPlan)
    }

    private suspend fun showScreenshotPreview() {
        screenshotBitmapHandle?.let { bitmapHandle: ManagedBitmap ->
            inspireContentView.setScreenShot(bitmapHandle)
        }
    }

    private fun setupParticleView() {
        inspireContentView.onTokenContentRequested = { mode ->
            requestTokenContent(mode)
        }
        inspireContentView.onRotateImageRequested = { degrees ->
            rotateScreenshotAndReOcr(degrees)
        }
        val actionWrapper = InspireActionDispatcher(
            activity = this,
            scope = lifecycleScope,
            contentView = inspireContentView,
            tokenContentController = tokenContentController,
            imageShareLauncher = imageShareLauncher,
            launchImageAction = ::launchImageAction,
            finishAndClearInspireData = ::finishAndClearInspireData,
            launchImageEditor = ::launchImageEditor,
            showSelectedTextEditor = ::showSelectedTextEditor,
            splitSegmentedSelectionOrItemToCharacters = ::splitSegmentedSelectionOrItemToCharacters,
        ).createActionWrapper()
        inspireContentView.setBigBangStatusListener(actionWrapper)
    }

    private fun rotateScreenshotAndReOcr(degrees: Int) {
        val currentHandle = screenshotBitmapHandle ?: return
        val currentBitmap = currentHandle.bitmapOrNull ?: return
        if (degrees % 360 == 0) return

        contentLoadJob?.cancel()
        tokenContentController.clearOcrData()
        inspireContentView.clearSelectedText()

        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotatedBitmap = try {
            currentBitmap.useSoftwareBitmap { safeBitmap ->
                Bitmap.createBitmap(
                    safeBitmap, 0, 0, safeBitmap.width, safeBitmap.height, matrix, true
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to rotate screenshot", t)
            return
        }

        val newHandle = ManagedBitmap.from(rotatedBitmap)
        screenshotBitmapHandle?.close()
        screenshotBitmapHandle = newHandle.acquire()
        InspireDataHolder.replaceScreenshotBitmap(newHandle)
        InspireDataHolder.replaceOcrAllowedTextBoxes(null)

        contentLoadJob = lifecycleScope.launch {
            try {
                inspireContentView.setScreenShot(newHandle)
                val currentMode = inspireContentView.getCurrentDisplayMode()
                val loadPlan = contentLoader.buildLoadPlan(newHandle).copy(
                    initialDisplayMode = currentMode
                )
                processOCRData(loadPlan)
            } finally {
                newHandle.close()
            }
        }
    }

    private fun requestTokenContent(mode: InspireTokenContentMode) {
        if (mode != InspireTokenContentMode.OCR || contentLoadJob?.isActive != true) {
            tokenContentController.requestMode(mode)
            return
        }
        lifecycleScope.launch {
            contentLoadJob?.join()
            tokenContentController.requestMode(mode)
        }
    }

    private fun launchImageEditor() {
        val imageHandle = acquireCurrentImageHandle() ?: screenshotBitmapHandle
        if (imageHandle != null) {
            InspireDataHolder.replaceScreenshotBitmap(imageHandle)
        }
        val intent = Intent(this, InspireImageEditorActivity::class.java)
        prepareForImageEditorLaunch()
        startActivity(intent, buildNoAnimOptions().toBundle())
        overridePendingTransition(R.anim.no_anim, R.anim.no_anim)
        launchingImageEditor = true
        finish()
    }

    private fun buildNoAnimOptions(): ActivityOptions {
        return ActivityOptions.makeCustomAnimation(this, R.anim.no_anim, R.anim.no_anim)
    }

    private fun prepareForImageEditorLaunch() {
        inspireContentView.resetForEditorLaunch()
    }

    private fun showSelectedTextEditor() {
        val currentMode = inspireContentView.getCurrentMode()
        val currentContents = inspireContentView.getCurrentContent().orEmpty()
        val selectionRange = getSelectedEditRange(currentContents)
        if (selectionRange == null) {
            ToastHelper.showInUIThread(this, R.string.inspire_edit_selected_text_contiguous_only)
            return
        }

        val scopeText = getString(
            if (selectionRange.isWholeContent) R.string.inspire_edit_scope_all
            else R.string.inspire_edit_scope_selected
        )

        showInspireEditBottomSheet(
            context = this,
            title = getString(R.string.inspire_edit_selected_text_title),
            hint = getString(R.string.inspire_edit_selected_text_hint),
            scopeText = scopeText,
            defaultText = selectionRange.selectedText,
            disableDragToDismiss = true,
            onConfirm = { editedText ->
                val updatedText = buildEditedText(currentContents, selectionRange, editedText)
                tokenContentController.applyEditedText(currentMode, updatedText)
            }
        )
    }

    private fun getSelectedEditRange(contents: List<String>): SelectedEditRange? {
        val selectedIndexes = inspireContentView.getSelectedContentIndexes()
        if (selectedIndexes.isEmpty()) {
            return SelectedEditRange(
                startIndex = 0,
                endIndex = contents.lastIndex,
                selectedText = contents.joinToString(""),
                isWholeContent = true,
            )
        }

        val startIndex: Int = selectedIndexes.first()
        val endIndex: Int = selectedIndexes.last()
        val selectedIndexSet = selectedIndexes.toHashSet()
        for (index in startIndex..endIndex) {
            if (selectedIndexSet.contains(index)) continue
            val content = contents.getOrNull(index) ?: return null
            if (!isHiddenContentToken(content)) {
                return null
            }
        }

        val selectedText = contents.subList(startIndex, endIndex + 1).joinToString("")
        return SelectedEditRange(startIndex, endIndex, selectedText, isWholeContent = false)
    }

    private fun buildEditedText(
        contents: List<String>,
        selectionRange: SelectedEditRange,
        editedSelectionText: String,
    ): String {
        return buildString {
            append(contents.take(selectionRange.startIndex).joinToString(""))
            append(editedSelectionText)
            append(contents.drop(selectionRange.endIndex + 1).joinToString(""))
        }
    }

    private fun isHiddenContentToken(content: String): Boolean {
        return content == BigBangView.ENTER_SYMBOL || content.contains(BigBangView.ENTER)
    }

    private fun splitSegmentedSelectionOrItemToCharacters(targetContentIndex: Int) {
        val selectedIndexes = inspireContentView.getSelectedContentIndexes()
        val selectedIndexSet = if (selectedIndexes.isNotEmpty()) {
            selectedIndexes.toSet()
        } else {
            setOf(targetContentIndex)
        }
        val currentMode = inspireContentView.getCurrentMode()
        val splitResult = splitContentsIntoCharacters(
            contents = tokenContentController.getContentsForMode(currentMode),
            targetIndex = targetContentIndex,
            selectedIndexes = selectedIndexSet,
        )
        tokenContentController.replaceModeContent(
            mode = currentMode,
            contents = splitResult.contents,
            selectedIndexes = splitResult.selectedIndexes,
            preserveScroll = true,
            changedIndex = targetContentIndex,
            changedContents = splitResult.changedContents,
        )
    }

    private fun splitContentsIntoCharacters(
        contents: List<String>,
        targetIndex: Int,
        selectedIndexes: Set<Int>,
    ): SplitContentsResult {
        val updatedContents = mutableListOf<String>()
        val updatedSelectedIndexes = mutableSetOf<Int>()
        val changedContents = mutableListOf<String>()
        contents.forEachIndexed { index, content ->
            if (index != targetIndex || isHiddenContentToken(content) || content.length <= 1) {
                if (selectedIndexes.contains(index)) {
                    updatedSelectedIndexes.add(updatedContents.size)
                }
                updatedContents.add(content)
            } else {
                content.forEach { char ->
                    if (selectedIndexes.contains(index)) {
                        updatedSelectedIndexes.add(updatedContents.size)
                    }
                    val charContent = char.toString()
                    updatedContents.add(charContent)
                    changedContents.add(charContent)
                }
            }
        }
        return SplitContentsResult(
            contents = updatedContents,
            selectedIndexes = updatedSelectedIndexes,
            changedContents = changedContents.ifEmpty { listOfNotNull(contents.getOrNull(targetIndex)) },
        )
    }

    fun acquireCurrentImageHandle(): ManagedBitmap? = inspireContentView.acquireImageHandle()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applySystemBarsVisibility()
        }
    }

    override fun onDestroy() {
        GlassEffectUtils.applyWindowBlur(window, false)
        contentLoadJob?.cancel()
        tokenContentController.cancel()
        imageActionJob?.cancel()
        inspireContentView.clear()
        screenshotBitmapHandle?.close()
        screenshotBitmapHandle = null
        if (launchingImageEditor) {
            InspireDataHolder.accessibilityContent = null
            InspireDataHolder.forceImageTextSelection = false
            InspireDataHolder.dragRect = null
        } else {
            InspireDataHolder.clear()
        }
        super.onDestroy()
    }

    private fun launchImageAction(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
        block: suspend () -> Unit,
    ) {
        imageActionJob?.cancel()
        imageActionJob = lifecycleScope.launch(dispatcher) {
            block()
        }
    }

    private suspend fun finishAndClearInspireData() {
        withContext(Dispatchers.Main) {
            InspireDataHolder.clear()
            finish()
        }
    }

    private data class SplitContentsResult(
        val contents: MutableList<String>,
        val selectedIndexes: Set<Int>,
        val changedContents: List<String>,
    )
}
