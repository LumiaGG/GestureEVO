package io.github.lumiagg.gestureevo.pin

import java.util.Calendar
import java.util.Locale

data class PinPoolReminderTimeCandidate(
    val triggerAt: Long,
    val matchedText: String
)

object PinPoolReminderTimeParser {
    private val datePattern = Regex("(?:(\\d{2,4})[年\\-/.])?(\\d{1,2})[月\\-/.](\\d{1,2})日?")
    private val relativeDayPattern = Regex("今天|明天|后天|大后天|today|tomorrow|tonight", RegexOption.IGNORE_CASE)
    private val weekdayPattern = Regex("周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天]")
    private val timePattern = Regex(
        "(凌晨|早上|上午|中午|下午|晚上|傍晚|am|pm)?\\s*(\\d{1,2})(?:(?:[:：])(\\d{1,2})|[点时](\\d{1,2})?)\\s*(半)?",
        RegexOption.IGNORE_CASE
    )
    private val amPmHourPattern = Regex(
        "(凌晨|早上|上午|中午|下午|晚上|傍晚|am|pm)\\s*(\\d{1,2})\\s*(半)?",
        RegexOption.IGNORE_CASE
    )

    fun findBest(text: String, now: Long = System.currentTimeMillis()): PinPoolReminderTimeCandidate? {
        val normalized = normalize(text)
        if (normalized.isBlank()) return null
        return buildList {
            addAll(parseAbsoluteDates(normalized, now))
            addAll(parseRelativeDays(normalized, now))
            addAll(parseWeekdays(normalized, now))
            parseTime(normalized)?.let { time ->
                val triggerAt = calendarAt(now, time.hour, time.minute)
                    .let { if (it <= now) it + DAY_MILLIS else it }
                add(PinPoolReminderTimeCandidate(triggerAt, time.matchedText))
            }
        }
            .filter { it.triggerAt > now }
            .distinctBy { it.triggerAt }
            .minByOrNull { it.triggerAt }
    }

    private fun parseAbsoluteDates(text: String, now: Long): List<PinPoolReminderTimeCandidate> {
        val nowCalendar = Calendar.getInstance()
        return datePattern.findAll(text).mapNotNull { match ->
            val year = match.groupValues[1].takeIf { it.isNotBlank() }?.toIntOrNull()
                ?.let { if (it < 100) 2000 + it else it }
                ?: nowCalendar.get(Calendar.YEAR)
            val month = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val day = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val nearby = text.substring(match.range.last + 1, (match.range.last + 18).coerceAtMost(text.length))
            val time = parseTime(nearby)
            val hour = time?.hour ?: DEFAULT_HOUR
            val minute = time?.minute ?: DEFAULT_MINUTE
            val triggerAt = calendarAt(year, month, day, hour, minute) ?: return@mapNotNull null
            if (triggerAt <= now && match.groupValues[1].isBlank()) {
                val nextYearTriggerAt = calendarAt(year + 1, month, day, hour, minute) ?: return@mapNotNull null
                PinPoolReminderTimeCandidate(nextYearTriggerAt, match.value + time?.matchedText.orEmpty())
            } else {
                PinPoolReminderTimeCandidate(triggerAt, match.value + time?.matchedText.orEmpty())
            }
        }.toList()
    }

    private fun parseRelativeDays(text: String, now: Long): List<PinPoolReminderTimeCandidate> {
        return relativeDayPattern.findAll(text).mapNotNull { match ->
            val keyword = match.value.lowercase(Locale.getDefault())
            val dayOffset = when (keyword) {
                "今天", "today", "tonight" -> 0
                "明天", "tomorrow" -> 1
                "后天" -> 2
                "大后天" -> 3
                else -> 0
            }
            val nearby = text.substring(match.range.last + 1, (match.range.last + 18).coerceAtMost(text.length))
            val time = parseTime(nearby)
            val hour = time?.hour ?: if (keyword == "tonight") 20 else DEFAULT_HOUR
            val minute = time?.minute ?: DEFAULT_MINUTE
            val triggerAt = calendarAt(now, hour, minute, dayOffset)
            PinPoolReminderTimeCandidate(triggerAt, match.value + time?.matchedText.orEmpty())
        }.toList()
    }

    private fun parseWeekdays(text: String, now: Long): List<PinPoolReminderTimeCandidate> {
        return weekdayPattern.findAll(text).mapNotNull { match ->
            val targetDay = parseWeekday(match.value) ?: return@mapNotNull null
            val nearby = text.substring(match.range.last + 1, (match.range.last + 18).coerceAtMost(text.length))
            val time = parseTime(nearby)
            val nowCalendar = Calendar.getInstance().apply { timeInMillis = now }
            val currentDay = nowCalendar.get(Calendar.DAY_OF_WEEK)
            var offset = (targetDay - currentDay + 7) % 7
            val hour = time?.hour ?: DEFAULT_HOUR
            val minute = time?.minute ?: DEFAULT_MINUTE
            val candidate = calendarAt(now, hour, minute, offset)
            if (offset == 0 && candidate <= now) offset = 7
            PinPoolReminderTimeCandidate(
                calendarAt(now, hour, minute, offset),
                match.value + time?.matchedText.orEmpty()
            )
        }.toList()
    }

    private fun parseTime(text: String): ParsedTime? {
        val match = timePattern.find(text) ?: amPmHourPattern.find(text) ?: return null
        val meridiem = match.groupValues[1]
        val hour = match.groupValues[2].toIntOrNull() ?: return null
        val minute = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            ?: match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            ?: if (match.groupValues.lastOrNull() == "半") 30 else 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return ParsedTime(adjustHour(hour, meridiem), minute, match.value.trim())
    }

    private fun adjustHour(hour: Int, meridiem: String): Int {
        val marker = meridiem.lowercase(Locale.getDefault())
        return when (marker) {
            "下午", "晚上", "傍晚", "pm" -> if (hour in 1..11) hour + 12 else hour
            "中午" -> if (hour in 1..10) hour + 12 else hour
            "凌晨", "早上", "上午", "am" -> if (hour == 12) 0 else hour
            else -> hour
        }
    }

    private fun calendarAt(now: Long, hour: Int, minute: Int, dayOffset: Int = 0): Long {
        return Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun calendarAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long? {
        if (month !in 1..12 || day !in 1..31) return null
        val calendar = Calendar.getInstance().apply {
            isLenient = false
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return runCatching { calendar.timeInMillis }.getOrNull()
    }

    private fun parseWeekday(value: String): Int? {
        return when (value.last()) {
            '日', '天' -> Calendar.SUNDAY
            '一' -> Calendar.MONDAY
            '二' -> Calendar.TUESDAY
            '三' -> Calendar.WEDNESDAY
            '四' -> Calendar.THURSDAY
            '五' -> Calendar.FRIDAY
            '六' -> Calendar.SATURDAY
            else -> null
        }
    }

    private fun normalize(text: String): String {
        return buildString(text.length) {
            text.forEach { char ->
                append(
                    when (char) {
                        in '０'..'９' -> '0' + (char - '０')
                        '：' -> ':'
                        else -> char
                    }
                )
            }
        }
    }

    private data class ParsedTime(
        val hour: Int,
        val minute: Int,
        val matchedText: String
    )

    private const val DEFAULT_HOUR = 9
    private const val DEFAULT_MINUTE = 0
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
}
