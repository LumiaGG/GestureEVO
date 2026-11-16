package io.github.lumiagg.gestureevo.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Extraction decides which substring is treated as the link, so it needs pinning down. */
class LinkExtractorTest {

    private fun pattern(regex: String, enabled: Boolean = true) =
        LinkExtractPattern(name = "test", pattern = regex, isEnabled = enabled)

    @Test
    fun userPatternMatchesBeforeBuiltInDetector() {
        val text = "复制这段 magnet:?xt=urn:btih:ABCDEF123456 打开"
        val result = LinkExtractor.extract(text, LinkExtractPattern.builtinDefaults())
        assertEquals("magnet:?xt=urn:btih:ABCDEF123456", result)
    }

    @Test
    fun fallsBackToBuiltInDetectorWhenNoPatternMatches() {
        val result = LinkExtractor.extract(
            "看这个 https://example.com/path",
            listOf(pattern("nothing-here"))
        )
        assertEquals("https://example.com/path", result)
    }

    @Test
    fun disabledPatternIsIgnored() {
        val result = LinkExtractor.extract(
            "magnet:?xt=urn:btih:XYZ https://example.com/a",
            listOf(pattern("magnet:\\S+", enabled = false))
        )
        assertEquals("https://example.com/a", result)
    }

    @Test
    fun longestMatchWins() {
        val text = "https://example.com/very/long/path"
        val result = LinkExtractor.extract(
            text,
            listOf(pattern("example\\.com"), pattern("https://example\\.com/\\S+"))
        )
        assertEquals("https://example.com/very/long/path", result)
    }

    @Test
    fun invalidPatternIsSkippedSafely() {
        val result = LinkExtractor.extract(
            "https://example.com/a",
            listOf(pattern("[unclosed"))
        )
        assertEquals("https://example.com/a", result)
    }

    @Test
    fun blankTextReturnsNull() {
        assertNull(LinkExtractor.extract("   ", LinkExtractPattern.builtinDefaults()))
    }

    @Test
    fun textWithoutLinkReturnsNull() {
        assertNull(LinkExtractor.extract("没有链接的一段话", LinkExtractPattern.builtinDefaults()))
    }

    @Test fun allLinksKeepCorrectOffsetsAndRepeatedOccurrences() {
        val text = "https://example.com/a and https://example.com/a"
        val links = LinkExtractor.findAll(text, LinkExtractPattern.builtinDefaults())
        assertEquals(2, links.size)
        links.forEach { assertEquals(it.url, text.substring(it.start, it.end)) }
        assertEquals(0, links.first().start)
        assertEquals(text.lastIndexOf("https://"), links.last().start)
    }

    @Test fun namedUrlSpanTakesPrecedenceOverRegexAtSameRange() {
        val preferred = listOf(LinkTextMatch("https://example.com/hidden", 0, 10))
        val matches = selectLinkTextMatches(preferred, listOf(LinkTextMatch("https://other.com", 0, 10)))
        assertEquals(preferred, matches)
    }

    @Test fun customSchemesAndBareDomainsCanCoexist() {
        val text = "magnet:?xt=urn:btih:ABC and example.com/path"
        val links = LinkExtractor.findAll(text, listOf(pattern("magnet:\\S+")))
        assertEquals(listOf("magnet:?xt=urn:btih:ABC", "example.com/path"), links.map { it.url })
    }

    @Test fun zeroWidthAndDisabledMatchesDoNotProduceTargets() {
        val links = LinkExtractor.findAll("plain text", listOf(pattern("(?=text)"), pattern("plain", enabled = false)))
        assertEquals(emptyList<LinkTextMatch>(), links)
    }

    @Test fun longestOverlappingRangeWinsWithoutHidingOtherLinks() {
        val links = selectLinkTextMatches(emptyList(), listOf(
            LinkTextMatch("short", 2, 6), LinkTextMatch("full", 0, 10), LinkTextMatch("second", 20, 26)
        ))
        assertEquals(listOf("full", "second"), links.map { it.url })
    }
}
