package io.github.lumiagg.gestureevo.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LinkRoutingRegressionTest {
    private val shortRule = LinkDispatchRule(
        pattern = "short.test", resolveShortLink = true,
        resolveStrategy = LinkResolveStrategy.REDISPATCH
    )

    @Test
    fun headFollowsRelativeLocationAndStopsAtSuccessfulResponse() = runBlocking {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val worker = Executors.newSingleThreadExecutor()
        val requests = worker.submit<List<String>> {
            List(2) { index ->
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                    val status = if (index == 0) "302 Found\r\nLocation: /final" else "200 OK"
                    socket.getOutputStream().write(
                        "HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                    request
                }
            }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            assertEquals("$base/final", LinkDispatcher.followRedirects("$base/short", LinkDispatcher::headRedirect))
            assertEquals(listOf("HEAD /short HTTP/1.1", "HEAD /final HTTP/1.1"), requests.get(5, TimeUnit.SECONDS))
        } finally {
            server.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun redirectLoopsFailuresAndOverlongChainsAreRejected() = runBlocking {
        assertNull(LinkDispatcher.followRedirects("a") { if (it == "a") "b" else "a" })
        assertNull(LinkDispatcher.followRedirects("a") { null })
        assertNull(LinkDispatcher.followRedirects("0") { (it.toInt() + 1).toString() })
        assertNull(LinkDispatcher.followRedirects("a") { it })
        assertEquals("5", LinkDispatcher.followRedirects("0") {
            if (it == "5") it else (it.toInt() + 1).toString()
        })
    }

    @Test
    fun redispatchUsesDestinationRuleCapturesWithoutSourceText() = runBlocking {
        val destination = LinkDispatchRule(
            pattern = "example.com", targetPackage = "com.example",
            extractPattern = "item/(\\d+)", template = "app://item/{1}"
        )
        val result = LinkDispatcher.routeWithRules(
            "https://short.test/a", listOf(shortRule, destination)
        ) { "https://example.com/item/42" } as LinkDispatchResult.Routed
        assertEquals(destination.id, result.rule.id)
        assertEquals("app://item/42", result.url)
    }

    @Test
    fun intentExtraKeepsUrlDelimitersInsideTheValue() = runBlocking {
        val url = "https://example.com/part%20one?a=1;part=two+three#section"
        val rule = LinkDispatchRule(
            pattern = "example.com",
            targetPackage = "com.example",
            template = "intent://#Intent;component=com.example/.Reader;S.rawUrl={url};end",
        )
        val result = LinkDispatcher.routeWithRules(url, listOf(rule)) as LinkDispatchResult.Routed
        assertEquals(url, result.fallbackUrl)
        assertEquals(
            "intent://#Intent;component=com.example/.Reader;S.rawUrl=" +
                "https%3A%2F%2Fexample.com%2Fpart%2520one%3Fa%3D1%3Bpart%3Dtwo%2Bthree%23section;end",
            result.url,
        )
    }

    @Test
    fun redispatchRechecksExemptionsAndPreservesResolvedUrl() = runBlocking {
        val result = LinkDispatcher.routeWithRules(
            "https://short.test/a",
            listOf(shortRule, LinkDispatchRule(pattern = "bank.test", targetPackage = "com.browser")),
            listOf(LinkExemptDomain(domain = "bank.test", sourcePackage = "com.bank")),
            sourcePackage = "com.bank"
        ) { "https://bank.test/pay" } as LinkDispatchResult.Exempted
        assertEquals("https://bank.test/pay", result.url)
    }

    @Test
    fun unmatchedResolvedUrlOpensThroughSystemHandler() = runBlocking {
        val result = LinkDispatcher.routeWithRules("https://short.test/a", listOf(shortRule)) {
            "https://unknown.test/final"
        }
        assertEquals(LinkDispatchResult.SystemDefault("https://unknown.test/final"), result)
    }

    @Test
    fun failedPureResolverFallsBackWithoutRecordingARuleHit() = runBlocking {
        val result = LinkDispatcher.routeWithRules("https://short.test/a", listOf(shortRule)) { null }
        assertEquals(LinkDispatchResult.SystemDefault("https://short.test/a"), result)
    }

    @Test
    fun cyclicRedispatchTerminates() = runBlocking {
        val result = LinkDispatcher.routeWithRules("https://short.test/a", listOf(shortRule)) {
            if (it.endsWith("/a")) "https://short.test/b" else "https://short.test/a"
        }
        assertEquals(LinkDispatchResult.Failed, result)
    }

    @Test(expected = CancellationException::class)
    fun routingPreservesCancellation(): Unit = runBlocking {
        LinkDispatcher.routeWithRules("https://short.test/a", listOf(shortRule)) {
            throw CancellationException("session ended")
        }
        Unit
    }

    @Test
    fun mixedPlaceholderFamiliesDoNotRewriteInsertedValues() {
        val rule = LinkDispatchRule(
            pattern = "item/(\\d+)", matchType = LinkMatchType.REGEX,
            template = "app://item/\$1?original={url}&extra={1}"
        )
        assertEquals(
            "app://item/42?original=https://example.com/item/42?q=\$1&extra=value\$1",
            LinkDispatcher.applyTemplate("https://example.com/item/42?q=\$1", rule, listOf("value\$1"))
        )
    }

    @Test
    fun captureContainingAnotherReferenceIsNotExpandedTwice() {
        val rule = LinkDispatchRule(
            pattern = "(\\\$2)/(value)", matchType = LinkMatchType.REGEX, template = "app://\$1/\$2"
        )
        assertEquals("app://\$2/value", LinkDispatcher.applyTemplate("\$2/value", rule))
    }

    @Test
    fun legacyDomainsPreserveSpecificityAndExistingRules() {
        val existing = LinkDispatchRule(pattern = "kept.test", targetPackage = "com.kept", priority = 5)
        val legacy = listOf(
            LegacyLinkDomain("com.parent", "example.com"),
            LegacyLinkDomain("com.child", "shop.example.com"),
            LegacyLinkDomain("com.old", "kept.test")
        )
        val migrated = LinkRuleRepository.mergeLegacyDomains(listOf(existing), legacy)
        assertEquals(3, migrated.size)
        assertEquals(existing, migrated.first())
        assertEquals("com.child", LinkDispatcher.findRule("https://shop.example.com/x", migrated)?.targetPackage)
        assertEquals(migrated, LinkRuleRepository.mergeLegacyDomains(migrated, legacy))
    }
}
