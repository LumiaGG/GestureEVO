package io.github.lumiagg.gestureevo.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure routing logic: matching, template rewriting and exemptions.
 *
 * These are the parts that decide whether a link is intercepted at all, so they are worth
 * pinning down without an Android device.
 */
class LinkDispatcherTest {

    private fun rule(
        pattern: String,
        matchType: LinkMatchType = LinkMatchType.CONTAINS,
        template: String? = null,
        pkg: String = "com.example.browser",
        enabled: Boolean = true,
        priority: Int = 0
    ) = LinkDispatchRule(
        pattern = pattern,
        matchType = matchType,
        template = template,
        targetPackage = pkg,
        isEnabled = enabled,
        priority = priority
    )

    @Test
    fun containsMatchesBareDomainAndSubdomain() {
        val r = rule("example.com")
        assertTrue(LinkDispatcher.matchRule("https://example.com/path", r))
        assertTrue(LinkDispatcher.matchRule("https://www.example.com/path", r))
        assertTrue(LinkDispatcher.matchRule("https://m.example.com/path", r))
    }

    @Test
    fun containsDoesNotMatchUnrelatedDomain() {
        val r = rule("example.com")
        assertFalse(LinkDispatcher.matchRule("https://notexample.com/path", r))
        assertFalse(LinkDispatcher.matchRule("https://example.org/path", r))
    }

    @Test
    fun containsFallsBackToSubstringForNonDomainPatterns() {
        val r = rule("share?id=")
        assertTrue(LinkDispatcher.matchRule("https://host/share?id=42", r))
        assertFalse(LinkDispatcher.matchRule("https://host/other", r))
    }

    @Test
    fun regexMatchesAndCaptures() {
        val r = rule(
            pattern = "https://shop\\.example\\.com/item/(\\d+)",
            matchType = LinkMatchType.REGEX
        )
        assertTrue(LinkDispatcher.matchRule("https://shop.example.com/item/123", r))
        assertFalse(LinkDispatcher.matchRule("https://shop.example.com/other/123", r))
    }

    @Test
    fun exactRequiresWholeUrl() {
        val r = rule("https://example.com/a", LinkMatchType.EXACT)
        assertTrue(LinkDispatcher.matchRule("https://example.com/a", r))
        assertTrue(LinkDispatcher.matchRule("HTTPS://EXAMPLE.COM/A", r))
        assertFalse(LinkDispatcher.matchRule("https://example.com/a?x=1", r))
    }

    @Test
    fun blankPatternNeverMatches() {
        assertFalse(LinkDispatcher.matchRule("https://example.com", rule("")))
    }

    @Test
    fun invalidRegexIsIgnoredInsteadOfCrashing() {
        val r = rule("[unclosed", LinkMatchType.REGEX)
        assertFalse(LinkDispatcher.matchRule("https://example.com", r))
    }

    @Test
    fun templateBackfillReplacesCaptureGroups() {
        val r = rule(
            pattern = "https://item\\.example\\.com/(\\d+)",
            matchType = LinkMatchType.REGEX,
            template = "exampleapp://item/$1"
        )
        assertEquals(
            "exampleapp://item/987",
            LinkDispatcher.applyTemplate("https://item.example.com/987", r)
        )
    }

    @Test
    fun templateWithoutMatchReturnsOriginalUrl() {
        val r = rule("nomatch/(\\d+)", LinkMatchType.REGEX, template = "app://$1")
        val url = "https://example.com/x"
        assertEquals(url, LinkDispatcher.applyTemplate(url, r))
    }

    @Test
    fun blankTemplateLeavesUrlUntouched() {
        val url = "https://example.com/a"
        assertEquals(url, LinkDispatcher.applyTemplate(url, rule("example.com")))
        assertEquals(url, LinkDispatcher.applyTemplate(url, rule("example.com", template = "  ")))
    }

    @Test
    fun exemptMatchesDomainAndSubdomainOnly() {
        val exempts = listOf(LinkExemptDomain(domain = "bank.com"))
        assertEquals("bank.com", LinkDispatcher.isExempt("https://bank.com/x", "", exempts))
        assertEquals("bank.com", LinkDispatcher.isExempt("https://secure.bank.com/x", "", exempts))
        assertNull(LinkDispatcher.isExempt("https://mybank.com/x", "", exempts))
    }

    @Test
    fun exemptCanBeLimitedToASourceApp() {
        val exempts = listOf(
            LinkExemptDomain(domain = "bank.com", sourcePackage = "com.bank.app")
        )
        assertEquals(
            "bank.com",
            LinkDispatcher.isExempt("https://bank.com/x", "com.bank.app", exempts)
        )
        assertNull(
            LinkDispatcher.isExempt("https://bank.com/x", "com.other.app", exempts)
        )
    }

    @Test
    fun disabledExemptIsIgnored() {
        val exempts = listOf(LinkExemptDomain(domain = "bank.com", isEnabled = false))
        assertNull(LinkDispatcher.isExempt("https://bank.com/x", "", exempts))
    }

    @Test
    fun firstEnabledRuleWinsByPriority() {
        val catchAll = rule("com", pkg = "com.catch", priority = 10)
        val specific = rule("shop.example.com", pkg = "com.shop", priority = 0)
        val found = LinkDispatcher.findRule(
            "https://shop.example.com/item",
            listOf(catchAll, specific)
        )
        assertEquals("com.shop", found?.targetPackage)
    }

    @Test
    fun disabledRuleIsSkipped() {
        val disabled = rule("example.com", pkg = "com.a", enabled = false, priority = 0)
        val fallback = rule("example.com", pkg = "com.b", priority = 1)
        val found = LinkDispatcher.findRule(
            "https://example.com/x",
            listOf(disabled, fallback)
        )
        assertEquals("com.b", found?.targetPackage)
    }

    @Test
    fun ruleWithoutTargetAppIsSkipped() {
        val empty = rule("example.com", pkg = "", priority = 0)
        assertNull(LinkDispatcher.findRule("https://example.com/x", listOf(empty)))
    }
}
