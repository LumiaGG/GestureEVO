package io.github.lumiagg.gestureevo.gesture.config

import io.github.lumiagg.gestureevo.gesture.config.ActionConfig.Companion.DEFAULT_TAG

class ActionViewState {
    var itemsTag: String = DEFAULT_TAG

    var startAngle: Float = 0F

    var sweepAngle: Float = 90F

    var centerX: Int = 0

    var centerY: Int = 0

    var mirror: Boolean = false

    fun setAngleByQuadrant(quadrant: List<Boolean>) {
        val trueCount = quadrant.count { it }

        when (trueCount) {
            4 -> {
                // All quadrants enabled - full circle (360 degrees)
                startAngle = 0F
                sweepAngle = 360F
            }

            2 -> {
                // Two quadrants enabled - semicircle (180 degrees)
                if (quadrant[0] && quadrant[1]) {
                    // Top edge: first and second quadrants
                    startAngle = 0F
                } else if (quadrant[1] && quadrant[2]) {
                    // Left edge: second and third quadrants
                    startAngle = 90F
                } else if (quadrant[2] && quadrant[3]) {
                    // Bottom edge: third and fourth quadrants
                    startAngle = 180F
                } else if (quadrant[0] && quadrant[3]) {
                    // Right edge: first and fourth quadrants
                    startAngle = 270F
                } else {
                    // Handle non-adjacent quadrant edge cases if any
                    startAngle = 90F * quadrant.indexOf(true)
                }
                sweepAngle = 180F
            }

            1 -> {
                // Single quadrant enabled - corners (90 degrees)
                startAngle = 90F * quadrant.indexOf(true)
                sweepAngle = 90F
            }

            3 -> {
                // Three quadrants enabled (special case)
                startAngle = 90F * ((quadrant.indexOf(false) + 1) % 4) // Find disabled quadrant
                sweepAngle = 270F
            }

            0 -> {
                // No quadrants enabled (fallback/error case)
                startAngle = 0F
                sweepAngle = 0F
            }
        }
    }


}
