package io.github.lumiagg.gestureevo.link

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LinkTargetPreview(val label: String, val icon: Drawable?)

/** Uses the actual routing/Intent builders, without issuing a network probe while hovering. */
internal suspend fun previewLinkTarget(
    context: Context, url: String, sourcePackage: String, sourceText: String? = null,
): LinkTargetPreview? = withContext(Dispatchers.IO) {
    val result = LinkDispatcher.route(context, url, sourcePackage, sourceText, resolveShortLinks = false)
    val routed = result as? LinkDispatchResult.Routed
    val destination = when (result) {
        is LinkDispatchResult.Routed -> result.url
        is LinkDispatchResult.Exempted -> result.url
        is LinkDispatchResult.SystemDefault -> result.url
        LinkDispatchResult.NoRule -> url
        LinkDispatchResult.Failed -> return@withContext null
    }
    val intent = LinkLauncher.buildLaunchIntent(destination, routed?.rule?.targetPackage.orEmpty(), false, routed?.rule?.component)
        ?: return@withContext null
    val manager = context.packageManager
    val explicitPackage = intent.component?.packageName ?: intent.`package`
    val pkg = explicitPackage ?: manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let { info ->
        info.packageName.takeUnless { it == "android" ||
            info.name.contains("ResolverActivity") || info.name.contains("ChooserActivity") }
    } ?: return@withContext null
    try {
        val label = manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
        val icon = runCatching { IconHelper.getIconDrawable(context, pkg, routed?.rule?.iconPath.orEmpty()) }.getOrNull()
        LinkTargetPreview(label, icon)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
