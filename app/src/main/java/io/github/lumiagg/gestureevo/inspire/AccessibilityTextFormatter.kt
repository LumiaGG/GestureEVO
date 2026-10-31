package io.github.lumiagg.gestureevo.inspire

internal data class AccessibilityTextFragment(val text: String, val top: Int, val bottom: Int)

/** The traversal already provides deterministic hierarchy order; preserve separate visual rows. */
internal fun joinAccessibilityTextInReadingOrder(fragments: List<AccessibilityTextFragment>): String =
    buildString {
        var previous: AccessibilityTextFragment? = null
        for (fragment in fragments) {
            if (fragment.text.isEmpty()) continue
            val previousFragment = previous
            if (previousFragment != null && !previousFragment.sharesVisualRow(fragment) &&
                lastOrNull() !in LINE_BREAKS && fragment.text.first() !in LINE_BREAKS
            ) {
                append('\n')
            }
            append(fragment.text)
            previous = fragment
        }
    }

private fun AccessibilityTextFragment.sharesVisualRow(other: AccessibilityTextFragment): Boolean {
    val height = bottom.toLong() - top
    val otherHeight = other.bottom.toLong() - other.top
    val overlap = minOf(bottom, other.bottom).toLong() - maxOf(top, other.top)
    return height > 0 && otherHeight > 0 && overlap > 0 && overlap * 2 >= minOf(height, otherHeight)
}

private val LINE_BREAKS = setOf('\n', '\r')
