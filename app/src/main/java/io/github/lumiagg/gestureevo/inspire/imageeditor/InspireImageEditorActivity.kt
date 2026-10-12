package io.github.lumiagg.gestureevo.inspire.imageeditor

import android.annotation.SuppressLint
import androidx.core.graphics.drawable.toDrawable

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.view.animation.PathInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import com.google.android.material.button.MaterialButton
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityInspireImageEditorBinding
import io.github.lumiagg.gestureevo.databinding.PopupInspireImageEditorScrubBinding
import io.github.lumiagg.gestureevo.databinding.PopupInspireImageEditorSaveOptionsBinding
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceInspireEvent
import io.github.lumiagg.gestureevo.gesture.InspireEvent
import io.github.lumiagg.gestureevo.inspire.InspireDataHolder
import io.github.lumiagg.gestureevo.inspire.InspireHelperUtils
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.EditAction
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.EditorMode
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.EditorPoint
import io.github.lumiagg.gestureevo.inspire.imageeditor.model.ShapeType
import io.github.lumiagg.gestureevo.inspire.imageeditor.state.EditorSession
import io.github.lumiagg.gestureevo.inspire.imageeditor.state.EditorUiState
import io.github.lumiagg.gestureevo.inspire.imageeditor.ui.BrushSizePreviewView
import io.github.lumiagg.gestureevo.inspire.imageeditor.ui.InspireImageEditorView
import io.github.lumiagg.gestureevo.inspire.imageeditor.ui.ShapeTypePreviewView
import io.github.lumiagg.gestureevo.inspire.imageeditor.utils.InAppCardBlur
import io.github.lumiagg.gestureevo.inspire.ui.showInspireEditBottomSheet
import io.github.lumiagg.gestureevo.pin.PinPoolStore
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_DEFAULT_SHARE_IMAGE_APP
import io.github.lumiagg.gestureevo.utils.GalleryImageManager
import io.github.lumiagg.gestureevo.utils.ToastHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class InspireImageEditorActivity : AppCompatActivity() {

    private data class ScrubOption<T>(
        val value: T,
        val view: View,
    )

    private data class HorizontalScreenBounds(
        val left: Float,
        val right: Float,
    ) {
        fun centerX(): Float = (left + right) / 2f
    }

    private class CompositeTouchDelegate(view: View) : TouchDelegate(Rect(), view) {
        private val delegates = mutableListOf<TouchDelegate>()

        fun add(delegate: TouchDelegate) {
            delegates += delegate
        }

        fun isEmpty(): Boolean = delegates.isEmpty()

        override fun onTouchEvent(event: MotionEvent): Boolean {
            for (delegate in delegates.asReversed()) {
                if (delegate.onTouchEvent(event)) return true
            }
            return false
        }

        override fun onTouchExplorationHoverEvent(event: MotionEvent): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            for (delegate in delegates.asReversed()) {
                if (delegate.onTouchExplorationHoverEvent(event)) return true
            }
            return false
        }
    }

    companion object {
        const val EXTRA_IMAGE_CACHE_PATH = "extra_image_cache_path"
        private const val KEY_LAST_EDITOR_MODE = "inspire_image_editor_last_mode"
        private const val KEY_LAST_EDITOR_COLOR = "inspire_image_editor_last_color"
        private const val KEY_LAST_EDITOR_SHAPE_TYPE = "inspire_image_editor_last_shape_type"
        private const val IMAGE_ENTER_DURATION_MS = 340L
        private const val BAR_ENTER_DURATION_MS = 240L
        private const val BAR_ENTER_DELAY_MS = 0L
        private const val BRUSH_SIZE_MIN = 6f
        private const val BRUSH_SIZE_MAX = 120f
        private val IMAGE_ENTER_INTERPOLATOR = PathInterpolator(0.16f, 0f, 0f, 1f)
        private val BAR_ENTER_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
    }

    private lateinit var binding: ActivityInspireImageEditorBinding
    private val editorSession = EditorSession()
    private val imageShareLauncher by lazy { ImageShareLauncher(applicationContext) }

    private var cacheFile: File? = null
    private var sourceBitmapHandle: ManagedBitmap? = null
    private var loadBitmapJob: Job? = null
    private var exportJob: Job? = null
    private var isModeQuickToolsExpanded = false
    private val adaptiveStrokeWidths = mutableMapOf<EditorMode, Float>()
    private var lastPersistedMode: EditorMode? = null
    private var lastPersistedColor: Int? = null
    private var lastPersistedShapeType: ShapeType? = null
    private lateinit var modeButtons: List<Pair<MaterialButton, EditorMode>>
    private var enterAnimationPlayed = false
    private var saveOptionsPopup: PopupWindow? = null
    private var scrubPopupWindow: PopupWindow? = null
    private var colorScrubOptions: List<ScrubOption<Int>> = emptyList()
    private var shapeScrubOptions: List<ScrubOption<ShapeType>> = emptyList()
    private var brushScrubOptions: List<ScrubOption<Float>> = emptyList()
    private val brushDragTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private var colorScrubStartX = 0f
    private var isColorScrubbing = false
    private var shapeScrubStartX = 0f
    private var isShapeScrubbing = false
    private var brushDragStartX = 0f
    private var isBrushToolDragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(R.anim.no_anim, R.anim.no_anim)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityInspireImageEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cacheFile = intent.getStringExtra(EXTRA_IMAGE_CACHE_PATH)?.let(::File)
        binding.editorView.bindSession(editorSession)
        binding.editorView.callback = object : InspireImageEditorView.Callback {
            override fun onRequestAddText(anchor: EditorPoint, suggestedSize: Float) {
                showTextEditor(anchor, suggestedSize, null)
            }

            override fun onRequestEditText(action: EditAction.Text) {
                showTextEditor(action.anchor, action.textSize, action)
            }
        }

        setupToolbar()
        setupControls()
        applyWindowInsets()
        lifecycleScope.launch {
            restoreLastUsedEditorPreferences()
            observeSession()
            loadSourceBitmap()
        }
    }

    override fun onDestroy() {
        loadBitmapJob?.cancel()
        exportJob?.cancel()
        saveOptionsPopup?.dismiss()
        saveOptionsPopup = null
        dismissQuickToolPopup()
        dismissScrubPopup()
        binding.root.animate().cancel()
        binding.bottomPanel.animate().cancel()
        binding.editorView.clearImage()
        sourceBitmapHandle?.close()
        sourceBitmapHandle = null
        super.onDestroy()
        cacheFile?.takeIf { it.exists() }?.delete()
    }

    private fun setupToolbar() = with(binding) {
        btnClose.setOnClickListener { finish() }
    }

    private fun applyWindowInsets() = with(binding) {
        val bottomPanelMargin = (bottomPanel.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)
            ?.bottomMargin ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )

            bottomPanel.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                bottomMargin = bottomPanelMargin + systemBars.bottom
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupControls() = with(binding) {
        modeButtons = listOf(
            modeNavigate to EditorMode.NAVIGATE,
            modeCrop to EditorMode.CROP,
            modeDoodle to EditorMode.DOODLE,
            modeEraser to EditorMode.ERASER,
            modeShape to EditorMode.SHAPE,
            modeText to EditorMode.TEXT,
            modeMosaic to EditorMode.MOSAIC,
        )
        modeButtons.forEach { (button, mode) ->
            button.setOnClickListener {
                editorView.cancelOngoingInteraction()
                editorView.suppressMultiTouchTemporarily()
                val currentState = editorSession.state
                val isSameMode = currentState.currentMode == mode
                if (!isSameMode) {
                    editorSession.switchMode(mode)
                    isModeQuickToolsExpanded = supportsModeQuickTools(mode)
                    dismissScrubPopup()
                } else {
                    if (supportsModeQuickTools(mode)) {
                        isModeQuickToolsExpanded = true
                    }
                }
                renderModeSelection(editorSession.state.currentMode)
                renderModeQuickTools(editorSession.state)
                scrollSelectedModeIntoView(editorSession.state.currentMode)
            }
        }
        modeNavigate.setOnLongClickListener {
            editorView.resetViewport()
            true
        }
        renderModeSelection(EditorMode.NAVIGATE)
        renderColorSelection(Color.RED)
        setupCompactColorControls()
        setupCompactBrushControls()
        setupCompactShapeControls()

        InAppCardBlur.apply(bottomBarCard, editorView)
        InAppCardBlur.apply(modeQuickToolsCard, editorView)


        btnUndo.setOnClickListener { editorSession.undo() }
        btnRedo.setOnClickListener { editorSession.redo() }
        btnTopClearCrop.setOnClickListener { editorView.clearCropSelection() }
        btnTopApplyCrop.setOnClickListener {
            editorView.cancelOngoingInteraction()
            val applied = editorView.applyCropSelection()
            if (applied) {
                applyAdaptiveBrushSizeIfNeeded()
                dismissQuickToolPopup()
                editorSession.switchMode(EditorMode.NAVIGATE)
                editorView.suppressMultiTouchTemporarily()
            } else {
                ToastHelper.showInUIThread(this@InspireImageEditorActivity, R.string.inspire_image_edit_crop_empty)
            }
        }
        btnPin.setOnClickListener { pinEditedBitmapToScreen() }
        btnPin.setOnLongClickListener {
            addEditedBitmapToPinPool()
            true
        }
        btnCopy.setOnClickListener { copyEditedBitmap() }
        btnShare.setOnClickListener { shareEditedBitmap(isPress = false) }
        btnShare.setOnLongClickListener {
            shareEditedBitmap(isPress = true)
            true
        }
        btnSave.setOnClickListener { showSaveOptions(it) }
        compactAddTextButton.setOnClickListener {
            val anchor = editorView.visibleImageCenterPoint() ?: return@setOnClickListener
            showTextEditor(anchor, editorView.suggestedTextSizeForInsert(), null)
        }
    }

    private fun observeSession() {
        editorSession.addListener { state ->
            binding.btnUndo.isEnabled = state.canUndo
            binding.btnRedo.isEnabled = state.canRedo
            renderModeSelection(state.currentMode)
            renderColorSelection(state.currentColor)
            renderShapeSelection(state.currentShapeType)
            renderBrushControls(state)
            renderModeQuickTools(state)
            persistLastUsedEditorPreferencesIfChanged(state)
        }
    }

    private suspend fun restoreLastUsedEditorPreferences() {
        val savedModeName = settingsStore.readKey(KEY_LAST_EDITOR_MODE, EditorMode.NAVIGATE.name)
        val restoredMode = EditorMode.entries.firstOrNull { it.name == savedModeName } ?: EditorMode.NAVIGATE
        val restoredColor = settingsStore.readKey(KEY_LAST_EDITOR_COLOR, Color.RED)
        val savedShapeTypeName = settingsStore.readKey(KEY_LAST_EDITOR_SHAPE_TYPE, ShapeType.RECTANGLE.name)
        val restoredShapeType =
            ShapeType.entries.firstOrNull { it.name == savedShapeTypeName } ?: ShapeType.RECTANGLE

        lastPersistedMode = restoredMode
        lastPersistedColor = restoredColor
        lastPersistedShapeType = restoredShapeType
        isModeQuickToolsExpanded = supportsModeQuickTools(restoredMode)

        if (editorSession.state.currentColor != restoredColor) {
            editorSession.updateColor(restoredColor)
        }
        if (editorSession.state.currentShapeType != restoredShapeType) {
            editorSession.updateShapeType(restoredShapeType)
        }
        if (editorSession.state.currentMode != restoredMode) {
            editorSession.switchMode(restoredMode)
        }
    }

    private fun persistLastUsedEditorPreferencesIfChanged(state: EditorUiState) {
        val modeChanged = lastPersistedMode != state.currentMode
        val colorChanged = lastPersistedColor != state.currentColor
        val shapeTypeChanged = lastPersistedShapeType != state.currentShapeType
        if (!modeChanged && !colorChanged && !shapeTypeChanged) return

        lastPersistedMode = state.currentMode
        lastPersistedColor = state.currentColor
        lastPersistedShapeType = state.currentShapeType
        lifecycleScope.launch {
            if (modeChanged) {
                settingsStore.saveKey(KEY_LAST_EDITOR_MODE, state.currentMode.name)
            }
            if (colorChanged) {
                settingsStore.saveKey(KEY_LAST_EDITOR_COLOR, state.currentColor)
            }
            if (shapeTypeChanged) {
                settingsStore.saveKey(KEY_LAST_EDITOR_SHAPE_TYPE, state.currentShapeType.name)
            }
        }
    }

    private fun renderBrushControls(state: EditorUiState) {
        if (!supportsBrushSize(state.currentMode)) return
        renderCompactBrushPreview(state)
    }

    private fun renderModeSelection(currentMode: EditorMode) {
        val selectedBackground = DynamicColorUtils.secondaryContainerColor
        val selectedText = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSecondaryContainer)
        val defaultBackground = Color.TRANSPARENT
        val defaultText = DynamicColorUtils.onSurfaceVariantColor

        modeButtons.forEach { (button, mode) ->
            val selected = mode == currentMode
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) selectedBackground else defaultBackground
            )
            button.setTextColor(if (selected) selectedText else defaultText)
        }
    }

    private fun renderColorSelection(currentColor: Int) {
        val outlineColor = DynamicColorUtils.outlineColor
        val swatch = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * 5f
            setColor(currentColor)
            setStroke((resources.displayMetrics.density * 1f).roundToInt(), outlineColor)
        }
        val accentDot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(dpToPx(5), dpToPx(5))
            setColor(
                if (currentColor == Color.WHITE) {
                    Color.argb(120, 0, 0, 0)
                } else {
                    Color.argb(110, 255, 255, 255)
                }
            )
        }
        binding.compactColorPreview.background = LayerDrawable(arrayOf(swatch, accentDot)).apply {
            setLayerInset(1, dpToPx(10), dpToPx(2), dpToPx(1), dpToPx(9))
        }
        binding.compactColorPanel.contentDescription = colorDescription(currentColor)
        updateCompactColorSelection(currentColor)
    }

    private fun renderModeQuickTools(state: EditorUiState) {
        val isCropMode = state.currentMode == EditorMode.CROP
        val supportsQuickTools = supportsModeQuickTools(state.currentMode)
        if (!supportsQuickTools) {
            isModeQuickToolsExpanded = false
            dismissScrubPopup()
        }

        val showHost = !isCropMode && isModeQuickToolsExpanded && supportsQuickTools
        binding.modeQuickToolsHost.isVisible = showHost
        binding.modeQuickToolsCard.isVisible = showHost
        binding.modeQuickToolsContainer.isVisible = showHost
        binding.cropActionsGroup.isVisible = isCropMode
        binding.defaultTopActionsGroup.isVisible = !isCropMode
        binding.compactColorPanel.isVisible = showHost && supportsColorSelection(state.currentMode)
        binding.compactShapePanel.isVisible = showHost && state.currentMode == EditorMode.SHAPE
        binding.compactBrushPanel.isVisible = showHost && supportsBrushSize(state.currentMode)
        binding.compactAddTextButton.isVisible = showHost && state.currentMode == EditorMode.TEXT

        if (!binding.compactBrushPanel.isVisible) {
            binding.editorView.setCenterBrushPreviewVisible(false)
        }

        renderModeQuickToolsContainer(showHost)
        renderCompactTextButtonState(binding.compactAddTextButton, binding.compactAddTextButton.isVisible)
        binding.root.post { updateQuickToolTouchDelegates() }
    }

    private fun renderModeQuickToolsContainer(showHost: Boolean) {
        val visiblePanels = listOf(
            binding.compactAddTextButton,
            binding.compactBrushPanel,
            binding.compactShapePanel,
            binding.compactColorPanel,
        ).filter { it.isVisible }

        binding.modeQuickToolsCard.isVisible = showHost && visiblePanels.isNotEmpty()
        binding.modeQuickToolsContainer.background = null

        visiblePanels.forEachIndexed { index, panel ->
            panel.background = null
            panel.alpha = 1f
            val layoutParams = panel.layoutParams as? ViewGroup.MarginLayoutParams
            layoutParams?.marginStart = if (index == 0) 0 else dpToPx(1)
            panel.layoutParams = layoutParams
        }
    }

    private fun supportsColorSelection(mode: EditorMode): Boolean {
        return mode == EditorMode.DOODLE ||
            mode == EditorMode.SHAPE ||
            mode == EditorMode.TEXT
    }

    private fun supportsModeQuickTools(mode: EditorMode): Boolean {
        return supportsColorSelection(mode) || supportsBrushSize(mode) || mode == EditorMode.SHAPE
    }

    private fun dismissQuickToolPopup() {
        isModeQuickToolsExpanded = false
        dismissScrubPopup()
        binding.editorView.setCenterBrushPreviewVisible(false)
        renderModeQuickTools(editorSession.state)
    }

    // The delegated touch handler calls performClick for taps and consumes scrubbing gestures.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupCompactColorControls() {
        binding.compactColorPanel.setOnClickListener {
            toggleColorScrubPopup(it)
        }
        binding.compactColorPanel.setOnTouchListener { view, event ->
            handleCompactColorPreviewTouch(view, event)
        }
    }

    private fun handleCompactColorPreviewTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                colorScrubStartX = event.rawX
                isColorScrubbing = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val delta = event.rawX - colorScrubStartX
                if (!isColorScrubbing && abs(delta) > brushDragTouchSlop) {
                    isColorScrubbing = true
                    showColorScrubPopup(view)
                }
                if (isColorScrubbing) {
                    ensureColorScrubPopup(view)
                    updateColorFromHorizontalScrub(event.rawX)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isColorScrubbing) {
                    dismissScrubPopup()
                } else {
                    view.performClick()
                }
                isColorScrubbing = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dismissScrubPopup()
                isColorScrubbing = false
                return true
            }
        }
        return false
    }

    private fun updateCompactColorSelection(currentColor: Int) {
        updateScrubSelection(
            options = colorScrubOptions,
            selected = currentColor,
            applySelection = { view, color, isSelected ->
                applyColorScrubSelection(view, color, isSelected)
            }
        )
    }

    private fun availableColors(): List<Int> {
        return listOf(
            Color.BLACK,
            Color.RED,
            Color.rgb(255, 152, 0),
            Color.rgb(255, 235, 59),
            Color.rgb(76, 175, 80),
            Color.rgb(33, 150, 243),
            Color.rgb(156, 39, 176),
            Color.WHITE,
        )
    }

    private fun createColorScrubView(color: Int): View {
        return FrameLayout(this).apply {
            layoutParams = ViewGroup.MarginLayoutParams(dpToPx(24), dpToPx(24)).apply {
                marginStart = dpToPx(3)
                marginEnd = dpToPx(3)
            }
            background = androidx.core.content.ContextCompat.getDrawable(
                this@InspireImageEditorActivity,
                colorSwatchDrawableRes(color)
            )
        }
    }

    private fun applyColorScrubSelection(view: View, color: Int, selected: Boolean) {
        val selectedScale = 1.06f
        view.alpha = if (selected) 1f else 0.68f
        view.scaleX = if (selected) selectedScale else 1f
        view.scaleY = if (selected) selectedScale else 1f
        view.foreground = if (selected) {
            androidx.core.content.ContextCompat.getDrawable(
                this,
                R.drawable.inspire_editor_color_selected_ring
            )
        } else {
            null
        }
    }

    private fun colorSwatchDrawableRes(color: Int): Int {
        return when (color) {
            Color.BLACK -> R.drawable.inspire_editor_color_black
            Color.RED -> R.drawable.inspire_editor_color_red
            Color.rgb(255, 152, 0) -> R.drawable.inspire_editor_color_orange
            Color.rgb(255, 235, 59) -> R.drawable.inspire_editor_color_yellow
            Color.rgb(76, 175, 80) -> R.drawable.inspire_editor_color_green
            Color.rgb(33, 150, 243) -> R.drawable.inspire_editor_color_blue
            Color.rgb(156, 39, 176) -> R.drawable.inspire_editor_color_purple
            else -> R.drawable.inspire_editor_color_white
        }
    }

    private fun <T> showScrubPopup(
        anchor: View,
        options: List<T>,
        selected: T,
        createView: (T) -> View,
        applySelection: (View, T, Boolean) -> Unit,
        onOptionClick: (T) -> Unit,
    ): List<ScrubOption<T>> {
        dismissScrubPopup()

        val popupBinding = PopupInspireImageEditorScrubBinding.inflate(LayoutInflater.from(this))
        popupBinding.scrubPopupRoot.background = buildScrubPopupBackground()

        val scrubOptions = options.map { value ->
            val itemView = createView(value)
            itemView.isClickable = true
            itemView.isFocusable = true
            itemView.setOnClickListener {
                onOptionClick(value)
                dismissScrubPopup()
            }
            popupBinding.scrubOptionsRow.addView(itemView)
            ScrubOption(value = value, view = itemView)
        }
        updateScrubSelection(scrubOptions, selected, applySelection)

        val popupWindow = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isTouchable = true
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            elevation = resources.displayMetrics.density * 10f
            setOnDismissListener {
                if (scrubPopupWindow === this) {
                    scrubPopupWindow = null
                    colorScrubOptions = emptyList()
                    shapeScrubOptions = emptyList()
                    brushScrubOptions = emptyList()
                }
            }
        }

        popupBinding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val horizontalOffset = ((anchor.width - popupBinding.root.measuredWidth) / 2f).roundToInt()
        val verticalGap = dpToPx(8)
        val verticalOffset = -(anchor.height + popupBinding.root.measuredHeight + verticalGap)
        popupWindow.showAsDropDown(anchor, horizontalOffset, verticalOffset)
        scrubPopupWindow = popupWindow
        return scrubOptions
    }

    private fun dismissScrubPopup() {
        scrubPopupWindow?.dismiss()
        scrubPopupWindow = null
        colorScrubOptions = emptyList()
        shapeScrubOptions = emptyList()
        brushScrubOptions = emptyList()
    }

    private fun <T> updateScrubSelection(
        options: List<ScrubOption<T>>,
        selected: T,
        applySelection: (View, T, Boolean) -> Unit,
    ) {
        options.forEach { option ->
            applySelection(option.view, option.value, option.value == selected)
        }
    }

    private fun <T> scrubValueAtRawX(
        options: List<ScrubOption<T>>,
        rawX: Float,
    ): T? {
        if (options.isEmpty()) return null

        val hitOption = options.firstOrNull { option ->
            val bounds = option.view.screenBoundsX() ?: return@firstOrNull false
            rawX in bounds.left..bounds.right
        }
        if (hitOption != null) return hitOption.value

        return options.minByOrNull { option ->
            val bounds = option.view.screenBoundsX() ?: return@minByOrNull Float.MAX_VALUE
            abs(bounds.centerX() - rawX)
        }?.value
    }

    private fun View.screenBoundsX(): HorizontalScreenBounds? {
        val widthPx = width.takeIf { it > 0 } ?: measuredWidth
        if (widthPx <= 0) return null
        val location = IntArray(2)
        getLocationOnScreen(location)
        val left = location[0].toFloat()
        return HorizontalScreenBounds(left = left, right = left + widthPx)
    }

    private fun buildScrubPopupBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * 18f
            setColor(DynamicColorUtils.surfaceContainerHighColor)
            setStroke(
                (resources.displayMetrics.density * 1f).roundToInt(),
                DynamicColorUtils.outlineColor
            )
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    private fun ensureColorScrubPopup(anchor: View) {
        if (scrubPopupWindow?.isShowing == true && colorScrubOptions.isNotEmpty()) return
        showColorScrubPopup(anchor)
    }

    private fun isColorScrubPopupShowing(): Boolean {
        return scrubPopupWindow?.isShowing == true && colorScrubOptions.isNotEmpty()
    }

    private fun toggleColorScrubPopup(anchor: View) {
        if (isColorScrubPopupShowing()) {
            dismissScrubPopup()
        } else {
            showColorScrubPopup(anchor)
        }
    }

    private fun showColorScrubPopup(anchor: View) {
        colorScrubOptions = showScrubPopup(
            anchor = anchor,
            options = availableColors(),
            selected = editorSession.state.currentColor,
            createView = ::createColorScrubView,
            applySelection = { view, color, selected ->
                applyColorScrubSelection(view, color, selected)
            },
            onOptionClick = { color ->
                if (editorSession.state.currentColor != color) {
                    editorSession.updateColor(color)
                }
            }
        )
        shapeScrubOptions = emptyList()
    }

    private fun updateColorFromHorizontalScrub(rawX: Float) {
        val color = scrubValueAtRawX(colorScrubOptions, rawX) ?: return
        if (editorSession.state.currentColor != color) {
            editorSession.updateColor(color)
        }
    }

    // The delegated touch handler calls performClick for taps and consumes scrubbing gestures.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupCompactBrushControls() {
        binding.compactBrushPanel.setOnClickListener {
            toggleBrushScrubPopup(it)
        }
        binding.compactBrushPanel.setOnTouchListener { view, event ->
            handleCompactBrushTouch(view, event)
        }
    }

    private fun updateQuickToolTouchDelegates() {
        val bottomBarTop = descendantRectInAncestor(binding.bottomBarCard, binding.root)?.top
            ?: binding.root.height
        val delegateGroup = CompositeTouchDelegate(binding.root).apply {
            addQuickToolTouchDelegate(binding.compactAddTextButton, bottomBarTop)
            addQuickToolTouchDelegate(binding.compactBrushPanel, bottomBarTop)
            addQuickToolTouchDelegate(binding.compactShapePanel, bottomBarTop)
            addQuickToolTouchDelegate(binding.compactColorPanel, bottomBarTop)
        }

        binding.root.touchDelegate = if (delegateGroup.isEmpty()) {
            null
        } else {
            delegateGroup
        }
    }

    private fun CompositeTouchDelegate.addQuickToolTouchDelegate(target: View, bottomBarTop: Int) {
        if (!target.isVisible) return
        val targetRect = descendantRectInAncestor(target, binding.root) ?: return
        val touchRect = Rect(targetRect).apply {
            left -= dpToPx(6)
            right += dpToPx(6)
            top -= dpToPx(16)
            bottom = maxOf(bottom + dpToPx(14), bottomBarTop)
            left = left.coerceAtLeast(0)
            top = top.coerceAtLeast(0)
            right = right.coerceAtMost(binding.root.width)
            bottom = bottom.coerceAtMost(binding.root.height)
        }
        add(TouchDelegate(touchRect, target))
    }

    private fun descendantRectInAncestor(descendant: View, ancestor: View): Rect? {
        val descendantWidth = descendant.width.takeIf { it > 0 } ?: descendant.measuredWidth
        val descendantHeight = descendant.height.takeIf { it > 0 } ?: descendant.measuredHeight
        val ancestorWidth = ancestor.width.takeIf { it > 0 } ?: ancestor.measuredWidth
        val ancestorHeight = ancestor.height.takeIf { it > 0 } ?: ancestor.measuredHeight
        if (descendantWidth <= 0 || descendantHeight <= 0 || ancestorWidth <= 0 || ancestorHeight <= 0) {
            return null
        }

        val descendantLocation = IntArray(2)
        val ancestorLocation = IntArray(2)
        descendant.getLocationOnScreen(descendantLocation)
        ancestor.getLocationOnScreen(ancestorLocation)
        val left = descendantLocation[0] - ancestorLocation[0]
        val top = descendantLocation[1] - ancestorLocation[1]
        return Rect(left, top, left + descendantWidth, top + descendantHeight)
    }

    private fun renderCompactBrushPreview(state: EditorUiState) {
        val surfaceColor = DynamicColorUtils.surfaceContainerColor
        val previewColor = currentBrushPreviewColor(state)
        val outlineColor = currentBrushOutlineColor(state, previewColor)
        binding.compactBrushPreview.setOutlineColor(outlineColor)
        binding.compactBrushPreview.setBrushPreview(
            sizePx = state.currentStrokeWidth,
            color = if (state.currentMode == EditorMode.ERASER) surfaceColor else previewColor,
            hollow = state.currentMode == EditorMode.ERASER,
        )
        binding.compactBrushPanel.contentDescription =
            getString(R.string.inspire_image_edit_brush_value, state.currentStrokeWidth.roundToInt())
        updateBrushScrubSelection(state.currentStrokeWidth, state)
    }

    private fun currentBrushPreviewColor(state: EditorUiState): Int {
        return when (state.currentMode) {
            EditorMode.MOSAIC -> DynamicColorUtils.onSurfaceVariantColor

            else -> state.currentColor
        }
    }

    private fun currentBrushOutlineColor(state: EditorUiState, previewColor: Int): Int {
        val onSurfaceColor = DynamicColorUtils.onSurfaceColor
        return if (previewColor == Color.WHITE || state.currentMode == EditorMode.ERASER) {
            onSurfaceColor
        } else {
            Color.WHITE
        }
    }

    private fun availableBrushSizes(): List<Float> {
        return listOf(6f, 12f, 20f, 32f, 48f, 72f, 96f, 120f)
    }

    private fun createBrushScrubView(sizePx: Float): View {
        val state = editorSession.state
        val previewColor = currentBrushPreviewColor(state)
        val outlineColor = currentBrushOutlineColor(state, previewColor)
        val surfaceColor = DynamicColorUtils.surfaceContainerColor
        return FrameLayout(this).apply {
            layoutParams = ViewGroup.MarginLayoutParams(dpToPx(30), dpToPx(28)).apply {
                marginStart = dpToPx(2)
                marginEnd = dpToPx(2)
            }
            addView(
                BrushSizePreviewView(this@InspireImageEditorActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setPadding(dpToPx(3), dpToPx(3), dpToPx(3), dpToPx(3))
                    setOutlineColor(outlineColor)
                    setBrushPreview(
                        sizePx = sizePx,
                        color = if (state.currentMode == EditorMode.ERASER) surfaceColor else previewColor,
                        hollow = state.currentMode == EditorMode.ERASER,
                    )
                }
            )
        }
    }

    private fun applyBrushScrubSelection(
        view: View,
        sizePx: Float,
        selected: Boolean,
        state: EditorUiState,
    ) {
        val previewColor = currentBrushPreviewColor(state)
        val outlineColor = currentBrushOutlineColor(state, previewColor)
        val surfaceColor = DynamicColorUtils.surfaceContainerColor
        (view as? FrameLayout)?.getChildAt(0)?.let { child ->
            (child as? BrushSizePreviewView)?.apply {
                setOutlineColor(outlineColor)
                setBrushPreview(
                    sizePx = sizePx,
                    color = if (state.currentMode == EditorMode.ERASER) surfaceColor else previewColor,
                    hollow = state.currentMode == EditorMode.ERASER,
                )
            }
        }
        view.alpha = if (selected) 1f else 0.72f
        view.scaleX = if (selected) 1.05f else 1f
        view.scaleY = if (selected) 1.05f else 1f
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * 12f
            setColor(
                if (selected) {
                    DynamicColorUtils.secondaryContainerColor
                } else {
                    Color.TRANSPARENT
                }
            )
            setStroke(
                (resources.displayMetrics.density * 0.8f).roundToInt(),
                if (selected) {
                    MaterialColors.getColor(
                        binding.root,
                        com.google.android.material.R.attr.colorSecondary
                    )
                } else {
                    Color.TRANSPARENT
                }
            )
        }
    }

    private fun ensureBrushScrubPopup(anchor: View) {
        if (scrubPopupWindow?.isShowing == true && brushScrubOptions.isNotEmpty()) return
        showBrushScrubPopup(anchor)
    }

    private fun isBrushScrubPopupShowing(): Boolean {
        return scrubPopupWindow?.isShowing == true && brushScrubOptions.isNotEmpty()
    }

    private fun toggleBrushScrubPopup(anchor: View) {
        if (isBrushScrubPopupShowing()) {
            dismissScrubPopup()
        } else {
            showBrushScrubPopup(anchor)
        }
    }

    private fun showBrushScrubPopup(anchor: View) {
        val state = editorSession.state
        val selectedSize = availableBrushSizes().minByOrNull { abs(it - state.currentStrokeWidth) } ?: state.currentStrokeWidth
        brushScrubOptions = showScrubPopup(
            anchor = anchor,
            options = availableBrushSizes(),
            selected = selectedSize,
            createView = ::createBrushScrubView,
            applySelection = { view, sizePx, selected ->
                applyBrushScrubSelection(view, sizePx, selected, state)
            },
            onOptionClick = { sizePx ->
                if (abs(editorSession.state.currentStrokeWidth - sizePx) > 0.5f) {
                    editorSession.updateStrokeWidth(sizePx)
                }
            }
        )
        colorScrubOptions = emptyList()
        shapeScrubOptions = emptyList()
    }

    private fun updateBrushScrubSelection(currentWidth: Float, state: EditorUiState) {
        val selectedSize = brushScrubOptions.minByOrNull { abs(it.value - currentWidth) }?.value ?: return
        updateScrubSelection(
            options = brushScrubOptions,
            selected = selectedSize,
            applySelection = { view, sizePx, selected ->
                applyBrushScrubSelection(view, sizePx, selected, state)
            }
        )
    }

    private fun updateBrushFromHorizontalScrub(rawX: Float) {
        val sizePx = scrubValueAtRawX(brushScrubOptions, rawX) ?: return
        if (abs(editorSession.state.currentStrokeWidth - sizePx) > 0.5f) {
            editorSession.updateStrokeWidth(sizePx)
        }
    }

    private fun handleCompactBrushTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                brushDragStartX = event.rawX
                isBrushToolDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val delta = event.rawX - brushDragStartX
                if (!isBrushToolDragging && abs(delta) > brushDragTouchSlop) {
                    isBrushToolDragging = true
                    binding.editorView.setCenterBrushPreviewVisible(true)
                    showBrushScrubPopup(view)
                }
                if (isBrushToolDragging) {
                    ensureBrushScrubPopup(view)
                    updateBrushFromHorizontalScrub(event.rawX)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isBrushToolDragging) {
                    binding.editorView.setCenterBrushPreviewVisible(false)
                    dismissScrubPopup()
                } else {
                    view.performClick()
                }
                isBrushToolDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                binding.editorView.setCenterBrushPreviewVisible(false)
                dismissScrubPopup()
                isBrushToolDragging = false
                return true
            }
        }
        return false
    }

    // The delegated touch handler calls performClick for taps and consumes scrubbing gestures.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupCompactShapeControls() {
        binding.compactShapePreview.setShapePreview(ShapeType.RECTANGLE, Color.WHITE)
        binding.compactShapePanel.setOnClickListener {
            toggleShapeScrubPopup(it)
        }
        binding.compactShapePanel.setOnTouchListener { view, event ->
            handleCompactShapeTouch(view, event)
        }
    }

    private fun renderShapeSelection(currentShapeType: ShapeType) {
        val tintColor = if (editorSession.state.currentColor == Color.WHITE) {
            DynamicColorUtils.onSurfaceColor
        } else {
            editorSession.state.currentColor
        }
        binding.compactShapePreview.setShapePreview(currentShapeType, tintColor)
        updateShapeScrubSelection(currentShapeType, tintColor)
        binding.compactShapePanel.contentDescription = shapeDescription(currentShapeType)
    }

    private fun availableShapeTypes(): List<ShapeType> {
        return listOf(
            ShapeType.RECTANGLE,
            ShapeType.ROUNDED_RECTANGLE,
            ShapeType.OVAL,
            ShapeType.LINE,
            ShapeType.ARROW,
            ShapeType.DIAMOND,
            ShapeType.TRIANGLE,
        )
    }

    private fun createShapeScrubView(shapeType: ShapeType): View {
        return FrameLayout(this).apply {
            layoutParams = ViewGroup.MarginLayoutParams(dpToPx(30), dpToPx(24)).apply {
                marginStart = dpToPx(2)
                marginEnd = dpToPx(2)
            }

            addView(
                ShapeTypePreviewView(this@InspireImageEditorActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                    setShapePreview(shapeType, editorSession.state.currentColor)
                }
            )
        }
    }

    private fun applyShapeScrubSelection(
        view: View,
        shapeType: ShapeType,
        selected: Boolean,
        tintColor: Int,
    ) {
        (view as? FrameLayout)?.getChildAt(0)?.let { child ->
            (child as? ShapeTypePreviewView)?.setShapePreview(shapeType, tintColor)
        }

        view.alpha = if (selected) 1f else 0.72f
        view.scaleX = if (selected) 1.05f else 1f
        view.scaleY = if (selected) 1.05f else 1f
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * 12f
            setColor(
                if (selected) {
                    DynamicColorUtils.secondaryContainerColor
                } else {
                    Color.TRANSPARENT
                }
            )
            setStroke(
                (resources.displayMetrics.density * 0.8f).roundToInt(),
                if (selected) {
                    MaterialColors.getColor(
                        binding.root,
                        com.google.android.material.R.attr.colorSecondary
                    )
                } else {
                    Color.TRANSPARENT
                }
            )
        }
    }

    private fun ensureShapeScrubPopup(anchor: View) {
        if (scrubPopupWindow?.isShowing == true && shapeScrubOptions.isNotEmpty()) return
        showShapeScrubPopup(anchor)
    }

    private fun isShapeScrubPopupShowing(): Boolean {
        return scrubPopupWindow?.isShowing == true && shapeScrubOptions.isNotEmpty()
    }

    private fun toggleShapeScrubPopup(anchor: View) {
        if (isShapeScrubPopupShowing()) {
            dismissScrubPopup()
        } else {
            showShapeScrubPopup(anchor)
        }
    }

    private fun showShapeScrubPopup(anchor: View) {
        val tintColor = if (editorSession.state.currentColor == Color.WHITE) {
            DynamicColorUtils.onSurfaceColor
        } else {
            editorSession.state.currentColor
        }
        shapeScrubOptions = showScrubPopup(
            anchor = anchor,
            options = availableShapeTypes(),
            selected = editorSession.state.currentShapeType,
            createView = ::createShapeScrubView,
            applySelection = { view, shapeType, selected ->
                applyShapeScrubSelection(view, shapeType, selected, tintColor)
            },
            onOptionClick = { shapeType ->
                if (editorSession.state.currentShapeType != shapeType) {
                    editorSession.updateShapeType(shapeType)
                }
            }
        )
        colorScrubOptions = emptyList()
    }

    private fun updateShapeFromHorizontalScrub(rawX: Float) {
        val shapeType = scrubValueAtRawX(shapeScrubOptions, rawX) ?: return
        if (editorSession.state.currentShapeType != shapeType) {
            editorSession.updateShapeType(shapeType)
        }
    }

    private fun updateShapeScrubSelection(currentShapeType: ShapeType, tintColor: Int) {
        updateScrubSelection(
            options = shapeScrubOptions,
            selected = currentShapeType,
            applySelection = { view, shapeType, selected ->
                applyShapeScrubSelection(view, shapeType, selected, tintColor)
            }
        )
    }

    private fun handleCompactShapeTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                shapeScrubStartX = event.rawX
                isShapeScrubbing = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val delta = event.rawX - shapeScrubStartX
                if (!isShapeScrubbing && abs(delta) > brushDragTouchSlop) {
                    isShapeScrubbing = true
                    showShapeScrubPopup(view)
                }
                if (isShapeScrubbing) {
                    ensureShapeScrubPopup(view)
                    updateShapeFromHorizontalScrub(event.rawX)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isShapeScrubbing) {
                    dismissScrubPopup()
                } else {
                    view.performClick()
                }
                isShapeScrubbing = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dismissScrubPopup()
                isShapeScrubbing = false
                return true
            }
        }
        return false
    }

    private fun renderCompactTextButtonState(button: MaterialButton, visible: Boolean) {
        if (!visible) return
        button.backgroundTintList = ColorStateList.valueOf(
            Color.TRANSPARENT
        )
        button.setTextColor(
            DynamicColorUtils.onSurfaceColor
        )
        button.strokeWidth = 0
        button.cornerRadius = 0
    }

    private fun scrollSelectedModeIntoView(mode: EditorMode) {
        val target = modeButtons.firstOrNull { it.second == mode }?.first ?: return
        binding.modeActionsScroll.post {
            val viewportWidth = binding.modeActionsScroll.width
            if (viewportWidth <= 0) return@post
            val left = binding.modeActionsRow.left + target.left
            val right = left + target.width
            val currentScroll = binding.modeActionsScroll.scrollX
            val desiredScroll = when {
                left < currentScroll -> left
                right > currentScroll + viewportWidth -> right - viewportWidth
                else -> currentScroll
            }.coerceAtLeast(0)
            if (desiredScroll != currentScroll) {
                binding.modeActionsScroll.smoothScrollTo(desiredScroll, 0)
            }
        }
    }

    private fun colorDescription(color: Int): String {
        val labelRes = when (color) {
            Color.BLACK -> R.string.inspire_image_edit_color_black
            Color.RED -> R.string.inspire_image_edit_color_red
            Color.rgb(255, 152, 0) -> R.string.inspire_image_edit_color_orange
            Color.rgb(255, 235, 59) -> R.string.inspire_image_edit_color_yellow
            Color.rgb(76, 175, 80) -> R.string.inspire_image_edit_color_green
            Color.rgb(33, 150, 243) -> R.string.inspire_image_edit_color_blue
            Color.rgb(156, 39, 176) -> R.string.inspire_image_edit_color_purple
            else -> R.string.inspire_image_edit_color_white
        }
        return getString(labelRes)
    }

    private fun shapeDescription(shapeType: ShapeType): String {
        val labelRes = when (shapeType) {
            ShapeType.RECTANGLE -> R.string.inspire_image_edit_shape_rect
            ShapeType.ROUNDED_RECTANGLE -> R.string.inspire_image_edit_shape_round_rect
            ShapeType.OVAL -> R.string.inspire_image_edit_shape_oval
            ShapeType.LINE -> R.string.inspire_image_edit_shape_line
            ShapeType.ARROW -> R.string.inspire_image_edit_shape_arrow
            ShapeType.DIAMOND -> R.string.inspire_image_edit_shape_diamond
            ShapeType.TRIANGLE -> R.string.inspire_image_edit_shape_triangle
        }
        return getString(labelRes)
    }

    private fun showTextEditor(
        anchor: EditorPoint,
        suggestedSize: Float,
        action: EditAction.Text?,
    ) {
        showInspireEditBottomSheet(
            context = this,
            title = getString(R.string.inspire_image_edit_text_title),
            hint = getString(R.string.inspire_image_edit_text_hint),
            scopeText = getString(R.string.inspire_image_edit_text_scope),
            defaultText = action?.text.orEmpty(),
            disableDragToDismiss = true,
            onConfirm = { input ->
                val text = input.trim()
                when {
                    action != null && text.isEmpty() -> editorSession.removeAction(action.id)
                    action != null -> editorSession.updateTextAction(action.id) {
                        it.copy(text = text)
                    }

                    text.isNotEmpty() -> editorSession.addAction(
                        EditAction.Text(
                            id = System.nanoTime().toString(),
                            text = text,
                            anchor = anchor,
                            color = editorSession.state.currentColor,
                            textSize = suggestedSize,
                        )
                    )
                }
            }
        )
    }

    private fun showSaveOptions(anchor: View) {
        saveOptionsPopup?.dismiss()

        val popupBinding = PopupInspireImageEditorSaveOptionsBinding.inflate(
            LayoutInflater.from(this)
        )
        val popupWindow = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            elevation = resources.displayMetrics.density * 10f
        }

        popupBinding.actionSavePersist.setOnClickListener {
            popupWindow.dismiss()
            exportAndSave(deleteAfterMinutes = 0)
        }
        popupBinding.actionSaveAutoDelete.setOnClickListener {
            popupWindow.dismiss()
            exportAndSave(deleteAfterMinutes = 5)
        }

        popupBinding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val horizontalOffset = anchor.width - popupBinding.root.measuredWidth
        val verticalGap = (8 * resources.displayMetrics.density).toInt()
        val verticalOffset = -(anchor.height + popupBinding.root.measuredHeight + verticalGap)

        popupWindow.showAsDropDown(anchor, horizontalOffset, verticalOffset)
        saveOptionsPopup = popupWindow
    }

    private fun exportAndSave(deleteAfterMinutes: Long) {
        exportJob?.cancel()
        val exportedBitmap = binding.editorView.exportBitmap(1f)
        if (exportedBitmap == null) {
            ToastHelper.showInUIThread(this, R.string.inspire_image_edit_export_failed)
            return
        }

        exportJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                GalleryImageManager.saveImageToGalleryWithAutoDelete(
                    this@InspireImageEditorActivity,
                    exportedBitmap,
                    deleteAfterMinutes
                )
                PinPoolStore.addScreenContentImageIfEnabled(
                    this@InspireImageEditorActivity,
                    exportedBitmap
                )
                withContext(Dispatchers.Main) {
                    finish()
                }
            } finally {
                if (!exportedBitmap.isRecycled) {
                    exportedBitmap.recycle()
                }
            }
        }
    }

    private fun shareEditedBitmap(isPress: Boolean) {
        exportJob?.cancel()
        val exportedBitmap = binding.editorView.exportBitmap(1f)
        if (exportedBitmap == null) {
            ToastHelper.showInUIThread(this, R.string.inspire_image_edit_export_failed)
            return
        }

        exportJob = lifecycleScope.launch(Dispatchers.Main) {
            try {
                val targetPackage = if (isPress) {
                    ""
                } else {
                    settingsStore.readKey(KEY_DEFAULT_SHARE_IMAGE_APP, "")
                }
                val forceChooser = isPress || targetPackage.isEmpty()
                val launched = imageShareLauncher.shareImage(
                    bitmap = exportedBitmap,
                    targetPackage = targetPackage.ifEmpty { null },
                    targetActivity = null,
                    forceChooser = forceChooser,
                )
                if (!launched) {
                    ToastHelper.showInUIThread(
                        this@InspireImageEditorActivity,
                        R.string.inspire_share_image_failed
                    )
                } else {
                    finish()
                }
            } finally {
                if (!exportedBitmap.isRecycled) {
                    exportedBitmap.recycle()
                }
            }
        }
    }

    private fun pinEditedBitmapToScreen() {
        exportJob?.cancel()
        val exportedBitmap = binding.editorView.exportBitmap(1f)
        if (exportedBitmap == null) {
            ToastHelper.showInUIThread(this, R.string.inspire_image_edit_export_failed)
            return
        }

        exportJob = lifecycleScope.launch {
            val handle = ManagedBitmap.from(exportedBitmap)
            produceInspireEvent(InspireEvent(image = handle))
            finish()
        }
    }

    private fun addEditedBitmapToPinPool() {
        exportJob?.cancel()
        val exportedBitmap = binding.editorView.exportBitmap(1f)
        if (exportedBitmap == null) {
            ToastHelper.showInUIThread(this, R.string.inspire_image_edit_export_failed)
            return
        }

        exportJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pinned = PinPoolStore.addImagePin(this@InspireImageEditorActivity, exportedBitmap)
                if (pinned == null) {
                    ToastHelper.showInUIThread(
                        this@InspireImageEditorActivity,
                        R.string.pin_pool_image_read_failed
                    )
                } else {
                    ToastHelper.showInUIThread(
                        this@InspireImageEditorActivity,
                        R.string.clipboard_share_to_pin_pool
                    )
                    withContext(Dispatchers.Main) {
                        finish()
                    }
                }
            } finally {
                if (!exportedBitmap.isRecycled) {
                    exportedBitmap.recycle()
                }
            }
        }
    }

    private fun copyEditedBitmap() {
        exportJob?.cancel()
        val exportedBitmap = binding.editorView.exportBitmap(1f)
        if (exportedBitmap == null) {
            ToastHelper.showInUIThread(this, R.string.inspire_image_edit_export_failed)
            return
        }

        exportJob = lifecycleScope.launch {
            val handle = ManagedBitmap.from(exportedBitmap)
            try {
                InspireHelperUtils.copyBitmapToClipboard(
                    context = this@InspireImageEditorActivity,
                    bitmapHandle = handle,
                    showToast = true,
                    autoSaveToPinPool = true
                )
                finish()
            } finally {
                handle.close()
            }
        }
    }

    private fun loadSourceBitmap() {
        loadBitmapJob?.cancel()
        loadBitmapJob = lifecycleScope.launch {
            val screenshotBitmapHandle = InspireDataHolder.acquireScreenshotBitmap()
            if (screenshotBitmapHandle != null) {
                sourceBitmapHandle?.close()
                sourceBitmapHandle = screenshotBitmapHandle
                binding.editorView.setImageBitmap(screenshotBitmapHandle)
                applyAdaptiveBrushSizeIfNeeded()
                runEnterAnimationIfNeeded()
                return@launch
            }

            val bitmap = withContext(Dispatchers.IO) {
                cacheFile?.takeIf { it.exists() }?.let {
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inMutable = true
                    }
                    BitmapFactory.decodeFile(it.absolutePath, options)
                }
            }
            if (bitmap == null) {
                ToastHelper.showInUIThread(this@InspireImageEditorActivity, R.string.inspire_image_edit_load_failed)
                finish()
                return@launch
            }

            sourceBitmapHandle?.close()
            sourceBitmapHandle = ManagedBitmap.from(bitmap)
            sourceBitmapHandle?.let {
                binding.editorView.setImageBitmap(it)
                applyAdaptiveBrushSizeIfNeeded()
                runEnterAnimationIfNeeded()
            }
        }
    }

    private fun runEnterAnimationIfNeeded() {
        if (enterAnimationPlayed) return
        enterAnimationPlayed = true
        binding.editorView.post {
            binding.editorView.alpha = 0f
            binding.editorView.scaleX = 0.985f
            binding.editorView.scaleY = 0.985f
            binding.editorView.translationY = 12f
            binding.editorView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(IMAGE_ENTER_DURATION_MS)
                .setInterpolator(IMAGE_ENTER_INTERPOLATOR)
                .start()
        }
        prepareBarsForEnter()

        AnimatorSet().apply {
            startDelay = BAR_ENTER_DELAY_MS
            interpolator = BAR_ENTER_INTERPOLATOR
            duration = BAR_ENTER_DURATION_MS
            playTogether(
                ObjectAnimator.ofFloat(binding.bottomPanel, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.bottomPanel, View.TRANSLATION_Y, binding.bottomPanel.translationY, 0f),
            )
            start()
        }
    }

    private fun prepareBarsForEnter() {
        binding.bottomPanel.alpha = 0f
        val bottomOffset = (binding.bottomPanel.height.takeIf { it > 0 } ?: binding.bottomPanel.measuredHeight)
            .coerceAtLeast(dpToPx(96))
        binding.bottomPanel.translationY = bottomOffset.toFloat() * 0.8f
    }

    private fun applyAdaptiveBrushSizeIfNeeded() {
        val bitmapSize = binding.editorView.currentBitmapSize() ?: return
        val shortEdge = min(bitmapSize.first, bitmapSize.second)
        brushModes().forEach { mode ->
            val adaptiveStrokeWidth = calculateAdaptiveBrushSize(shortEdge, mode)
            val currentStrokeWidth = editorSession.state.brushWidthFor(mode)
            val previousAdaptiveStrokeWidth = adaptiveStrokeWidths[mode]
            val shouldApplyAdaptiveWidth = previousAdaptiveStrokeWidth == null ||
                abs(currentStrokeWidth - previousAdaptiveStrokeWidth) < 0.5f

            if (!shouldApplyAdaptiveWidth) return@forEach

            adaptiveStrokeWidths[mode] = adaptiveStrokeWidth
            editorSession.updateStrokeWidthForMode(mode, adaptiveStrokeWidth)
        }
    }

    private fun calculateAdaptiveBrushSize(shortEdge: Int, mode: EditorMode): Float {
        val edge = shortEdge.toFloat()
        val adaptiveSize = when (mode) {
            EditorMode.DOODLE -> (edge * 0.012f) + 2f
            EditorMode.ERASER -> (edge * 0.026f) + 4f
            EditorMode.SHAPE -> (edge * 0.015f) + 2f
            EditorMode.MOSAIC -> (edge * 0.04f) + 6f
            EditorMode.NAVIGATE,
            EditorMode.CROP,
            EditorMode.TEXT -> editorSession.state.brushWidthFor(EditorMode.DOODLE)
        }
        return adaptiveSize.coerceIn(BRUSH_SIZE_MIN, BRUSH_SIZE_MAX)
    }

    private fun brushModes(): List<EditorMode> {
        return listOf(
            EditorMode.DOODLE,
            EditorMode.ERASER,
            EditorMode.SHAPE,
            EditorMode.MOSAIC,
        )
    }

    private fun supportsBrushSize(mode: EditorMode): Boolean {
        return mode in brushModes()
    }
}
