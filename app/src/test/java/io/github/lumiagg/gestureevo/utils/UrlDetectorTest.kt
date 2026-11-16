package io.github.lumiagg.gestureevo.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector is shared by the link radar, the text toolbar and search, so a bare file name
 * must not become a target: "app.apk" has the same shape as a domain.
 */
class UrlDetectorTest {

    @Test fun bareFileNamesAreNotLinks() {
        listOf(
            "app.apk", "app-debug.apk", "update.zip", "backup-v2.zip", "data.rar", "install.exe",
            "report.pdf", "screenshot.png", "archive.tar.gz", "rec0001.mp4", "example.zip",
        ).forEach { name ->
            assertFalse(name, UrlDetector.hasUrl(name))
            assertNull(name, UrlDetector.normalizeForOpening(name))
            assertEquals(name, emptyList<String>(), UrlDetector.findUrls(name))
        }
    }

    @Test fun fileNameInsideSentenceIsIgnoredWhileOtherLinksRemain() {
        val text = "已保存 app-debug.apk 到下载目录，官网 example.com"
        assertEquals(listOf("example.com"), UrlDetector.findUrls(text))
    }

    @Test fun explicitSchemeAndPathKeepWorking() {
        val download = "https://example.com/download/app.apk"
        assertEquals(download, UrlDetector.normalizeForOpening(download))
        assertEquals(listOf(download), UrlDetector.findUrls(download))
        assertEquals("example.com/files/app.apk", UrlDetector.findUrls("example.com/files/app.apk").single())
    }

    @Test fun ordinaryBareDomainsStillDetected() {
        listOf("example.com", "www.example.com", "example.com/path?q=1").forEach { text ->
            assertTrue(text, UrlDetector.hasUrl(text))
        }
    }

    @Test fun extensionLookingHostsRemainReachableThroughExplicitForms() {
        // The file-name heuristic only drops bare tokens; a path, a scheme or `www.` disambiguates.
        assertEquals("https://example.zip/path", UrlDetector.normalizeForOpening("example.zip/path"))
        assertEquals("https://example.zip", UrlDetector.normalizeForOpening("https://example.zip"))
        assertEquals("https://www.example.zip", UrlDetector.normalizeForOpening("www.example.zip"))
    }

    @Test fun barePackageNamesAreNotLinks() {
        listOf(
            "cc.csxcl.fkcoloros", "com.coloros.colordirectservice", "io.github.lumiagg.gestureevo",
            "pm uninstall cc.csxcl.fkcoloros",
        ).forEach { text ->
            assertFalse(text, UrlDetector.hasUrl(text))
            assertEquals(text, emptyList<String>(), UrlDetector.findUrls(text))
        }
    }

    @Test fun knownTopLevelDomainsStayLinks() {
        listOf("example.com", "example.cn", "github.io", "example.moe", "blog.example.com", "example.app")
            .forEach { text -> assertTrue(text, UrlDetector.hasUrl(text)) }
    }

    @Test fun unknownTopLevelDomainIsDroppedButPathKeepsWorking() {
        assertFalse(UrlDetector.hasUrl("example.unknown"))
        assertTrue(UrlDetector.hasUrl("example.unknown/page"))
    }

    @Test fun twoLetterCodeFileEndingsAreNotLinks() {
        listOf("app.js", "main.ts", "build.go", "index.cs").forEach { name ->
            assertFalse(name, UrlDetector.hasUrl(name))
        }
    }
}
