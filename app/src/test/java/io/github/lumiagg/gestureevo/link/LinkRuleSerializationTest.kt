package io.github.lumiagg.gestureevo.link

import io.github.lumiagg.gestureevo.utils.MoshiUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule table is persisted as JSON, so a serialisation regression would silently disable
 * every rule. These round trips guard that contract.
 */
class LinkRuleSerializationTest {

    @Test
    fun singleRuleSurvivesRoundTrip() {
        val rule = LinkDispatchRule(
            name = "bilibili",
            pattern = "bilibili.com",
            targetPackage = "tv.danmaku.bili",
            matchType = LinkMatchType.CONTAINS
        )
        val json = MoshiUtils.toJson(rule)
        val restored = MoshiUtils.toModel<LinkDispatchRule>(json)
        assertEquals(rule.pattern, restored?.pattern)
        assertEquals(rule.targetPackage, restored?.targetPackage)
        assertEquals(LinkMatchType.CONTAINS, restored?.matchType)
    }

    @Test
    fun ruleListSurvivesRoundTrip() {
        val rules = listOf(
            LinkDispatchRule(pattern = "a.com", targetPackage = "com.a"),
            LinkDispatchRule(
                pattern = "b\\.com/(\\d+)",
                targetPackage = "com.b",
                matchType = LinkMatchType.REGEX,
                template = "bapp://$1"
            )
        )
        val json = MoshiUtils.toJson(rules)
        val restored = MoshiUtils.toModel<List<LinkDispatchRule>>(json)
        assertEquals(2, restored?.size)
        assertEquals("bapp://$1", restored?.get(1)?.template)
        assertTrue(restored?.get(1)?.resolveStrategy == LinkResolveStrategy.NONE)
    }
}
