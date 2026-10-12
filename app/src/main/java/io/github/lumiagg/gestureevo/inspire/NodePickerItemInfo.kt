package io.github.lumiagg.gestureevo.inspire

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

class NodePickerItemInfo(nodeInfo: AccessibilityNodeInfo) {
    var cls = "unknown"
    var id: String? = null
    var text: String? = null
    var clickable: Boolean
    var checkable: Boolean
    var editable: Boolean
    var longClickable: Boolean
    var visible: Boolean
    var rect = Rect()
    var parent: NodePickerItemInfo? = null
    var children = ArrayList<NodePickerItemInfo?>()

    init {
        val className = nodeInfo.className
        if (className != null) cls = className.toString()
        id = nodeInfo.viewIdResourceName
        val nodeInfoText = nodeInfo.text
        if (nodeInfoText != null) {
            text = nodeInfoText.toString()
        }
        clickable = nodeInfo.isClickable
        checkable = nodeInfo.isCheckable
        editable = nodeInfo.isEditable
        longClickable = nodeInfo.isLongClickable
        visible = nodeInfo.isVisibleToUser
        nodeInfo.getBoundsInScreen(rect)
        for (i in 0 until nodeInfo.childCount) {
            val child = nodeInfo.getChild(i)
            if (child == null) {
                children.add(null)
            } else {
                val info = NodePickerItemInfo(child)
                info.parent = this
                children.add(info)
            }
        }
    }

    fun findChildrenById(id: String): ArrayList<NodePickerItemInfo> {
        val items = ArrayList<NodePickerItemInfo>()
        for (child in children) {
            if (child == null) continue
            if (id == child.id) items.add(child)
            items.addAll(child.findChildrenById(id))
        }
        return items
    }

    val isUsable: Boolean
        get() = (clickable || checkable || editable || longClickable) && visible
}
