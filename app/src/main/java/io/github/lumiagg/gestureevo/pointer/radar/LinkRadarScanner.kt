package io.github.lumiagg.gestureevo.pointer.radar

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.text.Spanned
import android.text.style.URLSpan
import io.github.lumiagg.gestureevo.inspire.preferredScreenTextIndices
import io.github.lumiagg.gestureevo.inspire.protectedAccessibilityNodes
import io.github.lumiagg.gestureevo.inspire.readAccessibilityCharacterLocations
import io.github.lumiagg.gestureevo.inspire.withAccessibilityNodes
import io.github.lumiagg.gestureevo.link.LinkExtractor
import io.github.lumiagg.gestureevo.link.LinkRuleRepository
import io.github.lumiagg.gestureevo.link.LinkTextMatch
import io.github.lumiagg.gestureevo.link.selectLinkTextMatches

/** Link-specific projection of the same node snapshots used by pointer and screen capture. */
object LinkRadarScanner {
    suspend fun scan(service: AccessibilityService): List<LinkRegion> {
        val patterns = LinkRuleRepository.loadExtractPatterns(service)
        return withAccessibilityNodes(service, nodeLimit = 3000, depthLimit = 64) { nodes ->
            val protected = protectedAccessibilityNodes(nodes)
            // A named URLSpan carries metadata that duplicate plain text cannot replace.
            val spanNodes = nodes.indices.filter { index ->
                val node = nodes[index]
                val native = node.nativeText as? Spanned
                node.readable && index !in protected && !node.fromDescription && native != null &&
                    native.getSpans(0, native.length, URLSpan::class.java).isNotEmpty()
            }
            (preferredScreenTextIndices(nodes, protected) + spanNodes).distinct().flatMap { index ->
                val node = nodes[index]
                if (node.info.packageName?.toString() == service.packageName) return@flatMap emptyList()
                val text = node.text ?: return@flatMap emptyList()
                val native = node.nativeText as? Spanned
                val spans = if (node.fromDescription || native == null) emptyList() else
                    native.getSpans(0, native.length, URLSpan::class.java).mapNotNull { span ->
                        val start = native.getSpanStart(span)
                        val end = native.getSpanEnd(span)
                        span.url?.takeIf { start >= 0 && end in (start + 1)..text.length }
                            ?.let { LinkTextMatch(it, start, end) }
                    }
                val matches = selectLinkTextMatches(spans, LinkExtractor.findAll(text, patterns))
                if (matches.isEmpty()) return@flatMap emptyList()
                val characters = if (node.fromDescription) null else
                    readAccessibilityCharacterLocations(node.info, text)
                if (characters?.textChanged == true) return@flatMap emptyList()
                matches.mapNotNull { match ->
                    val locations = characters?.bounds
                    val rects = locations?.let { characterRegions(it, match.start, match.end, node.bounds) }
                    // Supported coordinates with no visible glyphs mean off-screen, not a fallback.
                    if (rects != null && rects.isEmpty()) return@mapNotNull null
                    LinkRegion(match.url, rects ?: listOf(Rect(node.bounds)), locations != null,
                        index, match.start, node.info.packageName?.toString().orEmpty(), text)
                }
            }.distinctBy { Triple(it.url, it.rects, it.sourcePackage) }
                .sortedWith(compareBy({ it.rects.first().top }, { it.rects.first().left }, { it.textOffset }))
        }
    }

    private fun characterRegions(locations: Array<RectF?>, start: Int, end: Int, clip: Rect): List<Rect> {
        val lines = mutableListOf<RectF>()
        for (index in start until minOf(end, locations.size)) {
            val glyph = locations[index] ?: continue
            if (glyph.isEmpty) continue
            val visible = RectF(glyph)
            if (!visible.intersect(RectF(clip))) continue
            val line = lines.lastOrNull()
            if (line != null && minOf(line.bottom, visible.bottom) > maxOf(line.top, visible.top)) {
                line.union(visible)
            } else lines += visible
        }
        return lines.map { Rect().also(it::roundOut) }
    }
}
