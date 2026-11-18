package io.github.lumiagg.gestureevo.settings.ui

/** Presentation only; the caller owns availability, defaults and the import operation. */
internal data class ImportOption<T>(
    val value: T,
    val title: String,
    val subtitle: String,
    val packageName: String,
    val status: String = "",
    val iconPath: String = "",
    val initiallySelected: Boolean = false,
)

internal class ImportSelection<T>(options: List<ImportOption<T>>) {
    val options = options.toList()
    private val selected = BooleanArray(options.size) { options[it].initiallySelected }
    val count: Int get() = selected.count { it }
    val allSelected: Boolean get() = selected.isNotEmpty() && selected.all { it }
    val values: List<T> get() = options.filterIndexed { index, _ -> selected[index] }.map { it.value }

    fun isSelected(index: Int): Boolean = selected[index]

    fun toggle(index: Int): Boolean {
        selected[index] = !selected[index]
        return selected[index]
    }

    fun setAll(value: Boolean): List<Int> = selected.indices.filter { selected[it] != value }.also { indices ->
        indices.forEach { selected[it] = value }
    }
}
