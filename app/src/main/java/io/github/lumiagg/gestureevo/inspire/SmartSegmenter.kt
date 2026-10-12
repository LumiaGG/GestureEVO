package io.github.lumiagg.gestureevo.inspire

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.ln

class SmartSegmenter private constructor(private val dictionary: Dictionary) {

    fun segment(text: String): MutableList<String> {
        if (text.isEmpty()) return mutableListOf()

        val segments = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            when {
                isCrLf(text, index) -> {
                    segments.add("\n")
                    index += 2
                }

                text[index] == '\r' || text[index] == '\n' -> {
                    segments.add("\n")
                    index++
                }

                text[index].isWhitespace() -> {
                    val end = consumeWhile(text, index) { it.isWhitespace() && it != '\r' && it != '\n' }
                    segments.add(text.substring(index, end))
                    index = end
                }

                matchUrl(text, index)?.let { match ->
                    segments.add(match)
                    index += match.length
                    true
                } == true -> Unit

                matchEmail(text, index)?.let { match ->
                    segments.add(match)
                    index += match.length
                    true
                } == true -> Unit

                dictionary.longestMixedMatch(text, index).takeIf { it > 1 }?.let { matchLength ->
                    segments.add(text.substring(index, index + matchLength))
                    index += matchLength
                    true
                } == true -> Unit

                matchNumber(text, index)?.let { match ->
                    segments.add(match)
                    index += match.length
                    true
                } == true -> Unit

                isAsciiWordStart(text[index]) -> {
                    val end = consumeAsciiWord(text, index)
                    segments.add(text.substring(index, end))
                    index = end
                }

                isCjk(text[index]) -> {
                    val end = consumeWhile(text, index, ::isCjk)
                    segments.addAll(dictionary.segmentByFrequency(text.substring(index, end)))
                    index = end
                }

                Character.isHighSurrogate(text[index]) && index + 1 < text.length &&
                    Character.isLowSurrogate(text[index + 1]) -> {
                    segments.add(text.substring(index, index + 2))
                    index += 2
                }

                else -> {
                    segments.add(text[index].toString())
                    index++
                }
            }
        }
        return segments
    }

    private class Dictionary private constructor(
        private val entriesByFirstChar: Map<Char, List<WordEntry>>,
        private val totalFrequency: Double,
        private val oovHmm: OovHmm,
    ) {
        private val unknownScore = ln(1.0 / totalFrequency) - UNKNOWN_PENALTY

        fun longestMixedMatch(text: String, start: Int): Int {
            val entries = entriesByFirstChar[text[start]].orEmpty()
            val remaining = text.length - start
            for (entry in entries) {
                if (!entry.hasCjk || entry.word.length > remaining) continue
                if (text.regionMatches(start, entry.word, 0, entry.word.length, false)) {
                    return entry.word.length
                }
            }
            return 1
        }

        fun segmentByFrequency(text: String): List<String> {
            if (text.isEmpty()) return emptyList()

            val scores = DoubleArray(text.length + 1) { Double.NEGATIVE_INFINITY }
            val nextIndexes = IntArray(text.length) { it + 1 }
            val oovEdgesByStart = oovHmm.findCandidateEdges(text, unknownScore)
            scores[text.length] = 0.0

            for (index in text.length - 1 downTo 0) {
                var bestScore = unknownScore + scores[index + 1]
                var bestNext = index + 1
                val entries = entriesByFirstChar[text[index]].orEmpty()
                val remaining = text.length - index

                for (entry in entries) {
                    if (entry.word.length > remaining) continue
                    if (!text.regionMatches(index, entry.word, 0, entry.word.length, false)) continue

                    val next = index + entry.word.length
                    val score = entry.score + scores[next]
                    if (score > bestScore) {
                        bestScore = score
                        bestNext = next
                    }
                }

                for (edge in oovEdgesByStart[index]) {
                    val next = index + edge.length
                    val score = edge.score + scores[next]
                    if (score > bestScore) {
                        bestScore = score
                        bestNext = next
                    }
                }

                scores[index] = bestScore
                nextIndexes[index] = bestNext
            }

            val segments = mutableListOf<String>()
            var index = 0
            while (index < text.length) {
                val next = nextIndexes[index].coerceIn(index + 1, text.length)
                segments.add(text.substring(index, next))
                index = next
            }
            return oovHmm.refineUnknownSingles(segments)
        }

        private data class WordEntry(
            val word: String,
            val frequency: Int,
            val score: Double,
            val hasCjk: Boolean,
        )

        companion object {
            private const val EXPECTED_ENTRY_COUNT = 60_000
            private const val UNKNOWN_PENALTY = 8.0

            fun fromLines(lines: Sequence<String>): Dictionary {
                val frequencies = HashMap<String, Int>(EXPECTED_ENTRY_COUNT)
                lines.forEach { line ->
                    putParsedLine(line, frequencies)
                }
                return fromFrequencies(frequencies)
            }

            private fun fromFrequencies(frequencies: Map<String, Int>): Dictionary {
                val totalFrequency = frequencies.values.sumOf { it.toLong() }
                    .coerceAtLeast(1L)
                    .toDouble()
                val entriesByFirstChar = HashMap<Char, MutableList<WordEntry>>()
                frequencies.forEach { (word, frequency) ->
                    val entry = WordEntry(
                        word = word,
                        frequency = maxOf(1, frequency),
                        score = ln(maxOf(1, frequency).toDouble() / totalFrequency),
                        hasCjk = word.any(::isCjk),
                    )
                    entriesByFirstChar.getOrPut(word.first()) { mutableListOf() }.add(entry)
                }
                val sortedEntriesByFirstChar = entriesByFirstChar.mapValues { (_, values) ->
                    values.sortedWith(
                            compareByDescending<WordEntry> { it.word.length }
                                .thenByDescending { it.frequency }
                    )
                }
                return Dictionary(sortedEntriesByFirstChar, totalFrequency, OovHmm.fromEntries(frequencies))
            }

            private fun putParsedLine(line: String, frequencies: MutableMap<String, Int>) {
                var wordStart = 0
                while (wordStart < line.length && line[wordStart].isWhitespace()) wordStart++
                if (wordStart < line.length && line[wordStart] == '\uFEFF') wordStart++
                if (wordStart >= line.length || line[wordStart] == '#') return

                var wordEnd = wordStart
                while (wordEnd < line.length && !line[wordEnd].isWhitespace()) wordEnd++
                if (wordEnd - wordStart <= 1) return

                var frequencyStart = wordEnd
                while (frequencyStart < line.length && line[frequencyStart].isWhitespace()) frequencyStart++
                if (frequencyStart >= line.length) return

                var frequencyEnd = frequencyStart
                while (frequencyEnd < line.length && !line[frequencyEnd].isWhitespace()) frequencyEnd++
                val frequency = parseFrequency(line, frequencyStart, frequencyEnd) ?: return
                val word = line.substring(wordStart, wordEnd)
                frequencies[word] = maxOf(frequencies[word] ?: 0, frequency)
            }

            private fun parseFrequency(line: String, start: Int, end: Int): Int? {
                if (start >= end) return null
                var index = start
                var sign = 1
                when (line[index]) {
                    '+' -> index++
                    '-' -> {
                        sign = -1
                        index++
                    }
                }
                if (index >= end) return null

                var value = 0
                while (index < end) {
                    val digit = line[index] - '0'
                    if (digit !in 0..9) return null
                    value = value * 10 + digit
                    index++
                }
                return maxOf(1, value * sign)
            }
        }
    }

    private class OovHmm private constructor(
        private val startScores: DoubleArray,
        private val transitionScores: Array<DoubleArray>,
        private val emissionScores: Array<Map<Char, Double>>,
        private val unknownEmissionScores: DoubleArray,
    ) {

        fun refineUnknownSingles(segments: List<String>): List<String> {
            val refined = mutableListOf<String>()
            var index = 0
            while (index < segments.size) {
                if (!isSingleCjkSegment(segments[index])) {
                    refined.add(segments[index])
                    index++
                    continue
                }

                val start = index
                while (index < segments.size && isSingleCjkSegment(segments[index])) {
                    index++
                }

                if (index - start < MIN_OOV_RUN_LENGTH) {
                    for (singleIndex in start until index) refined.add(segments[singleIndex])
                    continue
                }

                val text = buildString {
                    for (singleIndex in start until index) append(segments[singleIndex])
                }
                refined.addAll(segment(text))
            }
            return refined
        }

        fun findCandidateEdges(text: String, unknownScore: Double): Array<MutableList<CandidateEdge>> {
            val edgesByStart = Array(text.length) { mutableListOf<CandidateEdge>() }
            addShortOovEdges(text, unknownScore, edgesByStart)
            if (text.length < MIN_OOV_RUN_LENGTH) return edgesByStart

            val tags = decodeTags(text)
            var index = 0
            while (index < text.length) {
                if (tags[index] != TAG_BEGIN) {
                    index++
                    continue
                }

                val start = index
                index++
                while (index < text.length && tags[index] == TAG_MIDDLE && index - start < MAX_OOV_WORD_LENGTH - 1) {
                    index++
                }
                if (index < text.length && tags[index] == TAG_END && index - start < MAX_OOV_WORD_LENGTH) {
                    val length = index - start + 1
                    if (length >= MIN_OOV_WORD_LENGTH) {
                        edgesByStart[start].add(
                            CandidateEdge(
                                length = length,
                                score = unknownScore * length + OOV_EDGE_BONUS + OOV_LENGTH_BONUS * length,
                            )
                        )
                    }
                    index++
                } else {
                    index = start + 1
                }
            }
            return edgesByStart
        }

        private fun addShortOovEdges(
            text: String,
            unknownScore: Double,
            edgesByStart: Array<MutableList<CandidateEdge>>,
        ) {
            for (index in 0 until text.lastIndex) {
                if (!isShortOovCandidate(text, index)) continue
                val score = shortOovScore(text, index, unknownScore) ?: continue

                edgesByStart[index].add(
                    CandidateEdge(
                        length = SHORT_OOV_WORD_LENGTH,
                        score = score,
                    )
                )
            }
        }

        private fun shortOovScore(text: String, start: Int, unknownScore: Double): Double? {
            val first = text[start]
            val second = text[start + 1]
            val joinedScore = transitionScores[TAG_BEGIN][TAG_END] +
                emissionScore(TAG_BEGIN, first) + emissionScore(TAG_END, second)
            val splitScore = transitionScores[TAG_SINGLE][TAG_SINGLE] +
                emissionScore(TAG_SINGLE, first) + emissionScore(TAG_SINGLE, second)
            val confidence = (joinedScore - splitScore).coerceIn(SHORT_OOV_MIN_CONFIDENCE, SHORT_OOV_MAX_CONFIDENCE)
            if (confidence <= SHORT_OOV_REJECT_CONFIDENCE) return null

            return unknownScore * SHORT_OOV_WORD_LENGTH + SHORT_OOV_EDGE_BONUS + confidence
        }

        private fun segment(text: String): List<String> {
            if (text.length < MIN_OOV_RUN_LENGTH) return text.map { it.toString() }

            return buildSegments(text, decodeTags(text))
        }

        private fun decodeTags(text: String): IntArray {
            val scores = Array(text.length) { DoubleArray(TAG_COUNT) { Double.NEGATIVE_INFINITY } }
            val previousTags = Array(text.length) { IntArray(TAG_COUNT) }

            for (tag in startTags) {
                scores[0][tag] = startScores[tag] + emissionScore(tag, text[0])
            }

            for (index in 1 until text.length) {
                for (tag in 0 until TAG_COUNT) {
                    var bestScore = Double.NEGATIVE_INFINITY
                    var bestPrevious = TAG_SINGLE
                    val emissionScore = emissionScore(tag, text[index])
                    for (previousTag in 0 until TAG_COUNT) {
                        if (!isValidTransition(previousTag, tag)) continue
                        val candidate = scores[index - 1][previousTag] +
                            transitionScores[previousTag][tag] + emissionScore
                        if (candidate > bestScore) {
                            bestScore = candidate
                            bestPrevious = previousTag
                        }
                    }
                    scores[index][tag] = bestScore
                    previousTags[index][tag] = bestPrevious
                }
            }

            var bestLastTag = TAG_SINGLE
            var bestLastScore = Double.NEGATIVE_INFINITY
            for (tag in endTags) {
                if (scores[text.lastIndex][tag] > bestLastScore) {
                    bestLastScore = scores[text.lastIndex][tag]
                    bestLastTag = tag
                }
            }

            val tags = IntArray(text.length)
            tags[text.lastIndex] = bestLastTag
            for (index in text.lastIndex downTo 1) {
                tags[index - 1] = previousTags[index][tags[index]]
            }
            return tags
        }

        private fun buildSegments(text: String, tags: IntArray): List<String> {
            val segments = mutableListOf<String>()
            var index = 0
            while (index < text.length) {
                val tag = tags[index]
                if (tag == TAG_SINGLE || tag == TAG_END) {
                    segments.add(text[index].toString())
                    index++
                    continue
                }

                val start = index
                index++
                while (index < text.length && tags[index] == TAG_MIDDLE && index - start < MAX_OOV_WORD_LENGTH - 1) {
                    index++
                }
                if (index < text.length && tags[index] == TAG_END && index - start < MAX_OOV_WORD_LENGTH) {
                    index++
                    segments.add(text.substring(start, index))
                } else {
                    segments.add(text[start].toString())
                    index = start + 1
                }
            }
            return segments
        }

        private fun emissionScore(tag: Int, char: Char): Double {
            return emissionScores[tag][char] ?: unknownEmissionScores[tag]
        }

        data class CandidateEdge(
            val length: Int,
            val score: Double,
        )

        private class Trainer {
            private val startCounts = DoubleArray(TAG_COUNT) { SMOOTHING }
            private val transitionCounts = Array(TAG_COUNT) { DoubleArray(TAG_COUNT) { SMOOTHING } }
            private val emissionCounts = Array(TAG_COUNT) { HashMap<Char, Double>() }
            private val tagTotals = DoubleArray(TAG_COUNT) { SMOOTHING }
            private val chars = HashSet<Char>()

            fun add(word: String, frequency: Int) {
                if (word.isEmpty() || !word.all(::isCjk)) return

                val tags = tagsFor(word.length)
                val weight = ln(frequency.coerceAtLeast(1).toDouble() + 1.0).coerceAtLeast(1.0)
                startCounts[tags.first()] += weight

                for (index in word.indices) {
                    val tag = tags[index]
                    val char = word[index]
                    chars.add(char)
                    emissionCounts[tag][char] = (emissionCounts[tag][char] ?: 0.0) + weight
                    tagTotals[tag] += weight
                    if (index > 0) {
                        transitionCounts[tags[index - 1]][tag] += weight
                    }
                }
            }

            fun build(): OovHmm {
                val startTotal = startCounts.sum()
                val startScores = DoubleArray(TAG_COUNT) { tag -> ln(startCounts[tag] / startTotal) }
                val transitionScores = Array(TAG_COUNT) { previousTag ->
                    val total = transitionCounts[previousTag].sum()
                    DoubleArray(TAG_COUNT) { tag -> ln(transitionCounts[previousTag][tag] / total) }
                }
                val vocabularySize = chars.size.coerceAtLeast(1).toDouble()
                val emissionScores = Array(TAG_COUNT) { tag ->
                    emissionCounts[tag].mapValues { (_, count) ->
                        ln((count + SMOOTHING) / (tagTotals[tag] + SMOOTHING * (vocabularySize + 1.0)))
                    }
                }
                val unknownEmissionScores = DoubleArray(TAG_COUNT) { tag ->
                    ln(SMOOTHING / (tagTotals[tag] + SMOOTHING * (vocabularySize + 1.0)))
                }
                return OovHmm(startScores, transitionScores, emissionScores, unknownEmissionScores)
            }
        }

        companion object {
            private const val TAG_BEGIN = 0
            private const val TAG_MIDDLE = 1
            private const val TAG_END = 2
            private const val TAG_SINGLE = 3
            private const val TAG_COUNT = 4
            private const val MIN_OOV_RUN_LENGTH = 3
            private const val MIN_OOV_WORD_LENGTH = 2
            private const val SHORT_OOV_WORD_LENGTH = 2
            private const val MAX_OOV_WORD_LENGTH = 4
            private const val OOV_EDGE_BONUS = 16.0
            private const val OOV_LENGTH_BONUS = 1.0
            private const val SHORT_OOV_EDGE_BONUS = 8.0
            private const val SHORT_OOV_MIN_CONFIDENCE = -4.0
            private const val SHORT_OOV_MAX_CONFIDENCE = 4.0
            private const val SHORT_OOV_REJECT_CONFIDENCE = -3.0
            private const val SMOOTHING = 1.0

            private val startTags = intArrayOf(TAG_BEGIN, TAG_SINGLE)
            private val endTags = intArrayOf(TAG_END, TAG_SINGLE)

            private val shortOovBoundaryChars = setOf(
                '的', '地', '得', '了', '着', '过', '在', '是', '和', '与', '及', '或', '而', '但',
                '就', '都', '很', '也', '还', '又', '不', '没', '无', '有', '把', '被', '给', '对',
                '从', '到', '于', '以', '为', '将', '会', '能', '可', '要', '让', '使', '并', '等',
                '我', '你', '他', '她', '它', '们', '这', '那', '哪', '谁', '吗', '呢', '啊', '吧',
            )

            fun fromEntries(frequencies: Map<String, Int>): OovHmm {
                val trainer = Trainer()
                frequencies.forEach { (word, frequency) -> trainer.add(word, frequency) }
                return trainer.build()
            }

            private fun tagsFor(length: Int): IntArray {
                if (length == 1) return intArrayOf(TAG_SINGLE)

                return IntArray(length) { index ->
                    when (index) {
                        0 -> TAG_BEGIN
                        length - 1 -> TAG_END
                        else -> TAG_MIDDLE
                    }
                }
            }

            private fun isSingleCjkSegment(segment: String): Boolean {
                return segment.length == 1 && isCjk(segment[0])
            }

            private fun isShortOovCandidate(text: String, start: Int): Boolean {
                val first = text[start]
                val second = text[start + 1]
                if (!isCjk(first) || !isCjk(second)) return false
                if (first in shortOovBoundaryChars || second in shortOovBoundaryChars) return false
                if (start > 0 && text[start - 1] == first) return false
                if (start + 2 < text.length && text[start + 2] == second) return false
                return true
            }

            private fun isValidTransition(previousTag: Int, tag: Int): Boolean {
                return when (previousTag) {
                    TAG_BEGIN -> tag == TAG_MIDDLE || tag == TAG_END
                    TAG_MIDDLE -> tag == TAG_MIDDLE || tag == TAG_END
                    TAG_END -> tag == TAG_BEGIN || tag == TAG_SINGLE
                    TAG_SINGLE -> tag == TAG_BEGIN || tag == TAG_SINGLE
                    else -> false
                }
            }
        }
    }

    companion object {
        private const val DICTIONARY_ASSET = "segmenter/zh_core_dict.txt"
        private const val TAG = "SmartSegmenter"

        private val urlRegex = Regex(
            "^(?i)(?:https?://|ftp://|www\\.)[^\\s\\uFF0C\\u3002\\uFF01\\uFF1F\\uFF1B\\uFF1A\\u3001\\uFF08\\uFF09()\u300A\u300B<>\\\"\u201C\u201D\u2018\u2019]+"
        )
        private val emailRegex = Regex(
            "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"
        )
        private val numberRegex = Regex(
            "^[+-]?[0-9]+(?:[.,:/\\-][0-9]+)*(?:[%\\uFF05])?"
        )

        @Volatile
        private var assetDictionary: Dictionary? = null

        @Volatile
        private var dictionaryFuture: Future<Dictionary>? = null

        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "smart-segmenter-dictionary").apply { isDaemon = true }
        }

        fun prewarm(context: Context) {
            getOrCreateFuture(context.applicationContext)
        }

        fun segment(context: Context, text: String): MutableList<String> {
            return SmartSegmenter(loadDictionary(context)).segment(text)
        }

        fun releaseCache() {
            synchronized(this) {
                assetDictionary = null
                if (dictionaryFuture?.isDone == true) {
                    dictionaryFuture = null
                }
            }
        }

        private fun loadDictionary(context: Context): Dictionary {
            assetDictionary?.let { return it }

            while (true) {
                val future = getOrCreateFuture(context.applicationContext)
                try {
                    return future.get().also { dictionary ->
                        if (assetDictionary == null) {
                            synchronized(this) {
                                if (assetDictionary == null) {
                                    assetDictionary = dictionary
                                }
                            }
                        }
                    }
                } catch (exception: ExecutionException) {
                    synchronized(this) {
                        if (dictionaryFuture === future) dictionaryFuture = null
                    }
                    throw exception.cause ?: exception
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw exception
                }
            }
        }

        private fun getOrCreateFuture(context: Context): Future<Dictionary> {
            dictionaryFuture?.let { return it }

            return synchronized(this) {
                dictionaryFuture ?: executor.submit<Dictionary> {
                    loadAssetDictionary(context).also { dictionary ->
                        assetDictionary = dictionary
                    }
                }.also {
                    dictionaryFuture = it
                }
            }
        }

        private fun loadAssetDictionary(context: Context): Dictionary {
            val startMs = SystemClock.elapsedRealtime()
            return runCatching {
                context.assets.open(DICTIONARY_ASSET).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
                        Dictionary.fromLines(lines)
                    }
                }
            }.getOrThrow().also {
                Log.i(TAG, "Segmenter dictionary loaded in ${SystemClock.elapsedRealtime() - startMs} ms")
            }
        }

        private fun matchUrl(text: String, start: Int): String? {
            return matchAt(urlRegex, text, start)?.trimEnd('.', ',', ';', ':', '\uFF0C', '\u3002', '\uFF1B', '\uFF1A')
        }

        private fun matchEmail(text: String, start: Int): String? {
            if (!isAsciiWordStart(text[start])) return null
            return matchAt(emailRegex, text, start)
        }

        private fun matchNumber(text: String, start: Int): String? {
            val char = text[start]
            if (!char.isDigit() && char != '+' && char != '-') return null
            return matchAt(numberRegex, text, start)
        }

        private fun matchAt(regex: Regex, text: String, start: Int): String? {
            return regex.find(text.substring(start))?.value?.takeIf { it.isNotEmpty() }
        }

        private fun consumeAsciiWord(text: String, start: Int): Int {
            var index = start + 1
            while (index < text.length && isAsciiWordPart(text[index])) {
                index++
            }
            return index
        }

        private fun consumeWhile(text: String, start: Int, predicate: (Char) -> Boolean): Int {
            var index = start
            while (index < text.length && predicate(text[index])) {
                index++
            }
            return index
        }

        private fun isCrLf(text: String, index: Int): Boolean {
            return text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n'
        }

        private fun isAsciiWordStart(char: Char): Boolean {
            return char in 'A'..'Z' || char in 'a'..'z'
        }

        private fun isAsciiWordPart(char: Char): Boolean {
            return char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                char == '_' || char == '-' || char == '+' || char == '#' || char == '.'
        }

        private fun isCjk(char: Char): Boolean {
            return when (Character.UnicodeBlock.of(char)) {
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
                Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS -> true
                else -> false
            }
        }
    }
}
