package io.github.lumiagg.gestureevo.inspire

import android.content.Intent
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceInspireEvent
import io.github.lumiagg.gestureevo.gesture.InspireEvent
import io.github.lumiagg.gestureevo.inspire.ui.BigBangActionWrapper
import io.github.lumiagg.gestureevo.inspire.ui.InspireContentView
import io.github.lumiagg.gestureevo.pin.PinPoolStore
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_DEFAULT_SHARE_IMAGE_APP
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.GalleryImageManager
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class InspireActionDispatcher(
    private val activity: InspireContentActivity,
    private val scope: CoroutineScope,
    private val contentView: InspireContentView,
    private val tokenContentController: TokenContentController,
    private val imageShareLauncher: ImageShareLauncher,
    private val launchImageAction: (CoroutineDispatcher, suspend () -> Unit) -> Unit,
    private val finishAndClearInspireData: suspend () -> Unit,
    private val launchImageEditor: () -> Unit,
    private val showSelectedTextEditor: () -> Unit,
    private val splitSegmentedSelectionOrItemToCharacters: (Int) -> Unit,
) {
    fun createActionWrapper() = BigBangActionWrapper(
        onBigBangSelected = {},
        onBigBangSendToEVO = { sendSelectedTextToEvo() },
        onBigBangCopy = ::copySelectedText,
        onBigBangShare = ::shareSelectedText,
        onBigBangURL = ::openSelectedUrl,
        onBigBangPin = ::pinSelectedText,
        onBigBangTranslation = ::translateSelectedText,
        onParticipleURLClick = ::openCurrentContentUrl,
        onParticipleReselectClick = ::reselectScreenContent,
        onParticipleSaveImageClick = ::saveImage,
        onParticipleOpenImageClick = ::openImage,
        onBigBangEdit = { _, _, _ -> showSelectedTextEditor() },
        onParticipleEditRawTextClick = showSelectedTextEditor,
        onParticipleShareButtonClick = ::shareImage,
        onParticiplePinImageClick = ::pinImage,
        onParticiplePinImageLongPress = ::pinImageToPool,
        onParticipleScanQR = ::scanQr,
        onBigBangContentLongPress = { targetContentIndex, _ ->
            splitSegmentedSelectionOrItemToCharacters(targetContentIndex)
        },
    )

    private fun sendSelectedTextToEvo() {
        scope.launch {
            InspireHelperUtils.searchText(activity, contentView.getSelectedTextNotEmpty())
            finishAndClearInspireData()
        }
    }

    private fun copySelectedText(isPress: Boolean) {
        ClipboardUtils.copyText(activity.applicationContext, contentView.getSelectedTextNotEmpty())
        if (!isPress) {
            InspireDataHolder.clear()
            activity.finish()
        } else {
            contentView.clearSelectedText()
        }
    }

    private fun shareSelectedText() {
        InspireHelperUtils.shareText(activity, contentView.getSelectedTextNotEmpty())
        InspireDataHolder.clear()
        activity.finish()
    }

    private fun openSelectedUrl() {
        scope.launch {
            if (InspireHelperUtils.extractAndOpenURL(activity, contentView.getSelectedTextNotEmpty())) {
                InspireDataHolder.clear()
                activity.finish()
            }
        }
    }

    private fun pinSelectedText() {
        val content = contentView.getSelectedTextNotEmpty()
        contentView.clearSelectedText()
        scope.launch { produceInspireEvent(InspireEvent(text = content)) }
    }

    private fun translateSelectedText() {
        scope.launch {
            InspireHelperUtils.translateText(activity, contentView.getSelectedTextNotEmpty())
            finishAndClearInspireData()
        }
    }

    private fun openCurrentContentUrl() {
        scope.launch {
            val query = contentView.getCurrentContent()?.joinToString("").orEmpty()
            if (InspireHelperUtils.extractAndOpenURL(activity, query)) {
                InspireDataHolder.clear()
                activity.finish()
            }
        }
    }

    private fun reselectScreenContent() {
        val selectionRect = InspireDataHolder.dragRect?.let { android.graphics.Rect(it) } ?: return
        val forceImageTextSelection = InspireDataHolder.forceImageTextSelection
        scope.launch {
            produceInspireEvent(
                InspireEvent(
                    rect = selectionRect,
                    reSelect = true,
                    forceImageTextSelection = forceImageTextSelection,
                )
            )
            InspireDataHolder.clear()
            activity.finish()
        }
    }

    private fun saveImage(isPress: Boolean) {
        contentView.acquireImageHandle()?.let { bitmapHandle ->
            launchImageAction(Dispatchers.IO) {
                bitmapHandle.use {
                    InspireHelperUtils.saveImageToGallery(activity, it, isPress)
                    PinPoolStore.addScreenContentImageIfEnabled(activity, it.requireBitmap())
                }
                finishAndClearInspireData()
            }
        }
    }

    private fun openImage(isPress: Boolean) {
        contentView.acquireImageHandle()?.let { bitmapHandle ->
            if (isPress) {
                copyImage(bitmapHandle)
            } else {
                openImageOrEditor(bitmapHandle)
            }
        }
    }

    private fun copyImage(bitmapHandle: ManagedBitmap) {
        launchImageAction(Dispatchers.IO) {
            bitmapHandle.use {
                InspireHelperUtils.copyBitmapToClipboard(activity, it, autoSaveToPinPool = true)
            }
            finishAndClearInspireData()
        }
    }

    private fun openImageOrEditor(bitmapHandle: ManagedBitmap) {
        launchImageAction(Dispatchers.IO) {
            val targetPkg = activity.settingsStore.readKey(InspireSettingsActivity.KEY_IMAGE_APP, "")
            if (targetPkg.isEmpty()) {
                bitmapHandle.close()
                withContext(Dispatchers.Main) { launchImageEditor() }
            } else {
                openImageInTargetApp(bitmapHandle, targetPkg)
            }
        }
    }

    private suspend fun openImageInTargetApp(bitmapHandle: ManagedBitmap, targetPkg: String) {
        val uri = bitmapHandle.use {
            InspireHelperUtils.saveImageToGallery(activity, it, true)
        }
        uri?.let {
            GalleryImageManager.openImageInGallery(
                activity.applicationContext,
                uri,
                targetPkg = targetPkg,
            )
        }
        finishAndClearInspireData()
    }

    private fun shareImage(isPress: Boolean) {
        contentView.acquireImageHandle()?.let { bitmapHandle ->
            scope.launch {
                val targetPackage = if (isPress) {
                    ""
                } else {
                    activity.settingsStore.readKey(KEY_DEFAULT_SHARE_IMAGE_APP, "")
                }
                bitmapHandle.use {
                    imageShareLauncher.shareImage(
                        bitmap = it.requireBitmap(),
                        targetPackage = targetPackage.ifEmpty { null },
                        targetActivity = null,
                        forceChooser = isPress || targetPackage.isEmpty(),
                    )
                }
                InspireDataHolder.clear()
                activity.finish()
            }
        }
    }

    private fun pinImage() {
        contentView.acquireImageHandle()?.let { bitmap ->
            scope.launch { produceInspireEvent(InspireEvent(image = bitmap)) }
            InspireDataHolder.clear()
            activity.finish()
        }
    }

    private fun pinImageToPool() {
        contentView.acquireImageHandle()?.let { bitmapHandle ->
            launchImageAction(Dispatchers.IO) {
                bitmapHandle.use {
                    PinPoolStore.addImagePin(activity, it.requireBitmap())
                }
                finishAndClearInspireData()
            }
        }
    }

    private fun scanQr() {
        contentView.acquireImageHandle()?.let { bitmapHandle ->
            launchImageAction(Dispatchers.Default) {
                InspireHelperUtils.scanQR(activity, bitmapHandle)?.let { contents ->
                    withContext(Dispatchers.Main) {
                        tokenContentController.setCustomContent(contents)
                    }
                }
            }
        }
    }
}
