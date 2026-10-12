package io.github.lumiagg.gestureevo.utils

object UrlDetector {
    private val schemeRegex = Regex("(?i)https?\\s*:\\s*//")
    private val wwwRegex = Regex("(?i)www\\s*\\.\\s*")
    private val dotRegex = Regex("([A-Za-z0-9-])\\s*\\.\\s*([A-Za-z0-9-])")
    private val slashRegex = Regex("([A-Za-z0-9\\-._~%/])\\s*/\\s*([A-Za-z0-9\\-._~%/])")
    private val colonPortRegex = Regex("([A-Za-z0-9-])\\s*:\\s*([0-9])")
    private val queryRegex = Regex("([A-Za-z0-9\\-._~%])\\s*([?&#=])\\s*([A-Za-z0-9\\-._~%])")
    private val schemeHostWhitespaceRegex = Regex("(?i)(https?://)\\s+")
    private val protocolRelativeRegex = Regex("^//")
    private val anySchemeRegex = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val urlStartRegex = Regex(
        "(?i)https?\\s*:\\s*//|www\\s*\\.\\s*|(?<![@A-Za-z0-9._-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\s*\\.\\s*)+[a-z]{2,63}(?=\\b|\\s|[:/?#])"
    )
    private val domainRegex = Regex("(?i)^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}$")
    private val ipv4Regex = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")
    private val fullWidthUrlChars = mapOf(
        '：' to ':',
        '／' to '/',
        '．' to '.',
        '。' to '.',
        '？' to '?',
        '＆' to '&',
        '＝' to '=',
        '＃' to '#',
        '％' to '%',
        '＿' to '_',
        '－' to '-',
        '～' to '~',
    )

    fun hasUrl(text: String): Boolean {
        return findFirstUrl(text) != null
    }

    fun findFirstUrl(text: String): String? = findUrls(text, 1).firstOrNull()

    fun findUrls(text: String, max: Int = Int.MAX_VALUE): List<String> {
        if (text.isBlank()) return emptyList()
        val urls = ArrayList<String>(4)
        var index = 0
        while (index < text.length) {
            val match = urlStartRegex.find(text, index) ?: break
            val start = match.range.first
            val extracted = extractSpacedUrl(text, start)
            var url = trimTrailingWhitespace(extracted)
            url = trimTrailingPunctuation(url)
            if (normalizeForOpening(url) != null) {
                urls.add(url)
                if (urls.size >= max) break
            }
            index = (start + extracted.length).coerceAtLeast(start + 1)
        }
        return urls
    }

    fun normalizeForOpening(raw: String): String? {
        val normalized = normalizeUrlLikeText(raw)
        if (normalized.isEmpty()) return null

        return when {
            normalized.startsWith("http://", ignoreCase = true) ||
                normalized.startsWith("https://", ignoreCase = true) -> {
                normalized.takeIf { isLikelyWebUrl(it) }
            }

            protocolRelativeRegex.containsMatchIn(normalized) -> {
                "https:$normalized".takeIf { isLikelyWebUrl(it) }
            }

            anySchemeRegex.containsMatchIn(normalized) -> null

            else -> {
                "https://$normalized".takeIf { isLikelyWebUrl(it) }
            }
        }
    }

    private fun normalizeUrlLikeText(text: String): String {
        var normalized = text.trim()
            .map { char -> fullWidthUrlChars[char] ?: char }
            .joinToString("")
        normalized = trimTrailingPunctuation(normalized)
        normalized = schemeRegex.replace(normalized) { match ->
            if (match.value.contains("https", ignoreCase = true)) "https://" else "http://"
        }
        normalized = schemeHostWhitespaceRegex.replace(normalized, "$1")
        normalized = wwwRegex.replace(normalized, "www.")
        normalized = replaceRepeated(normalized, dotRegex, "$1.$2")
        normalized = replaceRepeated(normalized, slashRegex, "$1/$2")
        normalized = replaceRepeated(normalized, colonPortRegex, "$1:$2")
        normalized = replaceRepeated(normalized, queryRegex, "$1$2$3")
        return normalized
    }

    private fun replaceRepeated(text: String, regex: Regex, replacement: String): String {
        var current = text
        while (true) {
            val next = regex.replace(current, replacement)
            if (next == current) return current
            current = next
        }
    }

    private fun isLikelyWebUrl(url: String): Boolean {
        val withoutScheme = when {
            url.startsWith("http://", ignoreCase = true) -> url.substringAfter("://")
            url.startsWith("https://", ignoreCase = true) -> url.substringAfter("://")
            url.startsWith("//") -> url.removePrefix("//")
            else -> url
        }
        val authority = withoutScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
        val host = authority.substringBefore(':').trim().trim('.')
        if (host.isEmpty() || host.contains(' ')) return false
        if (domainRegex.matches(host)) return true
        return ipv4Regex.matches(host) && host.split('.').all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun trimTrailingPunctuation(url: String): String {
        var end = url.length
        while (end > 0) {
            when (url[end - 1]) {
                '.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'', '。', '，', '；', '：', '！', '？' -> end--
                else -> return if (end == url.length) url else url.substring(0, end)
            }
        }
        return ""
    }

    private fun isUrlChar(ch: Char): Boolean {
        return ch.isLetterOrDigit() || ch in "-._~%:/?#[]@!$&'()*+,;="
    }

    private fun isUrlDelimiter(ch: Char): Boolean {
        return !ch.isLetterOrDigit() && ch in "-._~%:/?#[]@!$&'()*+,;="
    }

    private fun previousNonWhitespace(text: String, start: Int): Int {
        var i = start
        while (i >= 0) {
            if (!text[i].isWhitespace()) return i
            i--
        }
        return -1
    }

    private fun nextNonWhitespace(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            if (!text[i].isWhitespace()) return i
            i++
        }
        return -1
    }

    private fun extractSpacedUrl(text: String, start: Int): String {
        val sb = StringBuilder()
        var i = start
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n' || ch == '\r' || ch == '\t') break
            if (ch.isWhitespace()) {
                val nextIndex = nextNonWhitespace(text, i + 1)
                if (nextIndex == -1) break
                val nextChar = text[nextIndex]
                val previousIndex = previousNonWhitespace(text, i - 1)
                val previousChar = previousIndex.takeIf { it >= start }?.let { text[it] }
                if (
                    previousChar != null &&
                    isUrlChar(nextChar) &&
                    (isUrlDelimiter(previousChar) || isUrlDelimiter(nextChar))
                ) {
                    sb.append(ch)
                    i++
                    continue
                }
                break
            }
            if (isUrlChar(ch)) {
                sb.append(ch)
                i++
                continue
            }
            break
        }
        return sb.toString()
    }

    private fun trimTrailingWhitespace(url: String): String {
        var end = url.length
        while (end > 0 && url[end - 1].isWhitespace()) end--
        return if (end == url.length) url else url.substring(0, end)
    }
}
