package dev.pixelchutney.tally

import dev.pixelchutney.tally.data.db.TransactionEntity
import dev.pixelchutney.tally.data.model.Cadence
import dev.pixelchutney.tally.insights.RecurringDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RecurringDetectorTest {

    private fun txn(date: LocalDate, rupees: Long) = TransactionEntity(
        amountPaise = rupees * 100,
        timestamp = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        loggedAt = 0,
        categoryId = 1,
        merchantName = "Netflix",
    )

    @Test
    fun `monthly subscription is detected`() {
        val start = LocalDate.of(2026, 1, 5)
        val history = listOf(
            txn(start, 649),
            txn(start.plusMonths(1), 649),
            txn(start.plusMonths(2), 649),
            txn(start.plusMonths(3), 649),
        )
        val candidate = RecurringDetector.evaluate("Netflix", history)
        assertNotNull(candidate)
        assertEquals(Cadence.MONTHLY, candidate!!.cadence)
        assertEquals(64_900L, candidate.amountPaise)
        assertEquals(4, candidate.occurrences)
    }

    @Test
    fun `weekly repeat is detected`() {
        val start = LocalDate.of(2026, 2, 2)
        val history = (0..4).map { txn(start.plusWeeks(it.toLong()), 300) }
        val candidate = RecurringDetector.evaluate("Netflix", history)
        assertNotNull(candidate)
        assertEquals(Cadence.WEEKLY, candidate!!.cadence)
    }

    @Test
    fun `a frequently visited shop is not a subscription`() {
        // Same place, wildly irregular gaps and amounts: that is a habit, not a bill.
        val start = LocalDate.of(2026, 1, 1)
        val history = listOf(
            txn(start, 120),
            txn(start.plusDays(2), 800),
            txn(start.plusDays(3), 240),
            txn(start.plusDays(11), 90),
            txn(start.plusDays(12), 1500),
        )
        assertNull(RecurringDetector.evaluate("Netflix", history))
    }

    @Test
    fun `steady interval but drifting amounts is rejected`() {
        val start = LocalDate.of(2026, 1, 10)
        val history = listOf(
            txn(start, 200),
            txn(start.plusMonths(1), 900),
            txn(start.plusMonths(2), 1800),
            txn(start.plusMonths(3), 3400),
        )
        assertNull(RecurringDetector.evaluate("Netflix", history))
    }

    @Test
    fun `two payments are not yet a pattern`() {
        val start = LocalDate.of(2026, 1, 10)
        assertNull(RecurringDetector.evaluate("Netflix", listOf(txn(start, 649), txn(start.plusMonths(1), 649))))
    }

    @Test
    fun `next expected date follows the cadence`() {
        val start = LocalDate.of(2026, 1, 5)
        val history = (0..3).map { txn(start.plusMonths(it.toLong()), 649) }
        val candidate = RecurringDetector.evaluate("Netflix", history)!!
        val expected = LocalDate.of(2026, 5, 5)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, candidate.nextExpectedAt)
    }
}
