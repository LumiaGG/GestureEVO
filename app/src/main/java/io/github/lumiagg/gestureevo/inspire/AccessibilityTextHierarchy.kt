package io.github.lumiagg.gestureevo.inspire

internal data class AccessibilityTextEntry(
    val parentIndex: Int?,
    val text: String?,
    val readable: Boolean,
)

/**
 * Prefer descendants over an ancestor whose text they fully reproduce. Run this over the
 * entire visible tree before applying the selection, so an off-selection child
 * cannot cause its enclosing summary to be returned instead.
 */
internal fun preferredAccessibilityTextIndices(entries: List<AccessibilityTextEntry>): List<Int> {
    val normalizedText = entries.map { entry ->
        entry.text?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() }
    }
    // Entries are in traversal order, with every parent before its children.
    // Accumulate preferred descendants in reverse order while walking upward.
    val descendants = Array(entries.size) { mutableListOf<Int>() }
    val preferred = BooleanArray(entries.size)
    for (index in entries.indices.reversed()) {
        val entry = entries[index]
        val text = normalizedText[index]
        val subtree = descendants[index]
        if (entry.readable && text != null) {
            val repeated = subtree.any { normalizedText[it] == text } ||
                    (subtree.size > 1 && text.withoutSummarySeparators() ==
                            subtree.asReversed().joinToString("") {
                                normalizedText[it].orEmpty().withoutSummarySeparators()
                            })
            if (!repeated) {
                preferred[index] = true
                subtree.add(index)
            }
        }
        entry.parentIndex?.let { descendants[it].addAll(subtree) }
    }
    return entries.indices.filter { preferred[it] }
}

private fun String.withoutSummarySeparators(): String = filterNot { it == ',' || it == '，' }

internal fun selectedAccessibilityTextIndices(
    entries: List<AccessibilityTextEntry>,
    intersecting: Set<Int>,
    fullyContained: Set<Int>,
): List<Int> {
    val candidates = preferredAccessibilityTextIndices(entries).filter { it in intersecting }
    val coarseAncestors = mutableSetOf<Int>()
    for (index in candidates) {
        var ancestorIndex = entries[index].parentIndex
        while (ancestorIndex != null) {
            // Flutter can attach a card's statistics to the card itself, with
            // distinct text in child nodes. A partially selected card does not
            // locate those statistics; prefer its selected descendants instead.
            // Fully selecting the card still includes its independent text.
            if (ancestorIndex !in fullyContained) coarseAncestors.add(ancestorIndex)
            ancestorIndex = entries[ancestorIndex].parentIndex
        }
    }
    return candidates.filterNot { it in coarseAncestors }
}
