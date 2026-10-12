package io.github.lumiagg.gestureevo.inspire.ui

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.addListener
import android.widget.ImageView
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.InspireContentViewBinding
import io.github.lumiagg.gestureevo.inspire.InspireDisplayMode
import io.github.lumiagg.gestureevo.inspire.InspireTokenContentMode
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.inspire.ScreenContentNode
import io.github.lumiagg.gestureevo.utils.MlKitOcr
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.UrlDetector
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt

class InspireContentView : MaterialCardView {
    constructor(context: Context) : super(context) {
        initialize(null)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        initialize(attrs)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    ) {
        initialize(attrs)
    }

    private lateinit var binding: InspireContentViewBinding
    private lateinit var displayModeController: InspireDisplayModeController
    private lateinit var imageTextActionBarController: ImageTextSelectionActionBarController
    private lateinit var imageTextSelectionPreviewController: ImageTextSelectionPreviewController
    private lateinit var modeSelectorController: ModeSelectorController
    private lateinit var imagePreviewController: ImagePreviewController
    var actionWrapper: BigBangActionWrapper? = null
    private var displayMode = InspireDisplayMode.TOKEN_SELECTION
    private var overlaySelectedText = ""
    private var overlaySelectionBounds: RectF? = null
    private var tokenContentState = TokenContentState()
    private var suppressNextFunctionViewScroll = false
    private var rotationAnimator: ValueAnimator? = null
    var onRotateImageRequested: ((rotationDegrees: Int) -> Unit)? = null
    val hasImage: Boolean get() = imagePreviewController.hasImage
    fun getCurrentDisplayMode(): InspireDisplayMode = displayMode

    var onTokenContentRequested: ((InspireTokenContentMode) -> Unit)? = null
    var ocrContents: List<String>?
        get() = tokenContentState.ocrContents
        set(value) {
            tokenContentState = tokenContentState.copy(ocrContents = value)
            updateModeSelectorState()
        }
    var accessibilityContents: List<String>?
        get() = tokenContentState.accessibilityContents
        set(value) {
            tokenContentState = tokenContentState.copy(accessibilityContents = value)
            updateModeSelectorState()
        }

    fun setInitialTokenMode(mode: InspireTokenContentMode) {
        tokenContentState = tokenContentState.copy(currentMode = mode)
        updateModeSelectorState()
    }

    private fun initialize(attrs: AttributeSet?) {
        binding = InspireContentViewBinding.inflate(LayoutInflater.from(context), this, true)
        imagePreviewController = ImagePreviewController(
            binding = binding,
            hostView = this,
            isImageTextSelectionProvider = { displayMode.isImageTextSelection },
        )
        displayModeController = InspireDisplayModeController(
            binding = binding,
            hostView = this,
            previewHeightProvider = imagePreviewController::previewHeightForMode,
            onPreviewLayoutChanged = imagePreviewController::postUpdateOverlayImageBounds,
        )
        imageTextActionBarController = ImageTextSelectionActionBarController(
            binding = binding,
            actionProvider = { actionWrapper },
        )
        imageTextSelectionPreviewController = ImageTextSelectionPreviewController(binding)
        modeSelectorController = ModeSelectorController(
            binding = binding,
            canUseMode = ::canUseTokenMode,
            currentModeProvider = { tokenContentState.currentMode },
        )
        setupBigBangLayout()
        setupClickListeners()
        updateModeSelectorState()
        updateOpenUrlVisibility(null)
        setupRotationGesture()
    }

    private fun setupBigBangLayout() {
        val edgeScroller = EdgeScroller(binding.bigBangLayoutNestedScroll, null)
        binding.bigBangLayout.apply {
            onFunctionViewLayout = { y -> smoothScrollTo(y) }
            onTouchCallback = { event, view ->
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> edgeScroller.handleDrag(event.y, view)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeScroller.onDragEnd()
                }
            }
        }
    }

    // This listener filters only touches outside the image; normal clicks are handled by View.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupClickListeners() = with(binding) {
        selectAll.setOnClickListener { bigBangLayout.selectAll() }
        btnImageTextOverlay?.setOnClickListener { toggleImageTextOverlayMode() }
        btnModeOcr.setOnClickListener { switchSourceMode(isOcr = true) }
        btnModeAccessibility.setOnClickListener { switchSourceMode(isOcr = false) }
        openUrl.setOnClickListener { actionWrapper?.handleUrlClick() }
        btnReselect.setOnClickListener { actionWrapper?.handleReselectClick() }
        btnSaveImage.setOnClickListener { actionWrapper?.handleSaveImageClick(false) }
        btnSaveImage.setOnLongClickListener {
            actionWrapper?.handleSaveImageClick(true)
            true
        }
        btnShare.apply {
            setOnClickListener { actionWrapper?.handleShareButtonClick(false) }
            setOnLongClickListener {
                actionWrapper?.handleShareButtonClick(true)
                true
            }
        }
        btnPinImage.setOnClickListener {
            actionWrapper?.handlePinImageClick()
        }
        btnPinImage.setOnLongClickListener {
            actionWrapper?.handlePinImageLongPress()
            true
        }
        scanQR.setOnClickListener { actionWrapper?.handleScanQR() }

        screenshotPreview.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                !imagePreviewController.isTouchInsideDisplayedImage(view as ImageView, event.x, event.y)
            ) {
                return@setOnTouchListener true
            }
            false
        }
        screenshotPreview.setOnClickListener {
            if (displayMode.isImageTextSelection) return@setOnClickListener
            actionWrapper?.handleOpenImageClick(false)
        }
        screenshotPreview.setOnLongClickListener {
            if (displayMode.isImageTextSelection) return@setOnLongClickListener true
            actionWrapper?.handleOpenImageClick(true)
            true
        }

        ocrImageTextOverlay?.onSelectionStateChanged = {
                selectedText,
                selectionBounds,
                isInteracting,
                interactionAnchor,
            ->
            overlaySelectedText = selectedText
            overlaySelectionBounds = selectionBounds
            imageTextSelectionPreviewController.update(
                displayMode = displayMode,
                selectedText = selectedText,
                isInteracting = isInteracting,
                interactionAnchor = interactionAnchor,
            )
            updateImageSelectionActionBar(selectedText, selectionBounds, isInteracting)
        }

    }

    private fun updateImageSelectionActionBar(
        selectedText: String = overlaySelectedText,
        selectionBounds: RectF? = overlaySelectionBounds,
        isInteracting: Boolean = false,
    ) {
        imageTextActionBarController.update(
            displayMode = displayMode,
            selectedText = selectedText,
            selectionBounds = selectionBounds,
            isInteracting = isInteracting,
        )
    }

    private fun toggleImageTextOverlayMode() {
        if (!imagePreviewController.hasImageTextNodes) {
            ToastHelper.showInUIThread(context, context.getString(R.string.inspire_content_ocr_empty))
            return
        }
        val targetDisplayMode = if (displayMode.isImageTextSelection) {
            InspireDisplayMode.TOKEN_SELECTION
        } else {
            InspireDisplayMode.IMAGE_TEXT_SELECTION
        }
        setDisplayMode(
            targetDisplayMode
        )
        if (!targetDisplayMode.isImageTextSelection) {
            requestCurrentTokenContentIfNeeded()
        }
    }

    fun setDisplayMode(newDisplayMode: InspireDisplayMode) = with(binding) {
        if (displayMode != newDisplayMode) {
            imagePreviewController.resetLockedImageTextPreviewHeight()
        }
        displayMode = newDisplayMode
        val isImageTextSelection = newDisplayMode.isImageTextSelection
        screenshotPreviewContainer?.visibility = VISIBLE
        ocrImageTextOverlay?.visibility = if (isImageTextSelection) VISIBLE else GONE
        btnImageTextOverlay?.isSelected = isImageTextSelection
        btnImageTextOverlay?.setImageResource(
            if (isImageTextSelection) R.drawable.ic_image_text_collapse_24 else R.drawable.ic_image_text_expand_24
        )
        if (!isImageTextSelection) {
            ocrImageTextOverlay?.clearSelection()
            overlaySelectionBounds = null
            overlaySelectedText = ""
        }
        imageTextSelectionPreviewController.update(
            displayMode = displayMode,
            selectedText = overlaySelectedText,
            isInteracting = false,
            interactionAnchor = null,
        )
        updateImageSelectionActionBar()
        displayModeController.apply(newDisplayMode)
        imagePreviewController.postUpdateOverlayImageBounds()
    }
    private fun switchSourceMode(isOcr: Boolean) {
        val targetMode = if (isOcr) InspireTokenContentMode.OCR else InspireTokenContentMode.ACCESSIBILITY
        setDisplayMode(InspireDisplayMode.TOKEN_SELECTION)
        switchToMode(targetMode)
        if (getContentForMode(targetMode) == null) {
            onTokenContentRequested?.invoke(targetMode)
        }
    }

    private fun requestCurrentTokenContentIfNeeded() {
        val targetMode = tokenContentState.currentMode
        switchToMode(targetMode)
        if (getContentForMode(targetMode) == null) {
            onTokenContentRequested?.invoke(targetMode)
        }
    }

    fun switchToMode(
        newMode: InspireTokenContentMode,
        isSelected: Boolean = false,
    ) {
        if (!displayMode.isImageTextSelection) {
            setDisplayMode(InspireDisplayMode.TOKEN_SELECTION)
        }
        tokenContentState = tokenContentState.copy(currentMode = newMode)
        updateModeSelectorState()
        val contents = getContentForMode(newMode)
        if (contents == null) {
            updateOpenUrlVisibility(null)
        }
        contents?.let { setContent(it, isSelected) } ?: {
            if (tokenContentState.currentMode.isOCR && MlKitOcr.taskRunning) {
                ToastHelper.showInUIThread(
                    context,
                    context.getString(R.string.inspire_recognizing)
                )
            }
        }
    }


    private fun getContentForMode(mode: InspireTokenContentMode) = tokenContentState.contentFor(mode)

    fun setContent(
        contents: List<String>,
        isSelected: Boolean = false,
        selectedIndexes: Set<Int>? = null,
        preserveScroll: Boolean = false,
        changedIndex: Int? = null,
        changedContents: List<String>? = null,
    ) {
        tokenContentState = tokenContentState.copy(currentContents = contents)
        updateModeSelectorState()
        updateOpenUrlVisibility(contents)
        if (
            changedIndex != null &&
            changedContents != null &&
            updateBigBangItem(
                changedIndex = changedIndex,
                changedContents = changedContents,
                selectedIndexes = selectedIndexes.orEmpty(),
                preserveScroll = preserveScroll,
            )
        ) {
            return
        }
        updateBigBang(
            isSelected = isSelected,
            selectedIndexes = selectedIndexes,
            preserveScroll = preserveScroll,
        )
    }

    fun getCurrentContent(): List<String>? {
        return tokenContentState.currentContents
    }

    fun getCurrentMode(): InspireTokenContentMode {
        return tokenContentState.currentMode
    }

    private fun updateBigBang(
        isSelected: Boolean,
        selectedIndexes: Set<Int>? = null,
        preserveScroll: Boolean = false,
    ) = binding.bigBangLayout.post {
        val scrollY = binding.bigBangLayoutNestedScroll.scrollY
        if (preserveScroll) {
            suppressNextFunctionViewScroll = true
        }
        binding.bigBangLayout.apply {
            alpha = 1f
            preSetContent()
            tokenContentState.currentContents?.forEach { binding.bigBangLayout.addTextItem(it, isSelected) }
            selectedIndexes?.let { binding.bigBangLayout.setSelectedContentIndexes(it) }
            requestLayout()
        }
        if (preserveScroll) {
            binding.bigBangLayoutNestedScroll.post {
                binding.bigBangLayoutNestedScroll.scrollTo(0, scrollY)
                binding.bigBangLayoutNestedScroll.post {
                    suppressNextFunctionViewScroll = false
                }
            }
        }
    }

    private fun updateBigBangItem(
        changedIndex: Int,
        changedContents: List<String>,
        selectedIndexes: Set<Int>,
        preserveScroll: Boolean,
    ): Boolean {
        val scrollY = binding.bigBangLayoutNestedScroll.scrollY
        if (preserveScroll) {
            suppressNextFunctionViewScroll = true
        }
        val replaced = binding.bigBangLayout.replaceTextItem(
            contentIndex = changedIndex,
            texts = changedContents,
            selectedIndexes = selectedIndexes,
        )
        if (preserveScroll) {
            binding.bigBangLayoutNestedScroll.post {
                binding.bigBangLayoutNestedScroll.scrollTo(0, scrollY)
                binding.bigBangLayoutNestedScroll.post {
                    suppressNextFunctionViewScroll = false
                }
            }
        }
        return replaced
    }

    private fun smoothScrollTo(targetY: Int) {
        if (suppressNextFunctionViewScroll) {
            return
        }
        val currentScrollY = binding.bigBangLayoutNestedScroll.scrollY
        val viewportHeight = binding.bigBangLayoutNestedScroll.height
        val dy = when {
            targetY > (currentScrollY + viewportHeight) -> targetY - (currentScrollY + viewportHeight)
            targetY < currentScrollY -> targetY - currentScrollY
            else -> 0
        }
        if (dy != 0) {
            post { binding.bigBangLayoutNestedScroll.smoothScrollBy(0, dy) }
        }
    }

    private fun updateModeSelectorState() {
        modeSelectorController.update()
    }

    private fun updateOpenUrlVisibility(contents: List<String>?) {
        val hasUrl = contents?.let { list ->
            UrlDetector.hasUrl(list.joinToString(" "))
        } ?: false
        val visibility = if (hasUrl) VISIBLE else GONE
        binding.openUrl.post {
            binding.openUrl.visibility = visibility
            (binding.openUrl.parent as? View)?.visibility = visibility
        }
    }

    fun setBigBangStatusListener(actionWrapper: BigBangActionWrapper) {
        this.actionWrapper = actionWrapper
        binding.bigBangLayout.bigBangStatusListener = actionWrapper
    }

    fun getSelectedTextNotEmpty() = getSelectedText().takeIf { it.isNotEmpty() }
        ?: if (displayMode.isImageTextSelection) {
            imagePreviewController.imageTextContent
        } else {
            tokenContentState.currentContents?.joinToString("").orEmpty()
        }

    fun getSelectedText() = if (displayMode.isImageTextSelection) {
        overlaySelectedText
    } else {
        binding.bigBangLayout.makeSelectedText()
    }

    fun getSelectedContentIndexes() = binding.bigBangLayout.getSelectedContentIndexes()

    fun setSelectedContentIndexes(indexes: Set<Int>) {
        binding.bigBangLayout.setSelectedContentIndexes(indexes)
    }

    fun applySelectedItemsBackgroundGlassStyle(glassEnabled: Boolean) {
        binding.bigBangLayout.applySelectBackgroundGlassStyle(glassEnabled)
    }

    fun clearSelectedText() {
        binding.bigBangLayout.cancelSelect()
        binding.ocrImageTextOverlay?.clearSelection()
        imageTextActionBarController.hide()
        imageTextSelectionPreviewController.hide()
    }

    fun acquireImageHandle(): ManagedBitmap? = imagePreviewController.acquireImageHandle()

    fun setCustomContent(
        contents: List<String>,
        preserveScroll: Boolean = false,
        changedIndex: Int? = null,
        changedContents: List<String>? = null,
        selectedIndexes: Set<Int>? = null,
    ) {
        tokenContentState = tokenContentState.copy(currentMode = InspireTokenContentMode.OTHER)
        setContent(
            contents = contents,
            preserveScroll = preserveScroll,
            changedIndex = changedIndex,
            changedContents = changedContents,
            selectedIndexes = selectedIndexes,
        )
    }

    fun resetForEditorLaunch() {
        binding.actionButtons.alpha = 0f
        binding.actionButtons.translationY = 0f
        binding.bigBangLayoutNestedScroll.alpha = 0f
        binding.bigBangLayoutNestedScroll.translationY = 0f
        binding.screenshotPreview.alpha = 1f
        binding.screenshotPreview.scaleX = 1f
        binding.screenshotPreview.scaleY = 1f
    }

    suspend fun setScreenShot(handle: ManagedBitmap) {
        resetTransientViewState()
        imagePreviewController.setScreenShot(handle)
    }

    fun setOcrImageTextNodes(imageWidth: Int, imageHeight: Int, nodes: List<ScreenContentNode>) {
        imagePreviewController.setImageTextNodes(imageWidth, imageHeight, nodes)
    }

    fun clear() {
        imagePreviewController.clear()
        tokenContentState = TokenContentState()
        overlaySelectedText = ""
        displayMode = InspireDisplayMode.TOKEN_SELECTION
        imageTextActionBarController.hide()
        imageTextSelectionPreviewController.hide()
        resetTransientViewState()
        displayModeController.hideAll()
        updateModeSelectorState()
        updateOpenUrlVisibility(null)
        binding.bigBangLayout.post { binding.bigBangLayout.reset() }
    }

    private fun canUseTokenMode(mode: InspireTokenContentMode): Boolean {
        return tokenContentState.canUse(mode)
    }

    fun setRequestableTokenModes(modes: Set<InspireTokenContentMode>) {
        tokenContentState = tokenContentState.copy(requestableModes = modes)
        updateModeSelectorState()
    }

    private fun resetTransientViewState() {
        rotationAnimator?.cancel()
        binding.screenshotPreview.animate().cancel()
        binding.actionButtons.animate().cancel()
        binding.bigBangLayoutNestedScroll.animate().cancel()

        binding.screenshotPreview.rotation = 0f
        binding.screenshotPreview.alpha = 1f
        binding.screenshotPreview.scaleX = 1f
        binding.screenshotPreview.scaleY = 1f
        imagePreviewController.postUpdateOverlayImageBounds()

        binding.actionButtons.alpha = 1f
        binding.actionButtons.translationY = 0f

        binding.bigBangLayoutNestedScroll.alpha = 1f
        binding.bigBangLayoutNestedScroll.translationY = 0f
    }

    private fun setupRotationGesture() {
        binding.imageActionButtons?.rotationGestureListener =
            object : InspireActionButtonsRow.OnRotationGestureListener {
                override fun canRotate(): Boolean = hasImage

                override fun onRotationGestureStart() {
                    rotationAnimator?.cancel()
                    binding.ocrImageTextOverlay?.alpha = 0f
                }

                override fun onRotationGestureUpdate(deltaDegrees: Float) {
                    binding.screenshotPreview.rotation = deltaDegrees
                }

                override fun onRotationGestureEnd(currentDegrees: Float, velocityX: Float) {
                    val targetAngle: Float = when {
                        velocityX > 800f -> (floor(currentDegrees / 90f) + 1f) * 90f
                        velocityX < -800f -> (ceil(currentDegrees / 90f) - 1f) * 90f
                        else -> round(currentDegrees / 90f) * 90f
                    }
                    animateRotationTo(targetAngle)
                }

                override fun onRotationGestureCancel() {
                    animateRotationTo(0f)
                }
            }
    }

    private fun animateRotationTo(targetAngle: Float) {
        val startAngle = binding.screenshotPreview.rotation
        rotationAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(startAngle, targetAngle).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                binding.screenshotPreview.rotation = anim.animatedValue as Float
            }
            addListener(
                onEnd = {
                    val snappedAngle = targetAngle.roundToInt()
                    val rotationDelta = ((snappedAngle % 360) + 360) % 360
                    binding.screenshotPreview.rotation = 0f
                    if (rotationDelta != 0) {
                        binding.ocrImageTextOverlay?.alpha = 1f
                        onRotateImageRequested?.invoke(rotationDelta)
                    } else {
                        binding.ocrImageTextOverlay?.alpha = 1f
                    }
                },
                onCancel = {
                    binding.screenshotPreview.rotation = 0f
                    binding.ocrImageTextOverlay?.alpha = 1f
                }
            )
        }
        rotationAnimator = animator
        animator.start()
    }

}
