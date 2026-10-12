package io.github.lumiagg.gestureevo.utils.shortcuts

import org.kxml2.io.KXmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class SettingsManifestReaderTest {
    private fun read(body: String): List<SettingsActivityEntry> {
        val parser = KXmlParser().apply {
            setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader("""
                <manifest xmlns:android="$ANDROID_XML_NAMESPACE" package="com.android.settings">
                    <application>$body</application>
                </manifest>
            """.trimIndent()))
        }
        return readSettingsActivities(parser, "com.android.settings") {
            it.getAttributeValue(ANDROID_XML_NAMESPACE, "label").orEmpty()
        }
    }

    @Test fun readsLiteralLabelsAndNormalizesActivityAndAliasNames() {
        val entries = read("""
            <activity android:name=".Wifi" android:label=" Wi-Fi ">
                <intent-filter><action android:name="android.settings.WIFI_SETTINGS"/></intent-filter>
            </activity>
            <activity-alias android:name="Bluetooth" android:label="蓝牙">
                <intent-filter><action android:name="android.settings.BLUETOOTH_SETTINGS"/></intent-filter>
            </activity-alias>
        """)
        assertEquals(listOf("com.android.settings.Wifi", "com.android.settings.Bluetooth"), entries.map { it.className })
        assertEquals(listOf(" Wi-Fi ", "蓝牙"), entries.map { it.label })
    }

    @Test fun filtersRequiringDataDoNotSuppressIndependentSettingsFilters() {
        val entries = read("""
            <activity android:name="settings.General" android:label="设置">
                <intent-filter>
                    <action android:name="android.settings.APPLICATION_DETAILS_SETTINGS"/>
                    <data android:scheme="package"/>
                </intent-filter>
                <intent-filter>
                    <action android:name="android.settings.SETTINGS"/>
                    <action android:name="android.settings.SETTINGS"/>
                </intent-filter>
            </activity>
        """)
        assertEquals(listOf("android.settings.SETTINGS", "android.settings.SETTINGS"), entries.single().actions)
        assertEquals("settings.General", entries.single().className)
    }

    @Test fun rejectsDataOnlyActivitiesAndDoesNotLeakFiltersToNextActivity() {
        val entries = read("""
            <activity android:name=".Details" android:label="详情">
                <intent-filter><data android:scheme="package"/>
                    <action android:name="android.settings.APPLICATION_DETAILS_SETTINGS"/>
                </intent-filter>
            </activity>
            <activity android:name=".Empty" android:label="空白"/>
            <activity android:name=".Valid" android:label="有效">
                <intent-filter><action android:name="android.settings.SETTINGS"/></intent-filter>
            </activity>
        """)
        assertEquals(listOf("有效"), entries.map { it.label })
    }

    @Test fun ignoresServiceActionsMissingLabelsAndNonAndroidAttributes() {
        assertTrue(read("""
            <service android:name=".Service" android:label="服务">
                <intent-filter><action android:name="android.settings.SETTINGS"/></intent-filter>
            </service>
            <activity android:name=".MissingLabel">
                <intent-filter><action android:name="android.settings.SETTINGS"/></intent-filter>
            </activity>
            <activity android:name=".Decoy" android:label="错误命名空间">
                <intent-filter><action name="android.settings.SETTINGS"/></intent-filter>
            </activity>
        """).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsManifestFromAnotherPackage() {
        val parser = KXmlParser().apply { setInput(StringReader("<manifest package=\"android\"/>")) }
        readSettingsActivities(parser, "com.android.settings") { "" }
    }

    @Test(expected = org.xmlpull.v1.XmlPullParserException::class)
    fun rejectsTruncatedManifestInsteadOfReturningPartialEntries() {
        read("<activity android:name=\".Wifi\" android:label=\"Wi-Fi\">")
    }

    @Test(expected = IllegalArgumentException::class)
    fun splitManifestCannotReplaceTheBaseActivityList() {
        val parser = KXmlParser().apply {
            setInput(StringReader("<manifest package=\"com.android.settings\" split=\"config.zh\"/>"))
        }
        readSettingsActivities(parser, "com.android.settings") { "" }
    }

    @Test fun launchActionMatchesThePreviousImporterIncludingRepeatedActions() {
        val entry = read("""
            <activity android:name=".Wifi" android:label="Wi-Fi">
                <intent-filter>
                    <action android:name="android.settings.WIFI_SETTINGS"/>
                    <action android:name="android.settings.WIRELESS_SETTINGS"/>
                </intent-filter>
                <intent-filter><action android:name="android.settings.WIFI_SETTINGS"/></intent-filter>
            </activity>
        """).single()
        assertEquals("android.settings.WIFI_SETTINGS", entry.launchAction)
    }
}
