package dev.pixelchutney.tally

import dev.pixelchutney.tally.insights.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MetricsTest {

    private val rupees = 100L   // paise per rupee, to keep fixtures readable

    @Test
    fun `safe to spend divides what is left across the days that remain`() {
        // ₹30,000 budget, ₹10,000 spent, 20 days left → ₹1,000 a day.
        assertEquals(
            1_000 * rupees,
            Metrics.safeToSpendPerDay(30_000 * rupees, 10_000 * rupees, 20),
        )
    }

    @Test
    fun `safe to spend goes negative once the budget is gone`() {
        assertTrue(Metrics.safeToSpendPerDay(10_000 * rupees, 12_000 * rupees, 5) < 0)
    }

    @Test
    fun `safe to spend is zero without a budget`() {
        assertEquals(0L, Metrics.safeToSpendPerDay(0, 5_000 * rupees, 10))
        assertEquals(0L, Metrics.safeToSpendPerDay(10_000 * rupees, 0, 0))
    }

    @Test
    fun `projection extrapolates the days so far`() {
        // ₹10,000 over 10 days of a 30 day month → ₹30,000.
        assertEquals(30_000 * rupees, Metrics.projection(10_000 * rupees, 10, 30))
        assertEquals(0L, Metrics.projection(5_000, 0, 30))
    }

    @Test
    fun `trimmed projection does not extrapolate a one-off`() {
        // Nine ordinary ₹500 days and one ₹20,000 flight.
        val days = List(9) { 500 * rupees } + listOf(20_000 * rupees)
        val plain = Metrics.projection(days.sum(), days.size, 30)
        val trimmed = Metrics.trimmedProjection(days, 30)

        assertTrue("the spike should not be multiplied out", trimmed < plain)
        // The flight still counts once, so the estimate stays above it.
        assertTrue(trimmed > 20_000 * rupees)
    }

    @Test
    fun `median handles both odd and even counts`() {
        assertEquals(0L, Metrics.median(emptyList()))
        assertEquals(30L, Metrics.median(listOf(10, 50, 30)))
        assertEquals(30L, Metrics.median(listOf(10, 20, 40, 50)))
    }

    @Test
    fun `z score stays quiet without enough history`() {
        assertEquals(0.0, Metrics.zScore(1000, listOf(500, 600)), 0.001)
    }

    @Test
    fun `z score flags a genuine spike`() {
        val steady = listOf(1000L, 1050L, 980L, 1020L, 1010L, 990L)
        assertTrue(Metrics.zScore(3000, steady) > 2.0)
        assertTrue(Metrics.zScore(1030, steady) < 2.0)
    }

    @Test
    fun `pace compares spending against the calendar`() {
        // Half the budget on the 15th of a 30 day month is exactly on pace.
        assertEquals(1.0, Metrics.pace(15_000, 30_000, 15, 30), 0.001)
        assertTrue(Metrics.pace(25_000, 30_000, 15, 30) > 1.0)
        assertEquals(0.0, Metrics.pace(5_000, 0, 10, 30), 0.001)
    }

    @Test
    fun `burn rate averages the last seven days`() {
        val days = List(14) { 100L } + List(7) { 200L }
        assertEquals(200L, Metrics.burnRate(days))
    }

    @Test
    fun `no spend streak counts back from yesterday`() {
        val today = LocalDate.of(2026, 3, 20)
        val spend = mapOf(
            today to 500L,                       // today does not count either way
            today.minusDays(1) to 0L,
            today.minusDays(2) to 0L,
            today.minusDays(3) to 900L,
        )
        val streak = Metrics.noSpendStreak(spend, today, today.minusDays(10))
        assertEquals(2, streak.current)
    }

    @Test
    fun `a spend yesterday breaks the streak`() {
        val today = LocalDate.of(2026, 3, 20)
        val spend = mapOf(today.minusDays(1) to 400L)
        assertEquals(0, Metrics.noSpendStreak(spend, today, today.minusDays(10)).current)
    }

    @Test
    fun `biggest movers rank by rupees not percentage`() {
        val current = mapOf("Food" to 12_000L, "Health" to 400L)
        val previous = mapOf("Food" to 6_000L, "Health" to 100L)

        val movers = Metrics.biggestMovers(current, previous, limit = 2, minChangePaise = 100)
        // Health quadrupled, but Food moved ₹60 and that is what matters.
        assertEquals("Food", movers.first().label)
        assertEquals(6_000L, movers.first().changePaise)
    }

    @Test
    fun `movers ignore changes too small to mention`() {
        val movers = Metrics.biggestMovers(
            current = mapOf("Food" to 10_100L),
            previous = mapOf("Food" to 10_000L),
            minChangePaise = 20_000L,
        )
        assertTrue(movers.isEmpty())
    }

    @Test
    fun `delta reports null percent when there is nothing to compare`() {
        assertEquals(null, Metrics.delta(500, 0).percent)
        assertEquals(50.0, Metrics.delta(150, 100).percent!!, 0.001)
    }

    @Test
    fun `share is bounded and safe at zero`() {
        assertEquals(0f, Metrics.share(500, 0), 0.001f)
        assertEquals(0.5f, Metrics.share(50, 100), 0.001f)
        assertEquals(1f, Metrics.share(200, 100), 0.001f)
    }
}
