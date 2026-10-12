package io.github.lumiagg.gestureevo.utils.icon

import org.xmlpull.v1.XmlPullParser

/** Component mappings from the icon pack's appfilter format; no bitmap synthesis is needed. */
internal class AppFilterIndex private constructor(private val mappings: Map<String, String>) {
    fun drawableFor(packageName: String, activityName: String?): String? {
        if (activityName != null) {
            return mappings[componentKey(packageName, activityName)]
        }
        return mappings.entries.firstOrNull { it.key.substringBefore('/') == packageName }?.value
    }

    companion object {
        val EMPTY = AppFilterIndex(emptyMap())

        fun read(parser: XmlPullParser): AppFilterIndex {
            val entries = linkedMapOf<String, String>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                    val raw = parser.getAttributeValue(null, "component").orEmpty().trim()
                    val component = raw.removePrefix("ComponentInfo{").removeSuffix("}")
                    val packageName = component.substringBefore('/', "")
                    val activity = component.substringAfter('/', "")
                    val drawable = parser.getAttributeValue(null, "drawable").orEmpty().trim()
                    if (packageName.isNotBlank() && activity.isNotBlank() && drawable.isNotBlank()) {
                        entries.putIfAbsent(componentKey(packageName, activity), drawable)
                    }
                }
                parser.next()
            }
            return AppFilterIndex(entries)
        }

        private fun componentKey(packageName: String, activityName: String): String {
            val fullName = if (activityName.startsWith('.')) packageName + activityName else activityName
            return "$packageName/$fullName"
        }
    }
}
