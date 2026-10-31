package io.github.lumiagg.gestureevo.inspire

internal fun accessibilityNodeText(
    text: CharSequence?,
    description: CharSequence?,
    className: CharSequence?,
    isClickable: Boolean = false,
    isLongClickable: Boolean = false,
    isCheckable: Boolean = false,
    isScrollable: Boolean = false,
    hasTextSelection: Boolean = false,
): String? {
    // Real text remains eligible even on controls with a separate spoken label.
    if (!text.isNullOrEmpty()) return text.toString()
    if (description.isNullOrBlank()) return null

    // Flutter static text commonly uses a generic View's contentDescription.
    // Images and buttons use the same property for spoken control descriptions;
    // reading all of them as visible text exposes labels such as "show menu".
    if (!hasTextSelection) {
        when (className?.toString()) {
            "android.view.View" -> if (isClickable && !isLongClickable) return null
            "android.widget.TextView", "android.widget.EditText", "android.widget.CheckedTextView" -> Unit
            else -> return null
        }
        if (isCheckable || isScrollable) return null
    }
    return description.toString()
}

/** Reject incidental edge overlaps, while allowing small selections inside a larger text node. */
internal fun hasSubstantialAccessibilityOverlap(
    selectionStart: Int,
    selectionEnd: Int,
    nodeStart: Int,
    nodeEnd: Int,
): Boolean {
    val selectionSize = selectionEnd.toLong() - selectionStart
    val nodeSize = nodeEnd.toLong() - nodeStart
    if (selectionSize <= 0 || nodeSize <= 0) return false
    val overlap = minOf(selectionEnd, nodeEnd).toLong() - maxOf(selectionStart, nodeStart)
    return overlap > 0 && overlap * 2 >= minOf(selectionSize, nodeSize)
}

/** A null location denotes an off-screen/missing glyph, not unsupported location data. */
internal fun <T> selectAccessibilityCharacters(
    text: String,
    locations: Array<out T?>,
    isSelected: (T) -> Boolean,
): String? {
    if (locations.size != text.length) return null
    return buildString {
        locations.forEachIndexed { index, location ->
            if (location != null && isSelected(location)) append(text[index])
        }
    }
}
