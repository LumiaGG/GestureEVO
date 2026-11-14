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
}
