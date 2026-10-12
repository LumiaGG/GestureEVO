package io.github.lumiagg.gestureevo.gesture

object TriggerSide {
    const val LEFT = "left"
    const val RIGHT = "right"
    const val EXTRA_TRIGGER_SIDE = "extra_trigger_side"

    data class Point(
        val x: Float,
        val width: Int,
        val y: Float = 0f,
        val height: Int = 0,
    )

    @Volatile
    private var side: String? = null

    @Volatile
    private var location: TriggerLocation? = null

    fun record(point: Point) {
        location = TriggerLocation(point.x, point.y, point.width, point.height)
        side = if (point.width > 0 && point.x < point.width / 2f) LEFT else RIGHT
    }

    fun record(loc: TriggerLocation) {
        location = loc
        side = if (loc.screenWidth > 0 && loc.x < loc.screenWidth / 2f) LEFT else RIGHT
    }

    fun record(screenX: Float, screenWidth: Int) {
        if (screenWidth <= 0) return
        side = if (screenX < screenWidth / 2f) LEFT else RIGHT
        val currentLoc = location
        if (currentLoc == null) {
            location = TriggerLocation(screenX, 0f, screenWidth, 0)
        } else {
            location = currentLoc.copy(x = screenX, screenWidth = screenWidth)
        }
    }

    fun record(screenX: Float, screenY: Float, screenWidth: Int, screenHeight: Int) {
        location = TriggerLocation(screenX, screenY, screenWidth, screenHeight)
        side = if (screenWidth > 0 && screenX < screenWidth / 2f) LEFT else RIGHT
    }

    fun record(anchorSide: String?) {
        if (anchorSide.isNullOrEmpty()) return
        location = null
        side = if (anchorSide == LEFT) LEFT else if (anchorSide == RIGHT) RIGHT else null
    }

    fun peek(): String? = side

    fun peekLocation(): TriggerLocation? = location

    fun take(): String? = side.also {
        side = null
        location = null
    }

    fun takeLocation(): TriggerLocation? = location.also {
        location = null
        side = null
    }
}
