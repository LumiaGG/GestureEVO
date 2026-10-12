package io.github.lumiagg.gestureevo.inspire.ui

import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.View.MeasureSpec
import io.github.lumiagg.gestureevo.databinding.BigbangFunctionItemBinding
import io.github.lumiagg.gestureevo.databinding.InspireContentViewBinding
import io.github.lumiagg.gestureevo.inspire.InspireDisplayMode
import io.github.lumiagg.gestureevo.utils.UrlDetector
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlin.math.min

internal class ImageTextSelectionActionBarController(
    private val binding: InspireContentViewBinding,
    private val actionProvider: () -> BigBangActionWrapper?,
) {
    private val actionBarBinding: BigbangFunctionItemBinding? =
        binding.imageSelectionActionBarContainer?.let { container ->
            BigbangFunctionItemBinding.inflate(
                LayoutInflater.from(container.context),
                container,
                true,
            )
        }

    init {
        actionBarBinding?.let { setupActions(it) }
    }

    fun update(
        displayMode: InspireDisplayMode,
        selectedText: String,
        selectionBounds: RectF?,
        isInteracting: Boolean = false,
    ) {
        val actionBar = actionBarBinding ?: return
        val showActionBar = displayMode.isImageTextSelection && selectedText.isNotBlank() && !isInteracting
        actionBar.openUrl.visibility = if (UrlDetector.hasUrl(selectedText)) VISIBLE else GONE
        if (!showActionBar) {
            setVisibility(GONE)
            return
        }
        setVisibility(INVISIBLE)
        position(selectionBounds) {
            setVisibility(VISIBLE)
            actionProvider()?.handleSelected()
        }
    }

    fun hide() {
        setVisibility(GONE)
    }

    private fun setupActions(actionBar: BigbangFunctionItemBinding) = with(actionBar) {
        root.elevation = 4f
        root.visibility = GONE
        edit.visibility = GONE
        share.setOnClickListener { actionProvider()?.handleShare() }
        pin.setOnClickListener { actionProvider()?.handlePin() }
        transition.setOnClickListener { actionProvider()?.handleTranslation() }
        copySelected.setOnClickListener { actionProvider()?.handleCopy(false) }
        copySelected.setOnLongClickListener {
            actionProvider()?.handleCopy(true)
            true
        }
        openUrl.setOnClickListener { actionProvider()?.handleBigBangUrlClick() }
        sendToEVO.setOnClickListener { actionProvider()?.handleSendToEVO(false) }
        sendToEVO.setOnLongClickListener {
            actionProvider()?.handleSendToEVO(true)
            true
        }
    }

    private fun setVisibility(visibility: Int) {
        binding.imageSelectionActionBarContainer?.visibility = visibility
        actionBarBinding?.root?.visibility = visibility
    }

    private fun position(selectionBounds: RectF?, onPositioned: () -> Unit) {
        val container = binding.imageSelectionActionBarContainer ?: return
        val previewContainer = binding.screenshotPreviewContainer ?: return
        val bounds = selectionBounds ?: return
        val positionActionBar = {
            val margin = 8.dp2px
            val maxActionBarWidth = (previewContainer.width - margin * 2).coerceAtLeast(0f).toInt()
            container.measure(
                MeasureSpec.makeMeasureSpec(maxActionBarWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(previewContainer.height, MeasureSpec.AT_MOST),
            )
            val actionBarWidth = min(container.measuredWidth.toFloat(), maxActionBarWidth.toFloat())
            val actionBarHeight = container.measuredHeight.toFloat()
            val desiredX = bounds.centerX() - actionBarWidth / 2f
            val aboveY = bounds.top - actionBarHeight - margin
            val belowY = bounds.bottom + margin
            val desiredY = if (aboveY >= margin) aboveY else belowY
            val maxX = (previewContainer.width - actionBarWidth - margin).coerceAtLeast(margin)
            val maxY = (previewContainer.height - actionBarHeight - margin).coerceAtLeast(margin)
            container.x = desiredX.coerceIn(margin, maxX)
            container.y = desiredY.coerceIn(margin, maxY)
            onPositioned()
        }
        if (previewContainer.width > 0 && previewContainer.height > 0) {
            positionActionBar()
        } else {
            container.post { positionActionBar() }
        }
    }
}
