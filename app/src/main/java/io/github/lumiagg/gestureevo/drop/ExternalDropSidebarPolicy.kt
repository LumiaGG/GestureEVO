package io.github.lumiagg.gestureevo.drop

internal object ExternalDropSidebarPolicy {
    data class Layout(val columns: Int, val rows: Int, val height: Int) {
        val pageSize: Int get() = columns * rows
    }

    fun rowCount(maxHeight: Int, fixedHeight: Int, rowHeight: Int, engineCount: Int, columns: Int): Int =
        ((maxHeight - fixedHeight).coerceAtLeast(0) / rowHeight.coerceAtLeast(1))
            .coerceIn(1, ((engineCount + columns - 1) / columns).coerceAtLeast(1))

    fun choose(layouts: List<Layout>, engineCount: Int, maxHeight: Int, preferColumns: Boolean): Layout {
        val fitting = layouts.filter { it.height <= maxHeight }
            .ifEmpty { listOf(layouts.minBy { it.height }) }
        if (preferColumns) return fitting.maxBy { it.columns }
        return fitting.filter { it.pageSize >= engineCount }.minByOrNull { it.columns }
            ?: fitting.maxWith(compareBy<Layout> { it.pageSize }.thenBy { -it.columns })
    }

    fun topOffset(availableHeight: Int, panelHeight: Int, totalHeight: Int): Int =
        ((availableHeight - panelHeight) / 2).coerceIn(0, (availableHeight - totalHeight).coerceAtLeast(0))

    fun pageCount(engineCount: Int, rows: Int, columns: Int): Int =
        ((engineCount + rows * columns - 1) / (rows * columns)).coerceAtLeast(1)

    data class HoverBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Float, y: Float, margin: Int = 0): Boolean =
            left < right && top < bottom &&
                x >= left - margin && x < right + margin && y >= top - margin && y < bottom + margin
    }

    fun hoverDirection(
        x: Float,
        y: Float,
        previous: HoverBounds?,
        next: HoverBounds?,
        activeDirection: Int = 0,
        retentionMargin: Int = 0,
    ): Int = when {
        previous?.contains(x, y) == true -> -1
        next?.contains(x, y) == true -> 1
        activeDirection == -1 && previous?.contains(x, y, retentionMargin) == true -> -1
        activeDirection == 1 && next?.contains(x, y, retentionMargin) == true -> 1
        else -> 0
    }

    fun canFlip(page: Int, direction: Int, pageCount: Int): Boolean =
        (direction == -1 || direction == 1) && page + direction in 0 until pageCount
}
