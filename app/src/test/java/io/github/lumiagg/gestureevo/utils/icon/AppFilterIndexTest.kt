package io.github.lumiagg.gestureevo.utils.icon

import org.kxml2.io.KXmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class AppFilterIndexTest {
    private fun index(body: String): AppFilterIndex {
        val parser = KXmlParser().apply { setInput(StringReader("<resources>$body</resources>")) }
        return AppFilterIndex.read(parser)
    }

    @Test fun resolvesFullAndShortComponentNames() {
        val index = index("""
            <item drawable="mail" component="ComponentInfo{com.example.mail/.Main}"/>
            <item component="com.example.browser/com.example.browser.Start" drawable="browser"/>
        """)
        assertEquals("mail", index.drawableFor("com.example.mail", "com.example.mail.Main"))
        assertEquals("browser", index.drawableFor("com.example.browser", ".Start"))
    }

    @Test fun frozenAppFallbackMatchesExactPackageRatherThanSubstring() {
        val index = index("""
            <item component="com.example.mail.extra/.Main" drawable="wrong"/>
            <item component="com.example.mail/.Main" drawable="correct"/>
        """)
        assertEquals("correct", index.drawableFor("com.example.mail", null))
        assertNull(index.drawableFor("com.example", null))
    }

    @Test fun firstValidMappingWinsAndMalformedItemsAreIgnored() {
        val index = index("""
            <item component="ComponentInfo{com.mail/.Main}"/>
            <item component="com.mail/.Main" drawable="first"/>
            <item component="com.mail/com.mail.Main" drawable="second"/>
            <item component="no_separator" drawable="broken"/>
            <item drawable="missing_component"/>
        """)
        assertEquals("first", index.drawableFor("com.mail", ".Main"))
        assertNull(index.drawableFor("no_separator", null))
        assertNull(index.drawableFor("com.mail", ".Other"))
    }

    @Test fun unusedCompositionTagsDoNotAffectMappedIcons() {
        val index = index("""
            <iconback img1="background"/><scale factor="not_a_number"/>
            <iconmask img1="mask"/><iconupon img1="foreground"/>
            <item component="com.mail/.Main" drawable="mail"/>
        """)
        assertEquals("mail", index.drawableFor("com.mail", null))
    }

    @Test fun emptyPackHasNoOverride() {
        assertNull(index("").drawableFor("com.mail", null))
    }
}
