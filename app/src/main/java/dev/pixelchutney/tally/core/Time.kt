package dev.pixelchutney.tally.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * All ranges are half-open [start, end) in epoch millis, resolved in the device's
 * own zone — a payment at 11pm belongs to that day as the person lived it.
 */
object Time {

    val zone: ZoneId get() = ZoneId.systemDefault()

    fun now(): Long = System.currentTimeMillis()

    fun today(): LocalDate = LocalDate.now(zone)

    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayRange(date: LocalDate): LongRange =
        startOfDay(date) until startOfDay(date.plusDays(1))

    fun weekRange(date: LocalDate = today()): LongRange {
        val monday = date.with(DayOfWeek.MONDAY)
        return startOfDay(monday) until startOfDay(monday.plusWeeks(1))
    }

    fun monthRange(month: YearMonth = YearMonth.now(zone)): LongRange =
        startOfDay(month.atDay(1)) until startOfDay(month.plusMonths(1).atDay(1))

    /** Same slice of last month: 1st through the same day-of-month as today. */
    fun lastMonthToSameDay(reference: LocalDate = today()): LongRange {
        val lastMonth = YearMonth.from(reference).minusMonths(1)
        val day = minOf(reference.dayOfMonth, lastMonth.lengthOfMonth())
        return startOfDay(lastMonth.atDay(1)) until startOfDay(lastMonth.atDay(day).plusDays(1))
    }

    fun monthToDate(reference: LocalDate = today()): LongRange =
        startOfDay(reference.withDayOfMonth(1)) until startOfDay(reference.plusDays(1))

    fun lastNDays(n: Int, reference: LocalDate = today()): LongRange =
        startOfDay(reference.minusDays((n - 1).toLong())) until startOfDay(reference.plusDays(1))

    fun toLocalDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun toLocalDateTime(epochMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    fun daysInMonth(reference: LocalDate = today()): Int = YearMonth.from(reference).lengthOfMonth()

    fun daysRemainingInMonth(reference: LocalDate = today()): Int =
        daysInMonth(reference) - reference.dayOfMonth + 1

    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)

    private val dayLabel = DateTimeFormatter.ofPattern("EEE, d MMM")
    private val timeLabel = DateTimeFormatter.ofPattern("h:mm a")
    private val monthLabel = DateTimeFormatter.ofPattern("MMMM yyyy")
    private val shortMonth = DateTimeFormatter.ofPattern("MMM")

    fun formatDay(date: LocalDate): String = when (date) {
        today() -> "Today"
        today().minusDays(1) -> "Yesterday"
        else -> date.format(dayLabel)
    }

    fun formatTime(epochMillis: Long): String = toLocalDateTime(epochMillis).format(timeLabel)
    fun formatMonth(month: YearMonth): String = month.atDay(1).format(monthLabel)
    fun formatShortMonth(date: LocalDate): String = date.format(shortMonth)

    /** "2 min ago" for the sync line and session debug. */
    fun relative(epochMillis: Long, now: Long = now()): String {
        val minutes = (now - epochMillis) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 1440 -> "${minutes / 60}h ago"
            else -> "${minutes / 1440}d ago"
        }
    }
}
