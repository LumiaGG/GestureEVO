package io.github.lumiagg.gestureevo.pointer.radar

import android.content.Context
import android.content.Intent
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.link.LinkLauncher

/** Both pointer entry points keep routing, source metadata and the existing multi-link chooser. */
internal suspend fun openPointerLinks(context: Context, links: List<LinkRegion>, side: String?) {
    if (links.size == 1) {
        val link = links.first()
        LinkLauncher.openUrl(context, link.url, link.sourcePackage, link.sourceText, side)
    } else if (links.isNotEmpty()) {
        context.startActivity(Intent(context, LinkSelectionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putStringArrayListExtra(LinkSelectionActivity.EXTRA_URLS, ArrayList(links.map { it.url }))
            putStringArrayListExtra(LinkSelectionActivity.EXTRA_SOURCE_PACKAGES, ArrayList(links.map { it.sourcePackage }))
            putStringArrayListExtra(LinkSelectionActivity.EXTRA_SOURCE_TEXTS, ArrayList(links.map { it.sourceText }))
            putExtra(TriggerSide.EXTRA_TRIGGER_SIDE, side)
        })
    }
}
