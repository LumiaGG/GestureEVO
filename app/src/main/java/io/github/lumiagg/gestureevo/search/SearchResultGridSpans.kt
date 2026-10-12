package io.github.lumiagg.gestureevo.search

// Preserve adapter order and expand an unmatched half-width item before a full row or the end.
internal fun compactResultSpans(fullWidth: BooleanArray): IntArray {
    val spans = IntArray(fullWidth.size) { if (fullWidth[it]) 2 else 1 }
    var pendingHalf = -1
    spans.indices.forEach { index ->
        if (spans[index] == 2) {
            if (pendingHalf >= 0) spans[pendingHalf] = 2
            pendingHalf = -1
        } else {
            pendingHalf = if (pendingHalf < 0) index else -1
        }
    }
    if (pendingHalf >= 0) spans[pendingHalf] = 2
    return spans
}
