// 来源：wangzhen9331 的 GestureEVO 自用改版（GPL-3.0-only），检测后端按本仓库架构适配。

package io.github.lumiagg.gestureevo.clipboard

import io.github.lumiagg.gestureevo.link.LinkDispatchRule
import io.github.lumiagg.gestureevo.link.LinkDispatcher
import io.github.lumiagg.gestureevo.link.LinkExtractPattern
import io.github.lumiagg.gestureevo.link.LinkExtractor

/**
 * One hit of a dispatch rule: the app to offer, plus the link it should actually receive.
 *
 * [uri] differs from the copied URL when the rule carries a template, which is the whole point
 * of having rules here: `https://m.tb.cn/h.xxx` becomes `taobao://item.taobao.com/...` so the app
 * opens its own item page instead of the web view it would show for the https link.
 */
data class ClipboardLinkRule(val packageName: String, val uri: String)

/**
 * One link found in the copied text, together with the apps its rules point at.
 *
 * A paste like "夸克：… 百度：… 迅雷：…" carries several links, and they are not interchangeable:
 * each one belongs to a different cloud drive. Keeping them apart is what lets the strip offer
 * "open these two" instead of guessing one.
 */
data class ClipboardLinkCandidate(val url: String, val rules: List<ClipboardLinkRule>)

/**
 * Turns the link dispatch rule table into the app strip this popup shows.
 *
 * The table lives in 链接分发 → 分发规则 (`LinkRuleRepository`), so there is exactly one place to
 * teach the app "a link containing X belongs to app Y". Matching and rewriting come straight
 * from [LinkDispatcher], so a rule behaves identically whether the link was dragged, tapped in
 * the word-selection bar, picked by the radar, or copied.
 *
 * Unlike [LinkDispatcher.findRule], which returns only the winner, this collects **every** match:
 * a chooser exists precisely because one link may reasonably open in more than one app. Package
 * duplicates are dropped, so the highest-priority rule per app wins.
 *
 * Rules without a pattern or without a target app take no part. That is stricter than the launch
 * path, where a pattern-less rule is a documented catch-all: in a chooser a catch-all is not a
 * rule, it is noise — it would put its app in front of every link copied, whatever the link is.
 */
object ClipboardLinkRules {

    /**
     * A pasted page can hold dozens of URLs; a chooser that long stops being a chooser. Six rows
     * also keep the strip shorter than a keyboard, so the popup never needs to scroll.
     */
    const val MAX_LINKS = 6

    /**
     * Every link in [text], in reading order, each with its own rule hits.
     *
     * Extraction runs through [LinkExtractor.findAll], the same pass the pointer radar uses, so a
     * scheme covered by a user extract pattern (magnet, ed2k, a private app scheme) is offered here
     * as well. Duplicates collapse on the URL itself: the same drive link pasted twice, once bare
     * and once inside a markdown link, is still one target.
     */
    fun collect(
        text: String,
        extractPatterns: List<LinkExtractPattern>,
        rules: List<LinkDispatchRule>,
        excludePackage: String? = null,
        maxLinks: Int = MAX_LINKS
    ): List<ClipboardLinkCandidate> {
        if (text.isBlank() || maxLinks <= 0) return emptyList()

        val urls = LinkedHashSet<String>(maxLinks)
        for (match in LinkExtractor.findAll(text, extractPatterns)) {
            if (urls.size >= maxLinks) break
            urls.add(match.url)
        }
        return urls.map { ClipboardLinkCandidate(it, matches(it, rules, excludePackage)) }
    }

    fun matches(
        url: String,
        rules: List<LinkDispatchRule>,
        excludePackage: String? = null
    ): List<ClipboardLinkRule> {
        if (url.isBlank() || rules.isEmpty()) return emptyList()

        val hits = ArrayList<ClipboardLinkRule>(rules.size)
        val seen = HashSet<String>(rules.size)

        for (rule in rules.sortedBy { it.priority }) {
            if (!rule.isEnabled) continue
            if (rule.pattern.isBlank()) continue
            val packageName = rule.targetPackage.trim()
            if (packageName.isEmpty()) continue
            if (packageName == excludePackage) continue
            if (!LinkDispatcher.matchRule(url, rule)) continue
            if (!seen.add(packageName)) continue

            hits.add(ClipboardLinkRule(packageName, LinkDispatcher.applyTemplate(url, rule)))
        }
        return hits
    }
}
