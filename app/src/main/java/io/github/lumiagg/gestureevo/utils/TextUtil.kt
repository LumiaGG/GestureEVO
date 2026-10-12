package io.github.lumiagg.gestureevo.utils

import kotlin.math.sqrt
import java.util.Locale

object TextUtil {
    private val whitespace = Regex("\\s+")

    fun calculateSimilarity(text1: String, text2: String): Double {
        val vector1 = createVector(text1)
        val vector2 = createVector(text2)

        return calculateCosineSimilarity(vector1, vector2)
    }

    fun calculateSimilarity(queryVector: Map<String, Int>, text: String): Double {
        val vector = createVector(text)
        return calculateCosineSimilarity(queryVector, vector)
    }

    fun createVector(text: String): Map<String, Int> {
        val vector = mutableMapOf<String, Int>()
        val words = text.lowercase(Locale.ROOT).split(whitespace)

        for (word in words) {
            vector[word] = vector.getOrDefault(word, 0) + 1
        }

        return vector
    }

    fun calculateCosineSimilarity(vector1: Map<String, Int>, vector2: Map<String, Int>): Double {
        val dotProduct = vector1.entries.sumOf { (word, count) -> count.toDouble() * (vector2[word] ?: 0) }
        val norm1 = sqrt(vector1.values.sumOf { it.toDouble() * it })
        val norm2 = sqrt(vector2.values.sumOf { it.toDouble() * it })
        return if (norm1 == 0.0 || norm2 == 0.0) 0.0 else dotProduct / (norm1 * norm2)
    }
}
