package io.github.lumiagg.gestureevo.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

data class IconEditorRequest(
    val wheelStyle: String? = null,
    val imageBadge: Boolean = false,
    val appPackage: String? = null,
)

/** Opens an image source in the editor, returning only an explicitly applied PNG. */
class IconEditorContract : ActivityResultContract<IconEditorRequest, String?>() {
    override fun createIntent(context: Context, input: IconEditorRequest) =
        Intent(context, IconEditorActivity::class.java)
            .putExtra(IconEditorActivity.EXTRA_WHEEL_STYLE, input.wheelStyle)
            .putExtra(IconEditorActivity.EXTRA_IMAGE_BADGE, input.imageBadge)
            .putExtra(IconEditorActivity.EXTRA_APP_PACKAGE, input.appPackage)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(IconEditorActivity.EXTRA_ICON_PATH)
        else null
}

data class TextIconEditorRequest(val text: String, val imageBadge: Boolean = false)

class TextIconEditorContract : ActivityResultContract<TextIconEditorRequest, String?>() {
    override fun createIntent(context: Context, input: TextIconEditorRequest) =
        Intent(context, IconEditorActivity::class.java)
            .putExtra(IconEditorActivity.EXTRA_TEXT_ICON, input.text)
            .putExtra(IconEditorActivity.EXTRA_IMAGE_BADGE, input.imageBadge)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(IconEditorActivity.EXTRA_TEXT_ICON)
        else null
}
