package io.github.lumiagg.gestureevo.inspire

import android.graphics.Rect

/**
 * Holds transient data passed between components in the Inspire feature.
 */
object InspireDataHolder {

    // Stored screenshot
    var screenshotBitmap: ManagedBitmap? = null
        private set

    // Stored accessibility content
    var accessibilityContent: List<String>? = null

    var ocrAllowedTextBoxes: List<Rect>? = null
        private set

    // Selection rectangle - should not be cleared by clear()
    var dragRect: Rect? = null

    var forceImageTextSelection: Boolean = false
    var triggerSide: String? = null

    fun replaceScreenshotBitmap(bitmapHandle: ManagedBitmap?) {
        val newHandle = bitmapHandle?.acquire()
        screenshotBitmap?.close()
        screenshotBitmap = newHandle
    }

    fun acquireScreenshotBitmap(): ManagedBitmap? = screenshotBitmap?.acquire()

    fun replaceOcrAllowedTextBoxes(boxes: List<Rect>?) {
        ocrAllowedTextBoxes = boxes?.map(::Rect)
    }

    fun clearScreenshotBitmap() {
        screenshotBitmap?.close()
        screenshotBitmap = null
    }

    fun clear() {
        clearScreenshotBitmap()
        accessibilityContent = null
        ocrAllowedTextBoxes = null
        forceImageTextSelection = false
        triggerSide = null
    }
}
