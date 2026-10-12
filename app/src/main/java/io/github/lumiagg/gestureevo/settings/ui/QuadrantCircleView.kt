package io.github.lumiagg.gestureevo.settings.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils


class QuadrantCircleView : View {
    private val selectColor: Int
        get() = DynamicColorUtils.primaryColor
    private val unSelectColor = ContextCompat.getColor(context, R.color.transparent)

    private var quadrantPaints = ArrayList<Paint>(4).apply {
        for (i in 0..3) {
            add(Paint().apply {
                style = Paint.Style.FILL
                color = unSelectColor
            })
        }
    }
    private val quadrantClickedList = ArrayList<Boolean>(4).apply {
        for (i in 0..3) {
            add(false)
        }
    }

    private var centerX = 0
    private var centerY = 0
    private var radius = 0

    var onQuadrantClick = object : OnQuadrantClick {}


    constructor(context: Context?) : super(context)

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2
        centerY = h / 2
        radius = Math.min(w, h) / 2
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw four quadrants
        drawQuadrants(canvas)

        // Draw divider lines
        drawSeparatorLines(canvas)
    }

    private fun drawQuadrants(canvas: Canvas) {
        canvas.drawArc(
            ((centerX - radius).toFloat()),
            ((centerY - radius).toFloat()),
            ((centerX + radius).toFloat()),
            ((centerY + radius).toFloat()),
            270F,
            90F,
            true,
            quadrantPaints[0]
        )

        canvas.drawArc(
            ((centerX - radius).toFloat()),
            ((centerY - radius).toFloat()),
            ((centerX + radius).toFloat()),
            ((centerY + radius).toFloat()),
            180F,
            90F,
            true,
            quadrantPaints[1]
        )

        canvas.drawArc(
            ((centerX - radius).toFloat()),
            ((centerY - radius).toFloat()),
            ((centerX + radius).toFloat()),
            ((centerY + radius).toFloat()),
            90F,
            90F,
            true,
            quadrantPaints[2]
        )

        canvas.drawArc(
            ((centerX - radius).toFloat()),
            ((centerY - radius).toFloat()),
            ((centerX + radius).toFloat()),
            ((centerY + radius).toFloat()),
            0F,
            90F,
            true,
            quadrantPaints[3]
        )
    }

    private fun drawSeparatorLines(canvas: Canvas) {
        val paint = Paint()
        paint.color = selectColor
        paint.strokeWidth = 10f

        // Draw horizontal divider line
        canvas.drawLine(
            (centerX - radius).toFloat(),
            centerY.toFloat(),
            (centerX + radius).toFloat(),
            centerY.toFloat(),
            paint
        )

        // Draw vertical divider line
        canvas.drawLine(
            centerX.toFloat(),
            (centerY - radius).toFloat(),
            centerX.toFloat(),
            (centerY + radius).toFloat(),
            paint
        )
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        var quadrantClicked = -1
        // Check clicked quadrant
        if (x < centerX && y < centerY) {
            quadrantClicked = 1
        } else if (x > centerX && y < centerY) {
            quadrantClicked = 0
        } else if (x < centerX && y > centerY) {
            quadrantClicked = 2
        } else if (x > centerX && y > centerY) {
            quadrantClicked = 3
        }

        // Handle click event
        if (event.action == MotionEvent.ACTION_UP && quadrantClicked > -1) {
            performClick()
            handleQuadrantClick(quadrantClicked)
        }
        return true
    }

    fun handleQuadrantClick(quadrant: Int) {
        val currentState = quadrantPaints[quadrant].color == selectColor
        val selectedCount = quadrantClickedList.count { it }

        // Clear current selected quadrants
        if (currentState) {
            quadrantPaints[quadrant].color = unSelectColor
            quadrantClickedList[quadrant] = false
            invalidate()
            onQuadrantClick.onQuadrantClick(quadrantClickedList)
            return
        }

        // Get current selected quadrants
        val selectedQuadrants = quadrantClickedList.mapIndexedNotNull { index, selected ->
            if (selected) index else null
        }

        when (selectedCount) {
            // No quadrants selected, allow choosing any quadrant
            0 -> {
                quadrantPaints[quadrant].color = selectColor
                quadrantClickedList[quadrant] = true
            }

            // One quadrant already selected
            1 -> {
                val firstSelected = selectedQuadrants.first()
                // Check if quadrant is adjacent
                val isAdjacent = when (firstSelected) {
                    0 -> quadrant == 1 || quadrant == 3  // Q1 connects to Q2 or Q4
                    1 -> quadrant == 0 || quadrant == 2  // Q2 connects to Q1 or Q3
                    2 -> quadrant == 1 || quadrant == 3  // Q3 connects to Q2 or Q4
                    3 -> quadrant == 2 || quadrant == 0  // Q4 connects to Q3 or Q1
                    else -> false
                }

                if (isAdjacent) {
                    quadrantPaints[quadrant].color = selectColor
                    quadrantClickedList[quadrant] = true
                } else {
                    // If not adjacent, clear previous selection and select new quadrant
                    quadrantPaints.forEachIndexed { index, paint ->
                        paint.color = unSelectColor
                        quadrantClickedList[index] = false
                    }
                    quadrantPaints[quadrant].color = selectColor
                    quadrantClickedList[quadrant] = true
                }
            }

            // Two quadrants already selected
            2 -> {
                // Only allowed when all quadrants are selected
                if (selectedQuadrants.all { it != quadrant }) {
                    val allQuadrantsSelected =
                        selectedQuadrants.toMutableList().apply { add(quadrant) }
                    if (canSelectAllQuadrants(allQuadrantsSelected)) {
                        quadrantPaints[quadrant].color = selectColor
                        quadrantClickedList[quadrant] = true
                    } else {
                        // If full selection cannot be formed, clear all and select new quadrant
                        quadrantPaints.forEachIndexed { index, paint ->
                            paint.color = unSelectColor
                            quadrantClickedList[index] = false
                        }
                        quadrantPaints[quadrant].color = selectColor
                        quadrantClickedList[quadrant] = true
                    }
                }
            }

            // Three quadrants already selected
            3 -> {
                // Only allow selecting final quadrant to complete full circle
                val remainingQuadrant = (0..3).first { !quadrantClickedList[it] }
                if (quadrant == remainingQuadrant) {
                    quadrantPaints[quadrant].color = selectColor
                    quadrantClickedList[quadrant] = true
                } else {
                    // If clicked quadrant is not the remaining one, clear all and select new
                    quadrantPaints.forEachIndexed { index, paint ->
                        paint.color = unSelectColor
                        quadrantClickedList[index] = false
                    }
                    quadrantPaints[quadrant].color = selectColor
                    quadrantClickedList[quadrant] = true
                }
            }
        }

        invalidate()
        onQuadrantClick.onQuadrantClick(quadrantClickedList)
    }

    // Check whether full four-quadrant selection can be formed
    private fun canSelectAllQuadrants(selectedQuadrants: List<Int>): Boolean {
        // Check whether all selected quadrants are adjacent
        return selectedQuadrants.sorted().let { sorted ->
            sorted.zipWithNext().all { (current, next) ->
                when (current) {
                    0 -> next == 1 || next == 3
                    1 -> next == 2
                    2 -> next == 3
                    else -> false
                }
            }
        }
    }

    fun getQuadrantState(quadrant: Int): Boolean {
        return quadrantClickedList[quadrant]
    }

    fun getQuadrantClickedList(): List<Boolean> {
        return quadrantClickedList
    }

    fun setQuadrantState(quadrantList: List<Boolean>) {
        quadrantList.forEachIndexed { i, item ->
            if (item) {
                quadrantPaints[i].color = selectColor
                quadrantClickedList[i] = true
            } else {
                quadrantPaints[i].color = unSelectColor
                quadrantClickedList[i] = false
            }
        }
        invalidate()
    }
}

interface OnQuadrantClick {
    fun onQuadrantClick(quadrantClickedList: ArrayList<Boolean>) {}
}
