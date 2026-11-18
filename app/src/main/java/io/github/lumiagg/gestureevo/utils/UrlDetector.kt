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

    /**
     * Bare `name.ext` tokens whose extension is a file type, not a host. A few of these are also
     * delegated TLDs (.zip, .log), but a bare occurrence is a file name far more often, and a
     * real URL can still be opened by spelling out its scheme or adding a path.
     */
    private val fileExtensionLabels = setOf(
        // packages and archives
        "apk", "apks", "xapk", "apkm", "aab", "ipa", "obb", "zip", "zipx", "rar", "7z", "tar",
        "tgz", "gz", "bz2", "xz", "lz", "lzma", "zst", "cab", "iso", "jar", "war", "sit", "sitx",
        // installers and binaries
        "exe", "msi", "dmg", "pkg", "deb", "rpm", "elf", "dll", "dylib", "sys", "bin",
        // documents
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt",
        "csv", "tsv", "epub", "mobi", "azw3",
        // images
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff", "ico", "heic", "heif", "svg",
        "psd", "raw",
        // media
        "mp3", "m4a", "m4v", "wav", "flac", "aac", "ogg", "opus", "wma", "avi", "mkv", "mov",
        "wmv", "flv", "webm", "3gp",
        // configuration, data and logs
        "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "log", "bak", "tmp", "dat",
        "db", "sqlite", "properties", "torrent",
        // source code with two-letter endings that are not delegated country codes
        "js", "ts", "go", "cs", "rb", "vb", "ko",
    )

    /**
     * Generic top-level domains a bare token may end in to count as a host. Curated rather than
     * complete on purpose: it exists to keep package names like `cc.csxcl.fkcoloros` out of the
     * link radar, and anything missing stays reachable through an explicit scheme or a path.
     */
    private val topLevelDomainLabels = setOf(
        // classic generics
        "com", "org", "net", "edu", "gov", "int", "mil", "info", "biz", "pro", "name", "mobi",
        "asia", "tel", "cat", "aero", "coop", "jobs", "travel", "museum",
        // newer generics people actually meet
        "xyz", "top", "site", "online", "club", "vip", "icu", "shop", "store", "tech", "cloud",
        "fun", "space", "website", "live", "life", "world", "today", "news", "link", "zone",
        "team", "group", "company", "digital", "network", "systems", "solutions", "agency",
        "studio", "works", "guru", "tips", "run", "fit", "ltd", "inc", "llc", "plus", "one",
        "love", "best", "cool", "city", "global", "games", "game", "host", "video", "music",
        "photo", "photos", "pics", "email", "market", "finance", "fund", "money", "press",
        "ink", "red", "blue", "pink", "mba", "moe", "fans", "cafe", "pub", "bar", "pet", "auto",
        "cars", "bike", "care", "health", "legal", "wtf", "lol", "rest", "rip", "day", "app",
        "dev", "page", "how", "art", "design", "blog", "wiki", "deals", "gift", "gifts", "tools",
        "wang", "ren", "loan", "cash", "gold", "sale", "win", "bet",
    )

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

            isBareNonHost(normalized) -> null

            else -> {
                "https://$normalized".takeIf { isLikelyWebUrl(it) }
            }
        }
    }

    /**
     * A scheme-less token without a path is only a host when its last label could be a TLD.
     * Judging the whole token keeps `example.com/a.zip` a URL, while `app.apk` (file name) and
     * `cc.csxcl.fkcoloros` (package name) drop out of the bare-domain path.
     */
    private fun isBareNonHost(normalized: String): Boolean {
        if (normalized.contains('/') || normalized.contains('?') || normalized.contains('#')) return false
        // An explicit `www.` is a deliberate host, so `.zip` and friends stay usable as TLDs.
        if (normalized.startsWith("www.", ignoreCase = true)) return false
        val label = normalized.substringAfterLast('.', "").substringBefore(':').lowercase()
        if (label.isEmpty()) return false
        if (label in fileExtensionLabels) return true
        // Two-letter labels are delegated country codes, so they stay reachable; longer ones must
        // be a known generic TLD. Unknown TLDs can still be opened by spelling out the scheme.
        return label.length != 2 && label !in topLevelDomainLabels
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
