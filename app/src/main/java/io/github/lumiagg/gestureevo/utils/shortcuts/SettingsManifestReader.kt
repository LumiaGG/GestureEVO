package io.github.lumiagg.gestureevo.utils.shortcuts

import org.xmlpull.v1.XmlPullParser

internal const val ANDROID_XML_NAMESPACE = "http://schemas.android.com/apk/res/android"

internal data class SettingsActivityEntry(
    val className: String,
    val label: String,
    val actions: List<String>,
) {
    // The original settings importer selected the last matching action in manifest order.
    val launchAction: String get() = actions.last()
}

/** Reads only the activity/filter information used by settings search. */
internal fun readSettingsActivities(
    parser: XmlPullParser,
    packageName: String,
    resolveLabel: (XmlPullParser) -> String,
): List<SettingsActivityEntry> {
    val result = mutableListOf<SettingsActivityEntry>()
    var activityDepth = -1
    var className = ""
    var label = ""
    val actions = mutableListOf<String>()
    var filterDepth = -1
    var filterNeedsData = false
    val filterActions = mutableListOf<String>()

    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when {
                parser.depth == 1 -> {
                    require(parser.name == "manifest" && parser.getAttributeValue(null, "package") == packageName) {
                        "Manifest does not belong to $packageName"
                    }
                    require(parser.getAttributeValue(null, "split").isNullOrBlank()) {
                        "Expected the base APK manifest"
                    }
                }
                parser.depth == 3 && (parser.name == "activity" || parser.name == "activity-alias") -> {
                    activityDepth = parser.depth
                    val declaredName = parser.getAttributeValue(ANDROID_XML_NAMESPACE, "name").orEmpty()
                    className = when {
                        declaredName.isBlank() -> ""
                        declaredName.startsWith('.') -> packageName + declaredName
                        '.' !in declaredName -> "$packageName.$declaredName"
                        else -> declaredName
                    }
                    label = resolveLabel(parser)
                    actions.clear()
                }
                activityDepth > 0 && parser.depth == activityDepth + 1 && parser.name == "intent-filter" -> {
                    filterDepth = parser.depth
                    filterNeedsData = false
                    filterActions.clear()
                }
                filterDepth > 0 && parser.depth == filterDepth + 1 && parser.name == "action" -> {
                    parser.getAttributeValue(ANDROID_XML_NAMESPACE, "name")
                        ?.takeIf { it.startsWith("android.settings.") }
                        ?.let(filterActions::add)
                }
                filterDepth > 0 && parser.depth == filterDepth + 1 && parser.name == "data" -> {
                    filterNeedsData = true
                }
            }
            XmlPullParser.END_TAG -> when {
                parser.depth == filterDepth -> {
                    if (!filterNeedsData) actions.addAll(filterActions)
                    filterDepth = -1
                }
                parser.depth == activityDepth -> {
                    if (className.isNotBlank() && label.isNotBlank() && actions.isNotEmpty()) {
                        result += SettingsActivityEntry(className, label, actions.toList())
                    }
                    activityDepth = -1
                }
            }
        }
        parser.next()
    }
    return result
}
