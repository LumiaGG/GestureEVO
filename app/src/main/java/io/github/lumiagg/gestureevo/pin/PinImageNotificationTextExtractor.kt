package io.github.lumiagg.gestureevo.pin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import io.github.lumiagg.gestureevo.inspire.ScreenContentNode
import io.github.lumiagg.gestureevo.inspire.SmartSegmenter
import io.github.lumiagg.gestureevo.utils.MlKitOcr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PinNotificationText(
    val title: String?,
    val content: String?
)

object PinImageNotificationTextExtractor {
    suspend fun extract(context: Context, bitmap: Bitmap): PinNotificationText? {
        return extractInternal(context.applicationContext, bitmap)
    }

    suspend fun extract(bitmap: Bitmap): PinNotificationText? {
        return extractInternal(null, bitmap)
    }

    private suspend fun extractInternal(context: Context?, bitmap: Bitmap): PinNotificationText? {
        if (bitmap.width < MIN_OCR_SIZE || bitmap.height < MIN_OCR_SIZE) return null
        val nodes = runCatching { MlKitOcr.detect(bitmap) }
            .onFailure { Log.w(TAG, "OCR failed for pin image notification", it) }
            .getOrDefault(arrayListOf())
        return PinImageTopicPredictionEngine.predict(context, nodes, bitmap.width, bitmap.height)
    }

    private const val TAG = "PinImageNotificationText"
    private const val MIN_OCR_SIZE = 32
}

private object PinImageTopicPredictionEngine {
    fun predict(
        appContext: Context?,
        nodes: List<ScreenContentNode>,
        imageWidth: Int,
        imageHeight: Int
    ): PinNotificationText? {
        val rawLines = nodes.asSequence()
            .mapNotNull { node -> OcrLine.from(node) }
            .filter { it.text.isMeaningfulText() }
            .sortedWith(compareBy<OcrLine>({ it.bound.top }, { it.bound.left }))
            .toList()
        val lines = mergeNearbyLines(rawLines)
        if (lines.isEmpty()) return null

        val context = PredictionContext(
            lines = lines,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            medianHeight = medianLineHeight(lines),
            medianTop = medianLineTop(lines),
            appContext = appContext
        )
        val lifeScene = detectLifeScene(context)
        val titleCandidate = chooseTitleCandidate(context)
        val title = lifeScene?.title
            ?: titleCandidate?.text?.limitNotificationText(TITLE_MAX_LENGTH)
        val content = lifeScene?.content
            ?: predictKeywords(context, titleCandidate?.text)
            ?: predictSummary(context, titleCandidate?.text)

        if (title.isNullOrBlank() && content.isNullOrBlank()) return null
        return PinNotificationText(title, content)
    }

    private fun mergeNearbyLines(lines: List<OcrLine>): List<OcrLine> {
        if (lines.size <= 1) return lines
        val medianHeight = medianLineHeight(lines)
        val rows = mutableListOf<MutableList<OcrLine>>()
        lines.forEach { line ->
            val row = rows.firstOrNull { existingRow ->
                val rowBounds = existingRow.unionBounds()
                abs(rowBounds.centerY() - line.bound.centerY()) <= max(
                    medianHeight,
                    min(rowBounds.height(), line.bound.height())
                ) * 0.55f
            }
            if (row == null) {
                rows.add(mutableListOf(line))
            } else {
                row.add(line)
            }
        }
        return rows.map { row ->
            row.sortedBy { it.bound.left }.reduce { merged, line -> merged.merge(line) }
        }.sortedWith(compareBy<OcrLine>({ it.bound.top }, { it.bound.left }))
    }

    private fun chooseTitleCandidate(context: PredictionContext): OcrLine? {
        return context.lines.asSequence()
            .map { line -> line to scoreTitleCandidate(line, context) }
            .filter { (_, score) -> score >= TITLE_MIN_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun detectLifeScene(context: PredictionContext): LifeScenePrediction? {
        return listOfNotNull(
            detectPickupScene(context),
            detectMealScene(context),
            detectDateScene(context),
            detectTicketScene(context),
            detectShortCodeFallbackScene(context)
        ).maxByOrNull { it.score }
    }

    private fun detectPickupScene(context: PredictionContext): LifeScenePrediction? {
        if (!context.containsAny(PICKUP_CONTEXT_KEYWORDS)) return null
        val code = findCodeNear(context, PICKUP_CODE_LABELS, PICKUP_CONTEXT_KEYWORDS) ?: return null
        val date = findDateTime(context)
        val location = findBestLine(
            context,
            PICKUP_LOCATION_KEYWORDS,
            excludedTexts = setOf(code.sourceText, date?.sourceText).filterNotNull().toSet()
        )
        val content = listOfNotNull(date?.value, location?.text?.limitNotificationText(SCENE_PART_MAX_LENGTH))
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
            ?.limitNotificationText(CONTENT_MAX_LENGTH)
        return LifeScenePrediction(
            title = "取件码 ${code.value}".limitNotificationText(TITLE_MAX_LENGTH),
            content = content,
            score = 14f + code.score + if (location != null) 1.5f else 0f
        )
    }

    private fun detectMealScene(context: PredictionContext): LifeScenePrediction? {
        if (!context.containsAny(MEAL_CONTEXT_KEYWORDS)) return null
        val code = findCodeNear(context, MEAL_CODE_LABELS, MEAL_CONTEXT_KEYWORDS) ?: return null
        val date = findDateTime(context)
        val merchant = findBestLine(
            context,
            MEAL_MERCHANT_KEYWORDS,
            excludedTexts = setOf(code.sourceText, date?.sourceText).filterNotNull().toSet()
        )
        val titlePrefix = if (code.label.contains("餐号")) "餐号" else "取餐码"
        val content = listOfNotNull(merchant?.text?.limitNotificationText(SCENE_PART_MAX_LENGTH), date?.value)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
            ?.limitNotificationText(CONTENT_MAX_LENGTH)
        return LifeScenePrediction(
            title = "$titlePrefix ${code.value}".limitNotificationText(TITLE_MAX_LENGTH),
            content = content,
            score = 13f + code.score + if (merchant != null) 1.2f else 0f
        )
    }

    private fun detectShortCodeFallbackScene(context: PredictionContext): LifeScenePrediction? {
        val candidate = findProminentShortCode(context) ?: return null
        if (!context.hasFallbackCodeHint() && !candidate.isDominantNumericCode) return null
        if (candidate.score < SHORT_CODE_FALLBACK_MIN_SCORE) return null
        val nearby = findBestLine(
            context,
            SHORT_CODE_CONTEXT_HINTS,
            excludedTexts = setOf(candidate.sourceText)
        )
        val titlePrefix = inferShortCodeTitlePrefix(context, nearby?.text)
        if (titlePrefix == GENERIC_CODE_TITLE &&
            !candidate.value.isPickupCodeFormat() &&
            !candidate.sourceText.hasFallbackCodeHint() &&
            !candidate.isDominantNumericCode
        ) {
            return null
        }
        val content = nearby?.text
            ?.takeIf { !it.looksLikeMetadata() && !it.isContainedBy(candidate.value) }
            ?.limitNotificationText(CONTENT_MAX_LENGTH)
        return LifeScenePrediction(
            title = "$titlePrefix ${candidate.value}".limitNotificationText(TITLE_MAX_LENGTH),
            content = content,
            score = candidate.score
        )
    }

    private fun detectDateScene(context: PredictionContext): LifeScenePrediction? {
        val date = findDateTime(context) ?: return null
        if (!context.containsAny(DATE_CONTEXT_KEYWORDS) && date.score < 4.6f) return null
        val relatedLine = findBestLine(
            context,
            DATE_CONTEXT_KEYWORDS,
            excludedTexts = setOf(date.sourceText)
        )
        val title = relatedLine?.text
            ?.takeIf { !it.looksLikeMetadata() && it.length <= 30 }
            ?.limitNotificationText(TITLE_MAX_LENGTH)
            ?: "日期提醒 ${date.value}".limitNotificationText(TITLE_MAX_LENGTH)
        val content = listOfNotNull(
            date.value,
            relatedLine?.text?.takeUnless { it.isContainedBy(title) }?.limitNotificationText(SCENE_PART_MAX_LENGTH)
        ).distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
            ?.limitNotificationText(CONTENT_MAX_LENGTH)
        return LifeScenePrediction(title, content, 8f + date.score)
    }

    private fun detectTicketScene(context: PredictionContext): LifeScenePrediction? {
        if (!context.containsAny(TICKET_CONTEXT_KEYWORDS)) return null
        val date = findDateTime(context)
        val seatOrGate = findLabeledValue(context, TICKET_VALUE_LABELS)
        val event = findBestLine(
            context,
            TICKET_CONTEXT_KEYWORDS,
            excludedTexts = setOf(date?.sourceText, seatOrGate?.sourceText).filterNotNull().toSet()
        ) ?: return null
        val content = listOfNotNull(date?.value, seatOrGate?.displayValue)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
            ?.limitNotificationText(CONTENT_MAX_LENGTH)
        return LifeScenePrediction(
            title = event.text.limitNotificationText(TITLE_MAX_LENGTH),
            content = content,
            score = 7.5f + (date?.score ?: 0f) + if (seatOrGate != null) 2f else 0f
        )
    }

    private fun scoreTitleCandidate(
        line: OcrLine,
        context: PredictionContext
    ): Float {
        val text = line.text
        if (text.length < 2 || text.length > 64) return -12f
        if (text.isMostlyNumeric() || text.hasTooManySymbols()) return -10f
        if (text.looksLikeUiNoise()) return -8f

        val imageWidth = context.imageWidth.toFloat().coerceAtLeast(1f)
        val imageHeight = context.imageHeight.toFloat().coerceAtLeast(1f)
        val topRatio = line.bound.top.toFloat() / imageHeight
        val centerOffset = abs(line.bound.centerX() - context.imageWidth / 2f) / imageWidth
        val widthRatio = line.bound.width().toFloat() / imageWidth
        val heightRatio = line.bound.height().toFloat() / context.medianHeight.toFloat().coerceAtLeast(1f)
        val areaRatio = line.bound.width().toFloat() * line.bound.height().toFloat() / (imageWidth * imageHeight)
        val textQuality = textQualityScore(text)
        var score = 0f
        score += when {
            topRatio <= 0.12f -> 3.8f
            topRatio <= 0.28f -> 4.4f
            topRatio <= 0.48f -> 2.4f
            topRatio <= 0.68f -> 0.8f
            else -> -2.4f
        }
        score += min(heightRatio, 2.8f) * 1.9f
        score += min(widthRatio * 3.2f, 2.4f)
        score += min(areaRatio * 160f, 2.2f)
        score += max(0f, 1.2f - centerOffset * 2.4f)
        score += textQuality
        score += when (text.length) {
            in 3..22 -> 2.2f
            in 23..38 -> 1.2f
            in 39..52 -> 0.3f
            else -> -1.2f
        }
        if (line.bound.top < context.medianTop) score += 0.7f
        if (text.hasTitlePunctuation()) score += 0.7f
        if (text.looksLikeMetadata()) score -= 3.5f
        if (text.looksLikeLongSentence()) score -= 1.4f
        return score
    }

    private fun predictKeywords(context: PredictionContext, title: String?): String? {
        val scores = linkedMapOf<String, KeywordScore>()
        context.lines.forEachIndexed { index, line ->
            val text = line.text
            if (text != title && text.length in 2..32 && !text.looksLikeUiNoise()) {
                scores.addScore(
                    text = text,
                    score = 2.5f + visualKeywordBoost(line, context) + positionKeywordBoost(index),
                    sourceIndex = index
                )
            }
            extractKeyValueKeywords(text).forEach { keyword ->
                scores.addScore(
                    text = keyword,
                    score = 4.2f + positionKeywordBoost(index),
                    sourceIndex = index
                )
            }
            segmentText(context, text).forEach { token ->
                if (token != title && !token.looksLikeUiNoise()) {
                    scores.addScore(
                        text = token,
                        score = 2.8f + min(token.length / 6f, 1.5f) + positionKeywordBoost(index),
                        sourceIndex = index
                    )
                }
            }
            tokenize(text).forEach { token ->
                if (token != title && !token.looksLikeUiNoise()) {
                    scores.addScore(
                        text = token,
                        score = 0.8f + min(token.length / 8f, 1.2f) + positionKeywordBoost(index),
                        sourceIndex = index
                    )
                }
            }
        }
        val titleText = title.orEmpty()
        val keywords = scores.values.asSequence()
            .filter { (keyword, _) -> keyword.length >= 2 }
            .filter { (keyword, _) -> !keyword.isContainedBy(titleText) }
            .filter { (keyword, _) -> !keyword.looksLikeMetadata() }
            .sortedWith(compareByDescending<KeywordScore> { it.score }.thenBy { it.firstSourceIndex })
            .map { it.text.limitNotificationText(KEYWORD_MAX_LENGTH) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .filterNotSimilar()
            .take(KEYWORD_COUNT)
            .toList()
        return keywords.joinToString(" · ").takeIf { it.isNotBlank() }
            ?.limitNotificationText(CONTENT_MAX_LENGTH)
    }

    private fun predictSummary(context: PredictionContext, title: String?): String? {
        return context.lines.asSequence()
            .filter { it.text != title }
            .filter { it.text.length >= 2 && !it.text.looksLikeUiNoise() }
            .filter { !it.text.looksLikeMetadata() }
            .sortedByDescending { scoreSummaryLine(it, context) }
            .take(SUMMARY_LINE_COUNT)
            .sortedWith(compareBy<OcrLine>({ it.bound.top }, { it.bound.left }))
            .map { it.text.limitNotificationText(SUMMARY_LINE_MAX_LENGTH) }
            .filterNotSimilar()
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
            ?.limitNotificationText(CONTENT_MAX_LENGTH)
    }

    private fun MutableMap<String, KeywordScore>.addScore(
        text: String,
        score: Float,
        sourceIndex: Int
    ) {
        val normalized = text.normalizeOcrText()
        if (normalized.isBlank() || normalized.length > 40) return
        val key = normalized.lowercase(Locale.ROOT)
        val current = this[key]
        this[key] = if (current == null) {
            KeywordScore(normalized, score, sourceIndex)
        } else {
            current.copy(
                score = current.score + score,
                firstSourceIndex = min(current.firstSourceIndex, sourceIndex)
            )
        }
    }

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val currentToken = StringBuilder()
        var currentType = TokenType.NONE

        fun flushToken() {
            if (currentToken.isEmpty()) return
            val token = currentToken.toString().normalizeOcrText()
            if (token.length >= 2 && !token.isStopWord()) {
                if (currentType == TokenType.CJK && token.length > CJK_TOKEN_MAX_LENGTH) {
                    tokens.addAll(token.extractCjkNgrams())
                } else {
                    tokens.add(token)
                }
            }
            currentToken.clear()
        }

        text.forEach { char ->
            val tokenType = when {
                char.isCjkChar() -> TokenType.CJK
                char.isLetterOrDigit() -> TokenType.LATIN
                else -> TokenType.NONE
            }
            if (tokenType == TokenType.NONE) {
                flushToken()
                currentType = TokenType.NONE
            } else {
                if (currentType != TokenType.NONE && currentType != tokenType) {
                    flushToken()
                }
                currentType = tokenType
                currentToken.append(char)
            }
        }
        flushToken()
        return tokens.distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun segmentText(context: PredictionContext, text: String): List<String> {
        val appContext = context.appContext ?: return emptyList()
        return runCatching { SmartSegmenter.segment(appContext, text) }
            .onFailure { Log.w(PREDICTION_TAG, "Segment notification OCR text failed", it) }
            .getOrDefault(emptyList())
            .asSequence()
            .map { it.normalizeOcrText() }
            .filter { it.isSegmentKeyword() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()
    }

    private fun visualKeywordBoost(line: OcrLine, context: PredictionContext): Float {
        val heightRatio = line.bound.height().toFloat() / context.medianHeight.toFloat().coerceAtLeast(1f)
        val widthRatio = line.bound.width().toFloat() / context.imageWidth.toFloat().coerceAtLeast(1f)
        return min(heightRatio, 2f) * 0.7f + min(widthRatio * 2f, 1.2f)
    }

    private fun positionKeywordBoost(index: Int): Float {
        return when (index) {
            0 -> 0.9f
            1 -> 0.6f
            2 -> 0.35f
            else -> 0f
        }
    }

    private fun scoreSummaryLine(line: OcrLine, context: PredictionContext): Float {
        var score = textQualityScore(line.text)
        score += visualKeywordBoost(line, context)
        score += when (line.text.length) {
            in 4..48 -> 1.8f
            in 49..80 -> 0.7f
            else -> -0.8f
        }
        if (line.text.looksLikeLongSentence()) score += 0.8f
        return score
    }

    private fun findCodeNear(
        context: PredictionContext,
        labels: Set<String>,
        contextKeywords: Set<String>
    ): SceneCode? {
        val candidates = mutableListOf<SceneCode>()
        context.lines.forEachIndexed { index, line ->
            val text = line.text
            val matchedLabel = labels.firstOrNull { text.contains(it, ignoreCase = true) }
            if (matchedLabel != null) {
                extractCodes(text).forEach { code ->
                    candidates.add(
                        SceneCode(
                            value = code,
                            label = matchedLabel,
                            sourceText = text,
                            score = 5f + codeScore(code) + positionKeywordBoost(index)
                        )
                    )
                }
                context.lines.getOrNull(index + 1)?.let { nextLine ->
                    extractCodes(nextLine.text).forEach { code ->
                        candidates.add(
                            SceneCode(
                                value = code,
                                label = matchedLabel,
                                sourceText = nextLine.text,
                                score = 4.2f + codeScore(code) + positionKeywordBoost(index)
                            )
                        )
                    }
                }
            }
            if (text.containsAny(contextKeywords)) {
                extractCodes(text).forEach { code ->
                    candidates.add(
                        SceneCode(
                            value = code,
                            label = matchedLabel.orEmpty(),
                            sourceText = text,
                            score = 3.4f + codeScore(code) + positionKeywordBoost(index)
                        )
                    )
                }
            }
        }
        return candidates
            .filter { it.value.isPickupCodeFormat() || it.value.length in 3..12 }
            .filterNot { it.value.looksLikeDateOnlyCode() }
            .maxByOrNull { it.score }
    }

    private fun findProminentShortCode(context: PredictionContext): SceneCode? {
        return context.lines.asSequence()
            .flatMapIndexed { index, line ->
                extractFallbackCodes(line.text).map { code ->
                    SceneCode(
                        value = code,
                        label = "",
                        sourceText = line.text,
                        score = prominentShortCodeScore(code, line, context, index),
                        isDominantNumericCode = isDominantNumericCode(code, line, context)
                    )
                }.asSequence()
            }
            .filter { it.value.isPickupCodeFormat() || it.value.length in 2..8 }
            .filterNot { it.value.looksLikeDateOnlyCode() }
            .filterNot { it.value.isCommonFalsePositiveCode(it.sourceText) }
            .maxByOrNull { it.score }
    }

    private fun findDateTime(context: PredictionContext): SceneDateTime? {
        return context.lines.asSequence()
            .flatMapIndexed { index, line ->
                extractDateTimes(line.text).map { dateText ->
                    SceneDateTime(
                        value = dateText,
                        sourceText = line.text,
                        score = dateTimeScore(dateText) + positionKeywordBoost(index)
                    )
                }.asSequence()
            }
            .maxByOrNull { it.score }
    }

    private fun findLabeledValue(
        context: PredictionContext,
        labels: Set<String>
    ): SceneLabeledValue? {
        return context.lines.asSequence()
            .flatMapIndexed { index, line ->
                labels.mapNotNull { label ->
                    if (!line.text.contains(label, ignoreCase = true)) return@mapNotNull null
                    val value = line.text.substringAfter(label, "")
                        .normalizeOcrText()
                        .trim(':', '：', '-', '—', ' ')
                        .takeIf { it.length in 1..18 && it.isMeaningfulText() }
                        ?: extractCodes(line.text).firstOrNull()
                        ?: return@mapNotNull null
                    SceneLabeledValue(
                        label = label,
                        value = value,
                        displayValue = "$label $value".limitNotificationText(SCENE_PART_MAX_LENGTH),
                        sourceText = line.text,
                        score = 3f + positionKeywordBoost(index)
                    )
                }.asSequence()
            }
            .maxByOrNull { it.score }
    }

    private fun findBestLine(
        context: PredictionContext,
        keywords: Set<String>,
        excludedTexts: Set<String> = emptySet()
    ): OcrLine? {
        return context.lines.asSequence()
            .filterNot { it.text in excludedTexts }
            .filter { it.text.length in 2..48 }
            .filter { !it.text.looksLikeUiNoise() }
            .mapIndexed { index, line ->
                val keywordScore = keywords.count { line.text.contains(it, ignoreCase = true) } * 2.2f
                val score = keywordScore + scoreSummaryLine(line, context) + positionKeywordBoost(index)
                line to score
            }
            .filter { (_, score) -> score > 2.8f }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun extractCodes(text: String): List<String> {
        val normalized = text.normalizeOcrText()
        val compact = normalized.replace(Regex("\\s+"), "")
        val pickupFormatValues = PICKUP_FORMAT_CODE_REGEX.findAll(normalized).mapNotNull { match ->
            match.value.normalizePickupSceneCode()
        }
        val labelValues = CODE_VALUE_REGEX.findAll(normalized).mapNotNull { match ->
            match.groupValues.getOrNull(2)?.normalizeSceneCode(preservePickupFormat = true)
        }
        val standaloneValues = SCENE_CODE_REGEX.findAll(compact).mapNotNull { match ->
            match.value.normalizeSceneCode()
        }
        return (pickupFormatValues + labelValues + standaloneValues)
            .filter { it.isPickupCodeFormat() || it.length in 3..12 }
            .filterNot { it.isStopWord() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()
    }

    private fun extractFallbackCodes(text: String): List<String> {
        val normalized = text.normalizeOcrText()
        val fallbackValues = if (normalized.hasFallbackCodeHint()) {
            FALLBACK_NUMERIC_CODE_REGEX.findAll(normalized)
                .map { it.value.normalizeFallbackNumericCode() }
                .toList()
        } else {
            emptyList()
        }
        return (extractCodes(text) + fallbackValues)
            .filter { it.isPickupCodeFormat() || it.any(Char::isDigit) }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    private fun extractDateTimes(text: String): List<String> {
        val normalized = text.normalizeOcrText()
        val results = mutableListOf<String>()
        DATE_TIME_PATTERNS.forEach { pattern ->
            pattern.findAll(normalized).forEach { match ->
                val value = match.value.normalizeOcrText()
                if (value.length in 3..28) results.add(value)
            }
        }
        return results.distinct()
    }

    private fun codeScore(code: String): Float {
        var score = 0f
        if (code.isPickupCodeFormat()) score += 4.8f
        if (code.any { it.isDigit() }) score += 1.5f
        if (code.any { it.isLetter() }) score += 0.8f
        score += when (code.length) {
            in 2..3 -> 2.8f
            in 4..6 -> 2.2f
            in 7..10 -> 1.5f
            else -> 0.6f
        }
        return score
    }

    private fun prominentShortCodeScore(
        code: String,
        line: OcrLine,
        context: PredictionContext,
        index: Int
    ): Float {
        val text = line.text
        var score = 4f + codeScore(code) + positionKeywordBoost(index)
        val heightRatio = line.bound.height().toFloat() / context.medianHeight.toFloat().coerceAtLeast(1f)
        val widthRatio = line.bound.width().toFloat() / context.imageWidth.toFloat().coerceAtLeast(1f)
        val imageAreaRatio = line.bound.width().toFloat() * line.bound.height().toFloat() /
            (context.imageWidth.toFloat().coerceAtLeast(1f) * context.imageHeight.toFloat().coerceAtLeast(1f))
        val centerOffset = abs(line.bound.centerX() - context.imageWidth / 2f) / context.imageWidth.toFloat().coerceAtLeast(1f)
        score += min(heightRatio, 3f) * 1.7f
        score += min(widthRatio * 2.2f, 1.8f)
        score += min(imageAreaRatio * 90f, 5f)
        score += max(0f, 1.4f - centerOffset * 2.8f)
        if (code.all { it.isDigit() }) score += 2.2f
        if (code.isPickupCodeFormat()) score += 3.5f
        if (isDominantNumericCode(code, line, context)) score += 6.5f
        if (code.length in 2..4) score += 2.4f
        if (text.normalizeOcrText() == code) score += 2.8f
        if (text.normalizeOcrText().normalizePickupSceneCode() == code) score += 2.4f
        if (text.containsAny(SHORT_CODE_CONTEXT_HINTS)) score += 3.2f
        if (text.length > 28) score -= 2.2f
        if (text.looksLikeMetadata()) score -= 4f
        return score
    }

    private fun isDominantNumericCode(
        code: String,
        line: OcrLine,
        context: PredictionContext
    ): Boolean {
        if (!code.any(Char::isDigit)) return false
        val normalizedLine = line.text.normalizeFallbackNumericCode()
        if (normalizedLine != code && line.text.normalizeSceneCode(preservePickupFormat = true) != code) {
            return false
        }
        val imageArea = context.imageWidth.toFloat().coerceAtLeast(1f) *
            context.imageHeight.toFloat().coerceAtLeast(1f)
        val areaRatio = line.bound.width().toFloat() * line.bound.height().toFloat() / imageArea
        val widthRatio = line.bound.width().toFloat() / context.imageWidth.toFloat().coerceAtLeast(1f)
        val heightRatio = line.bound.height().toFloat() / context.imageHeight.toFloat().coerceAtLeast(1f)
        val centerOffset = abs(line.bound.centerX() - context.imageWidth / 2f) /
            context.imageWidth.toFloat().coerceAtLeast(1f)
        return areaRatio >= DOMINANT_NUMBER_AREA_RATIO ||
            (widthRatio >= DOMINANT_NUMBER_WIDTH_RATIO && heightRatio >= DOMINANT_NUMBER_HEIGHT_RATIO && centerOffset <= 0.32f)
    }

    private fun inferShortCodeTitlePrefix(context: PredictionContext, nearbyText: String?): String {
        val allText = context.lines.joinToString(" ") { it.text } + " " + nearbyText.orEmpty()
        return when {
            allText.containsAny(TEA_CONTEXT_KEYWORDS) -> "取茶码"
            allText.containsAny(MEAL_CONTEXT_KEYWORDS) -> "取餐码"
            allText.containsAny(PICKUP_CONTEXT_KEYWORDS) -> "取件码"
            else -> GENERIC_CODE_TITLE
        }
    }

    private fun dateTimeScore(value: String): Float {
        var score = 2.5f
        if (value.contains(Regex("\\d{1,2}[:：]\\d{2}"))) score += 2f
        if (value.contains(Regex("今天|明天|后天|周[一二三四五六日天]|星期[一二三四五六日天]"))) score += 1.8f
        if (value.contains(Regex("\\d{1,2}[月/-]\\d{1,2}"))) score += 1.4f
        return score
    }

    private fun PredictionContext.containsAny(keywords: Set<String>): Boolean {
        return lines.any { it.text.containsAny(keywords) }
    }

    private fun PredictionContext.hasFallbackCodeHint(): Boolean {
        return lines.any { it.text.hasFallbackCodeHint() }
    }

    private fun textQualityScore(text: String): Float {
        val meaningful = text.count { it.isLetterOrDigit() || it.isCjkChar() }.toFloat()
        if (meaningful <= 0f) return -3f
        val meaningfulRatio = meaningful / text.length.toFloat().coerceAtLeast(1f)
        val letterRatio = text.count { it.isLetter() || it.isCjkChar() }.toFloat() / meaningful
        var score = meaningfulRatio * 2f + letterRatio
        if (text.any { it.isCjkChar() }) score += 0.5f
        if (text.any { it.isLetterOrDigit() }) score += 0.4f
        return score
    }

    private fun medianLineHeight(lines: List<OcrLine>): Int {
        return lines.map { it.bound.height() }.sorted().let { heights ->
            heights[heights.size / 2].coerceAtLeast(1)
        }
    }

    private fun medianLineTop(lines: List<OcrLine>): Int {
        return lines.map { it.bound.top }.sorted().let { tops ->
            tops[tops.size / 2].coerceAtLeast(0)
        }
    }

    private fun MutableList<OcrLine>.unionBounds(): Rect {
        val result = Rect(first().bound)
        drop(1).forEach { result.union(it.bound) }
        return result
    }

    private data class OcrLine(val text: String, val bound: Rect) {
        fun merge(other: OcrLine): OcrLine {
            val mergedBound = Rect(bound).apply { union(other.bound) }
            return OcrLine(
                text = listOf(text, other.text).joinToString(" ").normalizeOcrText(),
                bound = mergedBound
            )
        }

        companion object {
            fun from(node: ScreenContentNode): OcrLine? {
                val text = node.content.orEmpty().normalizeOcrText()
                if (text.isBlank()) return null
                return OcrLine(text, node.bound)
            }
        }
    }

    private data class PredictionContext(
        val lines: List<OcrLine>,
        val imageWidth: Int,
        val imageHeight: Int,
        val medianHeight: Int,
        val medianTop: Int,
        val appContext: Context?
    )

    private data class KeywordScore(
        val text: String,
        val score: Float,
        val firstSourceIndex: Int
    )

    private data class LifeScenePrediction(
        val title: String,
        val content: String?,
        val score: Float
    )

    private data class SceneCode(
        val value: String,
        val label: String,
        val sourceText: String,
        val score: Float,
        val isDominantNumericCode: Boolean = false
    )

    private data class SceneDateTime(
        val value: String,
        val sourceText: String,
        val score: Float
    )

    private data class SceneLabeledValue(
        val label: String,
        val value: String,
        val displayValue: String,
        val sourceText: String,
        val score: Float
    )

    private enum class TokenType {
        NONE,
        CJK,
        LATIN
    }

    private const val TITLE_MAX_LENGTH = 36
    private const val PREDICTION_TAG = "PinImageTopicPrediction"
    private const val CONTENT_MAX_LENGTH = 80
    private const val KEYWORD_MAX_LENGTH = 18
    private const val KEYWORD_COUNT = 4
    private const val CJK_TOKEN_MAX_LENGTH = 10
    private const val SUMMARY_LINE_COUNT = 2
    private const val SUMMARY_LINE_MAX_LENGTH = 36
    private const val SCENE_PART_MAX_LENGTH = 28
    private const val TITLE_MIN_SCORE = 6.2f
    private const val GENERIC_CODE_TITLE = "识别码"
    private const val DOMINANT_NUMBER_AREA_RATIO = 0.08f
    private const val DOMINANT_NUMBER_WIDTH_RATIO = 0.34f
    private const val DOMINANT_NUMBER_HEIGHT_RATIO = 0.08f

    private val PICKUP_CONTEXT_KEYWORDS = setOf(
        "取件", "快递", "包裹", "驿站", "菜鸟", "丰巢", "柜", "提货", "取货", "自提", "取件码", "取货码"
    )
    private val PICKUP_CODE_LABELS = setOf(
        "取件码", "取货码", "提货码", "自提码", "验证码", "开柜码", "取件编号", "取件号"
    )
    private val PICKUP_LOCATION_KEYWORDS = setOf(
        "驿站", "菜鸟", "丰巢", "快递柜", "门店", "地址", "网点", "服务站", "取件点"
    )
    private val MEAL_CONTEXT_KEYWORDS = setOf(
        "取餐", "取餐码", "取茶", "取茶码", "取饮品", "餐号", "外卖", "美团", "饿了么", "堂食", "排队", "叫号", "饮品", "奶茶", "咖啡", "订单", "茶饮"
    )
    private val MEAL_CODE_LABELS = setOf(
        "取餐码", "取茶码", "取饮品码", "取餐号", "餐号", "叫号", "排号", "订单号", "桌号", "柜号"
    )
    private val MEAL_MERCHANT_KEYWORDS = setOf(
        "店", "餐厅", "咖啡", "奶茶", "茶", "美团", "饿了么", "取餐", "堂食", "外卖"
    )
    private val DATE_CONTEXT_KEYWORDS = setOf(
        "预约", "会议", "提醒", "日程", "时间", "日期", "截止", "到期", "开会", "活动", "电影", "航班", "车次", "考试", "生日"
    )
    private val TICKET_CONTEXT_KEYWORDS = setOf(
        "电影", "影院", "影城", "航班", "登机", "车次", "火车", "高铁", "演出", "门票", "票", "座", "检票", "入场"
    )
    private val TICKET_VALUE_LABELS = setOf(
        "座位", "座", "厅", "场", "登机口", "检票口", "车厢", "座号", "排", "门"
    )
    private val TEA_CONTEXT_KEYWORDS = setOf(
        "取茶", "取茶码", "制茶", "茶饮", "奶茶", "咖啡", "饮品", "待制作", "制作中", "待取", "前方", "杯", "霸王茶姬", "喜茶", "奈雪", "瑞幸", "库迪"
    )
    private val SHORT_CODE_CONTEXT_HINTS = MEAL_CONTEXT_KEYWORDS + PICKUP_CONTEXT_KEYWORDS + TEA_CONTEXT_KEYWORDS + setOf(
        "码", "号", "凭", "领取", "核销", "验证", "出示", "扫码", "完成后", "请取", "等待"
    )
    private val CODE_VALUE_REGEX = Regex(
        "(取件码|取货码|提货码|自提码|取餐码|取茶码|取饮品码|取餐号|餐号|叫号|排号|开柜码|验证码|柜号|桌号|订单号)[:：\\s-]*([A-Za-z0-9][A-Za-z0-9\\-—–]{1,18}[A-Za-z0-9])"
    )
    private val PICKUP_FORMAT_CODE_REGEX = Regex("(?<![A-Za-z0-9])(?:[A-Za-z0-9]{1,3}[-—–]){2,4}[A-Za-z0-9]{1,4}(?![A-Za-z0-9])")
    private val FALLBACK_NUMERIC_CODE_REGEX = Regex("(?<!\\d)\\d{2,4}(?:[-—–]\\d{1,4}){0,4}(?!\\d)")
    private val SCENE_CODE_REGEX = Regex("(?<![A-Za-z0-9])[A-Za-z0-9]{2,12}(?![A-Za-z0-9])")
    private val DATE_TIME_PATTERNS = listOf(
        Regex("(今天|明天|后天)\\s*\\d{1,2}[:：]\\d{2}"),
        Regex("(周[一二三四五六日天]|星期[一二三四五六日天])\\s*\\d{1,2}[:：]\\d{2}"),
        Regex("\\d{4}[年/-]\\d{1,2}[月/-]\\d{1,2}[日号]?\\s*\\d{0,2}[:：]?\\d{0,2}"),
        Regex("\\d{1,2}[月/-]\\d{1,2}[日号]?\\s*\\d{1,2}[:：]\\d{2}"),
        Regex("\\d{1,2}[:：]\\d{2}(?:\\s*[-~至到]\\s*\\d{1,2}[:：]\\d{2})?")
    )
    private const val SHORT_CODE_FALLBACK_MIN_SCORE = 10.2f
}

private fun String.normalizeOcrText(): String = replace(Regex("\\s+"), " ")
    .trim()
    .trim(' ', '，', ',', '。', '.', '、', '|', '丨', ':', '：', ';', '；')

private fun String.limitNotificationText(maxLength: Int): String {
    val normalized = normalizeOcrText()
    if (normalized.length <= maxLength) return normalized
    return normalized.take(maxLength - 1).trimEnd() + "…"
}

private fun String.extractCjkNgrams(): List<String> {
    val normalized = normalizeOcrText()
    if (normalized.length < 2) return emptyList()
    val tokens = mutableListOf<String>()
    val keywordLength = when {
        normalized.length <= 6 -> normalized.length
        normalized.length <= 12 -> 4
        else -> 3
    }
    if (normalized.length <= 12) {
        tokens.add(normalized)
    }
    for (index in 0..(normalized.length - keywordLength)) {
        tokens.add(normalized.substring(index, index + keywordLength))
    }
    if (keywordLength > 3) {
        for (index in 0..(normalized.length - 3)) {
            tokens.add(normalized.substring(index, index + 3))
        }
    }
    return tokens.filter { !it.isStopWord() }.distinct()
}

private fun extractKeyValueKeywords(text: String): List<String> {
    val normalized = text.normalizeOcrText()
    val separators = listOf("：", ":", "—", "-", "|", "·")
    return separators.mapNotNull { separator ->
        val index = normalized.indexOf(separator)
        if (index <= 0 || index >= normalized.lastIndex) return@mapNotNull null
        val value = normalized.substring(index + separator.length).normalizeOcrText()
        value.takeIf { it.length in 2..24 && it.isMeaningfulText() && !it.looksLikeUiNoise() }
    }.distinctBy { it.lowercase(Locale.ROOT) }
}

private fun String.isSegmentKeyword(): Boolean {
    if (length !in 2..18) return false
    if (!isMeaningfulText()) return false
    if (isStopWord() || looksLikeUiNoise() || looksLikeMetadata()) return false

    val meaningfulChars = count { it.isLetterOrDigit() || it.isCjkChar() }
    if (meaningfulChars < 2) return false
    val cjkOrLetterChars = count { it.isLetter() || it.isCjkChar() }
    val digitChars = count { it.isDigit() }
    if (digitChars > 0 && cjkOrLetterChars == 0 && length < 4) return false
    return cjkOrLetterChars > 0 || digitChars >= 4
}

private fun Sequence<String>.filterNotSimilar(): Sequence<String> = sequence {
    val accepted = mutableListOf<String>()
    for (item in this@filterNotSimilar) {
        if (accepted.none { existing -> item.isSimilarTo(existing) }) {
            accepted.add(item)
            yield(item)
        }
    }
}

private fun String.isSimilarTo(other: String): Boolean {
    val left = lowercase(Locale.ROOT)
    val right = other.lowercase(Locale.ROOT)
    if (left == right) return true
    if (left.length < 3 || right.length < 3) return false
    return left.contains(right) || right.contains(left)
}

private fun String.isContainedBy(other: String): Boolean {
    if (isBlank() || other.isBlank()) return false
    return other.contains(this, ignoreCase = true) || this.contains(other, ignoreCase = true)
}

private fun String.containsAny(keywords: Set<String>): Boolean {
    return keywords.any { contains(it, ignoreCase = true) }
}

private fun String.hasFallbackCodeHint(): Boolean {
    return contains('取') || contains('码')
}

private fun String.normalizeSceneCode(preservePickupFormat: Boolean = false): String? {
    normalizePickupSceneCode()?.let { pickupCode ->
        if (preservePickupFormat || pickupCode.isPickupCodeFormat()) return pickupCode
    }
    val normalized = replace(Regex("[^A-Za-z0-9]"), "")
        .uppercase(Locale.ROOT)
    return normalized.takeIf { it.length in 3..12 }
}

private fun String.normalizePickupSceneCode(): String? {
    val normalized = replace('—', '-')
        .replace('–', '-')
        .replace(Regex("\\s*[-]\\s*"), "-")
        .trim('-', ' ', ':', '：')
        .uppercase(Locale.ROOT)
    return normalized.takeIf { it.isPickupCodeFormat() }
}

private fun String.normalizeFallbackNumericCode(): String {
    return replace('—', '-')
        .replace('–', '-')
        .replace(Regex("\\s*[-]\\s*"), "-")
        .trim('-', ' ', ':', '：')
}

private fun String.isPickupCodeFormat(): Boolean {
    return Regex("^[A-Z0-9]{1,3}(?:-[A-Z0-9]{1,4}){2,4}$").matches(this)
}

private fun String.looksLikeDateOnlyCode(): Boolean {
    if (isPickupCodeFormat()) return false
    return Regex("20\\d{2}|\\d{6,8}").matches(this)
}

private fun String.isCommonFalsePositiveCode(sourceText: String): Boolean {
    if (isPickupCodeFormat()) return false
    val normalizedSource = sourceText.normalizeOcrText()
    if (normalizedSource.contains(Regex("$this\\s*[%％折]"))) return true
    if (normalizedSource.contains(Regex("$this\\s*(元|¥|￥|分钟|分|小时|时|秒|件|杯|份|人|楼|层|号楼)"))) return true
    if (normalizedSource.contains(Regex("(第|共|约|满|减|省|优惠|折扣|券|余额)\\s*$this"))) return true
    if (Regex("0{2,}").matches(this)) return true
    if (length == 2 && toIntOrNull()?.let { it in 1..12 } == true && normalizedSource.containsAny(setOf("月", "月份", "日期"))) {
        return true
    }
    return false
}

private fun String.isMeaningfulText(): Boolean {
    if (isBlank() || length < 2) return false
    return any { it.isLetterOrDigit() || it.isCjkChar() }
}

private fun String.isMostlyNumeric(): Boolean {
    val meaningfulChars = count { it.isLetterOrDigit() || it.isCjkChar() }
    if (meaningfulChars == 0) return false
    val digitChars = count { it.isDigit() }
    return digitChars.toFloat() / meaningfulChars.toFloat() > 0.72f
}

private fun String.hasTooManySymbols(): Boolean {
    if (isEmpty()) return false
    val symbolCount = count { !it.isLetterOrDigit() && !it.isCjkChar() && !it.isWhitespace() }
    return symbolCount.toFloat() / length.toFloat() > 0.45f
}

private fun String.looksLikeUiNoise(): Boolean {
    val lowerText = lowercase(Locale.ROOT)
    if (lowerText.startsWith("http") || lowerText.startsWith("www.")) return true
    if (lowerText.endsWith(".com") || lowerText.endsWith(".cn") || lowerText.endsWith(".net")) return true
    if (Regex("^[\\d:：/\\-.年月日时分秒 ]+$").matches(lowerText)) return true
    if (Regex("^[a-z]$", RegexOption.IGNORE_CASE).matches(lowerText)) return true
    return lowerText in setOf(
        "ok",
        "yes",
        "no",
        "on",
        "off",
        "取消",
        "确定",
        "返回",
        "关闭",
        "保存",
        "发送",
        "分享",
        "更多",
        "复制",
        "编辑",
        "删除",
        "完成",
        "下一步",
        "跳过"
    )
}

private fun String.looksLikeMetadata(): Boolean {
    val lowerText = lowercase(Locale.ROOT)
    if (Regex("^\\d{1,2}[:：]\\d{2}.*").matches(lowerText)) return true
    if (Regex("^\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}.*").matches(lowerText)) return true
    if (Regex("^[+]?\\d[\\d\\s()-.]{5,}$").matches(lowerText)) return true
    return lowerText.startsWith("id:") ||
        lowerText.startsWith("no.") ||
        lowerText.startsWith("版本") ||
        lowerText.startsWith("version")
}

private fun String.looksLikeLongSentence(): Boolean {
    if (length < 18) return false
    return any { it in setOf('，', ',', '。', '.', '；', ';', '！', '!', '？', '?') }
}

private fun String.hasTitlePunctuation(): Boolean {
    return any { it in setOf('《', '》', '“', '”', '「', '」', '【', '】', '#') }
}

private fun String.isStopWord(): Boolean {
    val lowerText = lowercase(Locale.ROOT)
    return lowerText in setOf(
        "the",
        "and",
        "for",
        "with",
        "this",
        "that",
        "you",
        "your",
        "from",
        "have",
        "are",
        "was",
        "were",
        "will",
        "can",
        "not",
        "is",
        "to",
        "of",
        "in",
        "a",
        "an",
        "了",
        "的",
        "是",
        "在",
        "和",
        "或",
        "与",
        "我",
        "你",
        "他",
        "她",
        "它",
        "们",
        "这",
        "那",
        "就",
        "都",
        "也",
        "还",
        "要"
    )
}

private fun Char.isCjkChar(): Boolean {
    val unicodeBlock = Character.UnicodeBlock.of(this)
    return unicodeBlock == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        unicodeBlock == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        unicodeBlock == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
        unicodeBlock == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
        unicodeBlock == Character.UnicodeBlock.HIRAGANA ||
        unicodeBlock == Character.UnicodeBlock.KATAKANA ||
        unicodeBlock == Character.UnicodeBlock.HANGUL_SYLLABLES
}
