package dev.pixelchutney.tally.insights

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The arithmetic behind every headline number, kept free of Room and Android so
 * it can be unit-tested directly. If a number on the dashboard is ever wrong, the
 * bug is in here and there is a test that should have caught it.
 */
object Metrics {

    /**
     * What today can absorb without blowing the month. Deliberately divides by
     * days *remaining including today*, so the figure rises when you underspend
     * and falls the moment you overspend.
     */
    fun safeToSpendPerDay(limitPaise: Long, spentThisMonthPaise: Long, daysRemaining: Int): Long {
        if (limitPaise <= 0 || daysRemaining <= 0) return 0
        val left = limitPaise - spentThisMonthPaise
        return left / daysRemaining
    }

    /** Straight-line month-end estimate. */
    fun projection(spentPaise: Long, daysElapsed: Int, daysInMonth: Int): Long {
        if (daysElapsed <= 0) return 0
        return (spentPaise.toDouble() / daysElapsed * daysInMonth).roundToLong()
    }

    /**
     * Projection with one-off spikes held back. A single flight or a deposit
     * should not tell you every month will look like this, so days above
     * `spikeMultiple` × the median day are counted at their own value but not
     * extrapolated.
     */
    fun trimmedProjection(
        dailyPaise: List<Long>,
        daysInMonth: Int,
        spikeMultiple: Double = 3.0,
    ): Long {
        if (dailyPaise.isEmpty()) return 0
        val med = median(dailyPaise.filter { it > 0 })
        val cutoff = (med * spikeMultiple).toLong()
        val ordinary = dailyPaise.filter { med == 0L || it <= cutoff }
        val spikes = dailyPaise.filter { med > 0L && it > cutoff }.sum()
        if (ordinary.isEmpty()) return dailyPaise.sum()
        val ordinaryPerDay = ordinary.sum().toDouble() / dailyPaise.size
        return (ordinaryPerDay * daysInMonth).roundToLong() + spikes
    }

    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    fun mean(values: List<Long>): Double =
        if (values.isEmpty()) 0.0 else values.sum().toDouble() / values.size

    fun stdDev(values: List<Long>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        val variance = values.sumOf { (it - m) * (it - m) } / (values.size - 1)
        return sqrt(variance)
    }

    /** How unusual `current` is against its own history. Above 2 is worth saying out loud. */
    fun zScore(current: Long, history: List<Long>): Double {
        if (history.size < 3) return 0.0
        val sd = stdDev(history)
        if (sd < 1.0) return 0.0
        return (current - mean(history)) / sd
    }

    /** 7-day rolling average of daily spend, in paise per day. */
    fun burnRate(dailyPaise: List<Long>, window: Int = 7): Long {
        if (dailyPaise.isEmpty()) return 0
        val slice = dailyPaise.takeLast(window)
        return (slice.sum().toDouble() / slice.size).roundToLong()
    }

    /**
     * Budget pace: 1.0 means spending exactly in step with the calendar.
     * Above 1.0 means the month runs out of money before it runs out of days.
     */
    fun pace(spentPaise: Long, limitPaise: Long, dayOfMonth: Int, daysInMonth: Int): Double {
        if (limitPaise <= 0 || daysInMonth <= 0) return 0.0
        val expected = limitPaise.toDouble() * dayOfMonth / daysInMonth
        if (expected <= 0.0) return 0.0
        return spentPaise / expected
    }

    data class Streak(val current: Int, val best: Int)

    /**
     * Days with no spending at all. The current streak counts back from yesterday,
     * because today is not over and a quiet morning is not an achievement yet.
     */
    fun noSpendStreak(spendByDay: Map<LocalDate, Long>, today: LocalDate, since: LocalDate): Streak {
        var current = 0
        var day = today.minusDays(1)
        while (!day.isBefore(since) && (spendByDay[day] ?: 0L) == 0L) {
            current++
            day = day.minusDays(1)
        }

        var best = 0
        var run = 0
        var cursor = since
        while (!cursor.isAfter(today)) {
            if ((spendByDay[cursor] ?: 0L) == 0L && cursor != today) {
                run++
                if (run > best) best = run
            } else if (cursor != today) {
                run = 0
            }
            cursor = cursor.plusDays(1)
        }
        return Streak(current = current, best = maxOf(best, current))
    }

    data class Delta(val absolutePaise: Long, val percent: Double?)

    /** Change against a comparison period. Percent is null when there is nothing to compare to. */
    fun delta(current: Long, previous: Long): Delta {
        val abs = current - previous
        val pct = if (previous == 0L) null else abs.toDouble() / previous * 100.0
        return Delta(abs, pct)
    }

    data class Mover(val label: String, val currentPaise: Long, val previousPaise: Long) {
        val changePaise: Long get() = currentPaise - previousPaise
        val percent: Double? get() =
            if (previousPaise == 0L) null else changePaise.toDouble() / previousPaise * 100.0
    }

    /**
     * Which categories actually made this month different. Sorted by rupees moved,
     * not percent: a 300% jump on a ₹90 category explains nothing.
     */
    fun biggestMovers(
        current: Map<String, Long>,
        previous: Map<String, Long>,
        limit: Int = 3,
        minChangePaise: Long = 20_000L,
    ): List<Mover> =
        (current.keys + previous.keys)
            .map { Mover(it, current[it] ?: 0L, previous[it] ?: 0L) }
            .filter { abs(it.changePaise) >= minChangePaise }
            .sortedByDescending { abs(it.changePaise) }
            .take(limit)

    /** Share of a total, guarded against divide-by-zero. */
    fun share(part: Long, whole: Long): Float =
        if (whole <= 0L) 0f else (part.toDouble() / whole).toFloat().coerceIn(0f, 1f)
}
