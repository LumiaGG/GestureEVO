package io.github.lumiagg.gestureevo.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Import must be additive and idempotent: the same backup applied twice adds nothing. */
class LinkConfigCodecTest {

    private fun rule(pattern: String, pkg: String = "com.example") =
        LinkDispatchRule(pattern = pattern, targetPackage = pkg)

    @Test
    fun exportThenDecodeRoundTrips() {
        val rules = listOf(rule("bilibili.com"), rule("zhihu.com", "com.zhihu.android"))
        val exempts = listOf(LinkExemptDomain(domain = "bank.com"))
        val json = LinkConfigCodec.export(rules, exempts, emptyList())
        val decoded = LinkConfigCodec.decode(json)
        assertEquals(2, decoded?.rules?.size)
        assertEquals("bank.com", decoded?.exempts?.first()?.domain)
    }

    @Test
    fun importAddsOnlyNewEntries() {
        val existing = listOf(rule("bilibili.com"))
        val incoming = LinkConfigBackup(rules = listOf(rule("bilibili.com"), rule("zhihu.com")))
        val (merged, result) = LinkConfigCodec.merge(existing, emptyList(), emptyList(), incoming)
        assertEquals(1, result.rulesAdded)
        assertEquals(2, merged.rules.size)
    }

    @Test
    fun importingTwiceIsIdempotent() {
        val incoming = LinkConfigBackup(rules = listOf(rule("a.com"), rule("b.com")))
        val (first, firstResult) =
            LinkConfigCodec.merge(emptyList(), emptyList(), emptyList(), incoming)
        assertEquals(2, firstResult.rulesAdded)

        val (second, secondResult) =
            LinkConfigCodec.merge(first.rules, emptyList(), emptyList(), incoming)
        assertEquals(0, secondResult.rulesAdded)
        assertEquals(2, second.rules.size)
    }

    @Test
    fun blankPatternsAreDropped() {
        val incoming = LinkConfigBackup(rules = listOf(rule(""), rule("ok.com")))
        val (merged, result) = LinkConfigCodec.merge(emptyList(), emptyList(), emptyList(), incoming)
        assertEquals(1, result.rulesAdded)
        assertEquals("ok.com", merged.rules.single().pattern)
    }

    @Test
    fun invalidJsonDecodesToNull() {
        assertEquals(null, LinkConfigCodec.decode("{not json"))
        assertEquals(null, LinkConfigCodec.decode(""))
    }

    @Test
    fun exemptsAreDeduplicatedByDomain() {
        val existing = listOf(LinkExemptDomain(domain = "bank.com"))
        val incoming = LinkConfigBackup(
            exempts = listOf(
                LinkExemptDomain(domain = "bank.com"),
                LinkExemptDomain(domain = "gov.cn")
            )
        )
        val (merged, result) = LinkConfigCodec.merge(emptyList(), existing, emptyList(), incoming)
        assertEquals(1, result.exemptsAdded)
        assertEquals(2, merged.exempts.size)
    }

    @Test
    fun importedEntriesCannotReuseExistingIds() {
        val existing = rule("a.com").copy(id = "same")
        val incoming = LinkConfigBackup(rules = listOf(rule("b.com").copy(id = "same")))
        val (merged, _) = LinkConfigCodec.merge(listOf(existing), emptyList(), emptyList(), incoming)
        assertEquals(2, merged.rules.map { it.id }.distinct().size)
        assertEquals(existing, merged.rules.first())
    }

    @Test
    fun exemptionsRetainDifferentSourceApplications() {
        val existing = listOf(LinkExemptDomain(domain = "bank.com", sourcePackage = "com.a"))
        val incoming = LinkConfigBackup(exempts = listOf(
            LinkExemptDomain(domain = "BANK.COM", sourcePackage = "com.a"),
            LinkExemptDomain(domain = "bank.com", sourcePackage = "com.b")
        ))
        val (merged, result) = LinkConfigCodec.merge(emptyList(), existing, emptyList(), incoming)
        assertEquals(1, result.exemptsAdded)
        assertEquals(setOf("com.a", "com.b"), merged.exempts.map { it.sourcePackage }.toSet())
    }

    @Test
    fun customExtractsWithConflictingIdsRemainIdempotent() {
        val existing = LinkExtractPattern(id = "same", pattern = "old")
        val incoming = LinkConfigBackup(extracts = listOf(LinkExtractPattern(id = "same", pattern = "new")))
        val (first, result) = LinkConfigCodec.merge(emptyList(), emptyList(), listOf(existing), incoming)
        assertEquals(1, result.extractsAdded)
        assertEquals(2, first.extracts.map { it.id }.distinct().size)
        val (second, repeated) = LinkConfigCodec.merge(emptyList(), emptyList(), first.extracts, incoming)
        assertEquals(0, repeated.extractsAdded)
        assertEquals(first.extracts, second.extracts)
    }
}
