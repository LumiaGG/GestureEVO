package io.github.lumiagg.gestureevo.drop

import android.content.ClipData

/** Input sources send content and coordinates without depending on a concrete window. */
internal interface ContentDragTarget {
    fun beginContentDrag(
        clip: ClipData, x: Float, y: Float, stillCurrent: () -> Boolean,
        onReady: () -> Unit, onFailed: (String) -> Unit,
    ): Boolean
    fun updateContentDrag(x: Float, y: Float, release: Boolean = false)
    fun cancelContentDrag()
}
