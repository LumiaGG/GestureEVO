package io.github.lumiagg.gestureevo.inspire.ui

interface BigBangStatusListener {
    fun onSelected() {}

    fun onTranslation() {}

    fun onCopy(isPress: Boolean) {}

    fun onSendToEVO(isPress: Boolean) {}

    fun onShare() {}

    fun onEdit(placeLine: Int, targetItemIndex: Int, text: String) {}

    fun onURL() {}

    fun onPin() {}

    fun onContentLongPress(targetContentIndex: Int, text: String) {}
}
