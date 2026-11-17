package io.github.lumiagg.gestureevo.clipboard

import io.github.lumiagg.gestureevo.link.LinkDispatchRule
import io.github.lumiagg.gestureevo.link.LinkMatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardLinkRulesTest {

    private fun rule(
        packageName: String,
        pattern: String? = null,
        template: String? = null,
        mode: LinkMatchType = LinkMatchType.CONTAINS,
        enabled: Boolean = true,
        priority: Int = 0
    ) = LinkDispatchRule(
        name = packageName,
        pattern = pattern.orEmpty(),
        targetPackage = packageName,
        template = template,
        matchType = mode,
        isEnabled = enabled,
        priority = priority
    )

    @Test
    fun patternHitCarriesTheTemplatedScheme() {
        val github = rule("com.github.android", "github.com")
        val taobao = rule(
            "com.taobao.taobao",
            """https?://(?:m\.)?tb\.cn/(\w+)""",
            template = "taobao://item.taobao.com/item.htm?id=$1",
            mode = LinkMatchType.REGEX
        )

        val rules = ClipboardLinkRules.matches(
            "https://m.tb.cn/abc123",
            listOf(github, taobao)
        )

        assertEquals(1, rules.size)
        assertEquals("com.taobao.taobao", rules[0].packageName)
        assertEquals("taobao://item.taobao.com/item.htm?id=abc123", rules[0].uri)
    }

    @Test
    fun everyMatchingRuleIsOfferedNotJustTheWinner() {
        // The chooser exists because one link may reasonably open in more than one app, unlike the
        // launch path where the first rule wins and everything else is discarded.
        val rules = ClipboardLinkRules.matches(
            "https://pan.quark.cn/s/46fe6dd5d9a3",
            listOf(
                rule("com.quark.browser", "pan.quark.cn", priority = 0),
                rule("com.quark.video", "quark.cn", priority = 1)
            )
        )
        assertEquals(2, rules.size)
        assertEquals("com.quark.browser", rules[0].packageName)
        assertEquals("com.quark.video", rules[1].packageName)
    }

    @Test
    fun disabledRuleIsSkipped() {
        val rules = ClipboardLinkRules.matches(
            "https://taobao.com/item?id=1",
            listOf(rule("com.taobao.taobao", "taobao.com", enabled = false))
        )
        assertTrue(rules.isEmpty())
    }

    @Test
    fun ruleWithoutPatternIsNotARule() {
        // A blank pattern is a catch-all on the launch path. A chooser must not: it would put its
        // app in front of every link copied, whatever the link is.
        val rules = ClipboardLinkRules.matches(
            "https://github.com/Leaf-lsgtky/HyperPortal",
            listOf(rule("com.github.android", pattern = null))
        )
        assertTrue(rules.isEmpty())
    }

    @Test
    fun ruleWithoutTargetPackageIsIgnored() {
        val rules = ClipboardLinkRules.matches(
            "https://taobao.com/item?id=1",
            listOf(rule("   ", "taobao.com"))
        )
        assertTrue(rules.isEmpty())
    }

    @Test
    fun theSameAppIsOnlyListedOnce() {
        val first = rule("com.taobao.taobao", "taobao.com", template = "taobao://a", priority = 0)
        val second = rule("com.taobao.taobao", "taobao.com", template = "taobao://b", priority = 1)

        val rules = ClipboardLinkRules.matches("https://taobao.com/1", listOf(first, second))

        assertEquals(1, rules.size)
        assertEquals("taobao://a", rules[0].uri)
    }

    @Test
    fun ownPackageIsNeverOffered() {
        val rules = ClipboardLinkRules.matches(
            "https://taobao.com/item?id=1",
            listOf(rule("io.github.lumiagg.gestureevo", "taobao.com")),
            excludePackage = "io.github.lumiagg.gestureevo"
        )
        assertTrue(rules.isEmpty())
    }

    @Test
    fun textWithoutLinkMatchesNothing() {
        assertTrue(ClipboardLinkRules.matches("", listOf(rule("com.taobao.taobao", "taobao"))).isEmpty())
    }

    @Test
    fun domainPatternRespectsHostBoundary() {
        // "taobao.com" must not claim "notataobao.com"; the shared matcher compares hosts for
        // domain-shaped patterns precisely so this stays true.
        val rules = ClipboardLinkRules.matches(
            "https://notataobao.com/item?id=1",
            listOf(rule("com.taobao.taobao", "taobao.com"))
        )
        assertTrue(rules.isEmpty())
    }

    // --- several links in one paste ------------------------------------------------

    private val driveRules = listOf(
        rule("com.quark.browser", "pan.quark.cn", priority = 0),
        rule("com.baidu.netdisk", "pan.baidu.com", priority = 1),
        rule("com.xunlei.downloadprovider", "pan.xunlei.com", priority = 2),
        rule("com.guangyapan", "guangyapan.com", priority = 3),
    )

    private val sharePaste = """
        【港片】工作者2：我不卖身(2008) 邱礼涛 1080p 国粤双语 中文字幕【2.4G】（豆瓣无词条）
        夸克：https://pan.quark.cn/s/27175a2d5b01
        百度：https://pan.baidu.com/s/1Fie2UYZhAu_HU3NPnTZYLg?pwd=8888
        迅雷：https://pan.xunlei.com/s/VP6UjcuAHMx8lHUog7oRkRYKA1?pwd=qfvu#
        光鸭：https://www.guangyapan.com/s/1969082949871497226_aeOcpo8EdLN_2IRw
    """.trimIndent()

    @Test
    fun everyLinkInOnePasteBecomesItsOwnRow() {
        val links = ClipboardLinkRules.collect(sharePaste, emptyList(), driveRules)

        assertEquals(4, links.size)
        assertEquals(
            listOf(
                "https://pan.quark.cn/s/27175a2d5b01",
                "https://pan.baidu.com/s/1Fie2UYZhAu_HU3NPnTZYLg?pwd=8888",
                "https://pan.xunlei.com/s/VP6UjcuAHMx8lHUog7oRkRYKA1?pwd=qfvu#",
                "https://www.guangyapan.com/s/1969082949871497226_aeOcpo8EdLN_2IRw"
            ),
            links.map { it.url }
        )
    }

    @Test
    fun eachLinkKeepsOnlyTheAppItsOwnDomainPointsAt() {
        // The whole point of splitting the paste: a quark link must not offer 百度网盘 just because
        // the same text also contained a baidu link.
        val links = ClipboardLinkRules.collect(sharePaste, emptyList(), driveRules)

        assertEquals(
            listOf(
                listOf("com.quark.browser"),
                listOf("com.baidu.netdisk"),
                listOf("com.xunlei.downloadprovider"),
                listOf("com.guangyapan"),
            ),
            links.map { link -> link.rules.map { it.packageName } }
        )
    }

    @Test
    fun aLinkNoRuleClaimsIsStillListed() {
        // Without a rule the system handler decides; dropping the row would hide the link
        // entirely from someone who just wants to open it in a browser.
        val links = ClipboardLinkRules.collect(
            "a https://pan.quark.cn/s/1 b https://example.org/x",
            emptyList(),
            driveRules
        )

        assertEquals(2, links.size)
        assertTrue(links[1].rules.isEmpty())
    }

    @Test
    fun theSameLinkPastedTwiceIsOneRow() {
        val links = ClipboardLinkRules.collect(
            "https://pan.quark.cn/s/1 and again https://pan.quark.cn/s/1",
            emptyList(),
            driveRules
        )
        assertEquals(1, links.size)
    }

    @Test
    fun linksBeyondTheLimitAreIgnored() {
        // A pasted page can hold dozens of URLs; six rows keep the strip shorter than a keyboard.
        val text = (1..9).joinToString(" ") { "https://pan.quark.cn/s/$it" }
        val links = ClipboardLinkRules.collect(text, emptyList(), driveRules)

        assertEquals(ClipboardLinkRules.MAX_LINKS, links.size)
        assertEquals(6, links.size)
    }

    @Test
    fun textWithoutLinksCollectsNothing() {
        assertTrue(ClipboardLinkRules.collect("没有链接的一段话", emptyList(), driveRules).isEmpty())
    }
}
