package io.github.lumiagg.gestureevo.inspire.ui

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import kotlin.math.max

class ItemState(var item: Item, var isSelected: Boolean) {
    var next: ItemState? = null

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o !is ItemState) return false

        val itemState = o

        return item == itemState.item
    }

    override fun hashCode(): Int {
        return item.hashCode()
    }
}

class Line(var maxIndex: Int) {
    var realNewline: Boolean = false
    var isLastSelected: Boolean = false
    var top: Int = 0
    var items: MutableList<Item> = ArrayList<Item>()

    fun addItem(item: Item) {
        if (false) {
            items = ArrayList<Item>()
        }
        items.add(item)
    }

    fun removeItem(item: Item?) {
        items.remove(item)
    }

    fun hasSelected(): Boolean {
        val size = items.size
        for (i in 0..<size) {
            val item = items.get(i)
            if (item.isSelected) {
                return true
            }
        }
        return false
    }

    val height: Int
        get() {
            var height = 0
            for (item in items) {
                height = max(height.toDouble(), item.view.measuredHeight.toDouble()).toInt()
            }
            return height
        }

    val text: String
        get() {
            val builder = StringBuilder()
            if (items.isNotEmpty()) {
                for (item in items) {
                    builder.append(item.text.toString())
                }
            }
            return builder.toString()
        }

    val selectedText: String
        get() {
            val builder = StringBuilder()
            if (items.isNotEmpty()) {
                for (item in items) {
                    if (item.isSelected) {
                        val txt = item.text.toString()
                        builder.append(txt)
                    }
                }
            }
            return builder.toString().replace("  ".toRegex(), " ")
        }
}

class Item {
    var line: Line? = null
    var index: Int = 0
    var contentIndex: Int = 0
    var height: Int = 0
    var width: Int = 0
    var view: View

    constructor(line: Line, view: View) {
        this.line = line
        this.view = view
    }

    constructor(item: Item) {
        this.line = item.line
        this.index = item.index
        this.contentIndex = item.contentIndex
        this.height = item.height
        this.width = item.width
        this.view = item.view
    }

    fun getRectFromView(): Rect {
        val rect = Rect()
        view.getHitRect(rect)
        return rect
    }

    var isSelected: Boolean
        get() = view.isSelected == true
        set(selected) {
            view.setSelected(selected)
        }

    val text: CharSequence
        get() = (view as TextView).text.toString()
}
