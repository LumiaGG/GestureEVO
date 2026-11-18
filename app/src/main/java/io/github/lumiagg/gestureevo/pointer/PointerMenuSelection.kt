package io.github.lumiagg.gestureevo.pointer

/** Finger travel selects adjacent rows, stopping at either end of the menu. */
internal class PointerMenuSelection {
    private var lastY = 0f
    private var remainder = 0f

    fun reset(y: Float) { lastY = y; remainder = 0f }

    fun move(y: Float, count: Int, selectedIndex: Int, step: Float): Int? {
        if (!y.isFinite() || count <= 0 || step <= 0f) return null
        val delta = y - lastY
        lastY = y
        if ((selectedIndex == 0 && delta < 0) || (selectedIndex == count - 1 && delta > 0)) {
            remainder = 0f
            return null
        }
        remainder += delta
        val steps = (remainder / step).toInt()
        if (steps == 0) return null
        remainder -= steps * step
        val next = if (selectedIndex < 0 && steps > 0) steps - 1 else selectedIndex.coerceAtLeast(0) + steps
        val bounded = next.coerceIn(0, count - 1)
        if (bounded != next) remainder = 0f
        return bounded.takeUnless { it == selectedIndex }
    }
}
