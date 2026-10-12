package io.github.lumiagg.gestureevo.utils

import android.util.Patterns

object PhoneDetector {
    private const val MIN_PHONE_DIGITS = 7

    data class PhoneMatch(
        val start: Int,
        val end: Int,
        val value: String
    )

    fun findPhoneMatches(text: String): List<PhoneMatch> {
        if (text.isBlank()) return emptyList()

        val results = ArrayList<PhoneMatch>()
        val matcher = Patterns.PHONE.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            if (start < 0 || end <= start) continue

            val value = text.substring(start, end)
            if (value.count { it.isDigit() } < MIN_PHONE_DIGITS) continue
            results.add(PhoneMatch(start, end, value))
        }
        return results
    }
}
