package io.github.lumiagg.gestureevo.inspire.ui

class BigBangActionWrapper(
    private val onBigBangSelected: () -> Unit = {},
    private val onBigBangTranslation: () -> Unit = {},
    private val onBigBangCopy: (Boolean) -> Unit = {},
    private val onBigBangSendToEVO: (Boolean) -> Unit = {},
    private val onBigBangShare: () -> Unit = {},
    private val onBigBangEdit: (Int, Int, String) -> Unit = { _, _, _ -> },
    private val onBigBangURL: () -> Unit = {},
    private val onBigBangPin: () -> Unit = {},
    private val onParticipleURLClick: () -> Unit = {},
    private val onParticipleReselectClick: () -> Unit = {},
    private val onParticipleSaveImageClick: (Boolean) -> Unit = {},
    private val onParticipleOpenImageClick: (Boolean) -> Unit = {},
    private val onParticipleEditRawTextClick: () -> Unit = {},
    private val onParticipleShareButtonClick: (Boolean) -> Unit = {},
    private val onParticiplePinImageClick: () -> Unit = {},
    private val onParticiplePinImageLongPress: () -> Unit = {},
    private val onParticipleScanQR: () -> Unit = {},
    private val onBigBangContentLongPress: (Int, String) -> Unit = { _, _ -> },
) : BigBangStatusListener {
    override fun onSelected() = onBigBangSelected()
    override fun onTranslation() = onBigBangTranslation()
    override fun onCopy(isPress: Boolean) = onBigBangCopy(isPress)
    override fun onSendToEVO(isPress: Boolean) = onBigBangSendToEVO(isPress)
    override fun onShare() = onBigBangShare()
    override fun onPin() = onBigBangPin()
    override fun onURL() = onBigBangURL()
    override fun onContentLongPress(targetContentIndex: Int, text: String) =
        onBigBangContentLongPress(targetContentIndex, text)
    override fun onEdit(placeLine: Int, targetItemIndex: Int, text: String) =
        onBigBangEdit(placeLine, targetItemIndex, text)

    fun handleSelected() = onBigBangSelected()
    fun handleTranslation() = onBigBangTranslation()
    fun handleCopy(isPress: Boolean) = onBigBangCopy(isPress)
    fun handleSendToEVO(isPress: Boolean) = onBigBangSendToEVO(isPress)
    fun handleShare() = onBigBangShare()
    fun handlePin() = onBigBangPin()
    fun handleBigBangUrlClick() = onBigBangURL()
    fun handleEditClick() = onBigBangEdit(0, 0, "")

    fun handleUrlClick() = onParticipleURLClick()
    fun handleScanQR() = onParticipleScanQR()
    fun handleReselectClick() = onParticipleReselectClick()
    fun handleSaveImageClick(isPress: Boolean) = onParticipleSaveImageClick(isPress)
    fun handleOpenImageClick(isPress: Boolean) = onParticipleOpenImageClick(isPress)
    fun handleEditRawTextClick() = onParticipleEditRawTextClick()
    fun handleShareButtonClick(isPress: Boolean) = onParticipleShareButtonClick(isPress)
    fun handlePinImageClick() = onParticiplePinImageClick()
    fun handlePinImageLongPress() = onParticiplePinImageLongPress()
}
