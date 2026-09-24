package dev.pixelchutney.tally.insights

import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.RecurringDao
import dev.pixelchutney.tally.data.db.RecurringRuleEntity
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.db.TransactionEntity
import dev.pixelchutney.tally.data.model.Cadence
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Finds the payments that repeat on their own. A subscription you forgot about is
 * the cheapest thing to cut, so this proposes candidates and waits — nothing counts
 * towards the committed-spend figure until it is confirmed by hand.
 */
@Singleton
class RecurringDetector @Inject constructor(
    private val txns: TransactionDao,
    private val recurring: RecurringDao,
) {

    data class Candidate(
        val merchantName: String,
        val merchantId: Long,
        val amountPaise: Long,
        val cadence: Cadence,
        val occurrences: Int,
        val lastSeenAt: Long,
        val nextExpectedAt: Long,
    )

    suspend fun scan(lookbackDays: Int = 210): List<Candidate> {
        val since = Time.startOfDay(Time.today().minusDays(lookbackDays.toLong()))
        return txns.distinctMerchantNames(since)
            .filter { it.isNotBlank() }
            .mapNotNull { name -> evaluate(name, txns.historyFor(name)) }
    }

    /** Persists new candidates without touching ones already confirmed or dismissed. */
    suspend fun scanAndStore(): List<Candidate> {
        val found = scan()
        found.forEach { candidate ->
            val existing = recurring.byMerchantName(candidate.merchantName)
            if (existing == null) {
                recurring.upsert(
                    RecurringRuleEntity(
                        merchantId = candidate.merchantId,
                        merchantName = candidate.merchantName,
                        expectedAmountPaise = candidate.amountPaise,
                        cadence = candidate.cadence,
                        nextExpectedAt = candidate.nextExpectedAt,
                        lastSeenAt = candidate.lastSeenAt,
                        occurrences = candidate.occurrences,
                    )
                )
            } else if (!existing.dismissed) {
                recurring.update(
                    existing.copy(
                        expectedAmountPaise = candidate.amountPaise,
                        cadence = candidate.cadence,
                        nextExpectedAt = candidate.nextExpectedAt,
                        lastSeenAt = candidate.lastSeenAt,
                        occurrences = candidate.occurrences,
                    )
                )
            }
        }
        return found
    }

    /** Subscriptions whose expected date has passed without a matching payment. */
    suspend fun overdue(today: LocalDate = Time.today()): List<RecurringRuleEntity> {
        val now = Time.startOfDay(today)
        return recurring.confirmed().filter { it.nextExpectedAt in 1 until now }
    }

    companion object {
        const val MIN_OCCURRENCES = 3
        const val AMOUNT_TOLERANCE = 0.10
        const val GAP_TOLERANCE = 0.20

        fun evaluate(name: String, history: List<TransactionEntity>): Candidate? {
            if (history.size < MIN_OCCURRENCES) return null

            val gaps = history.zipWithNext { a, b ->
                Time.daysBetween(Time.toLocalDate(a.timestamp), Time.toLocalDate(b.timestamp))
            }.filter { it > 0 }
            if (gaps.isEmpty()) return null

            val cadence = cadenceFor(gaps) ?: return null

            // Amounts must be stable: a subscription that changes every month is just
            // a shop you visit often, and calling it recurring would be a lie.
            val amounts = history.map { it.amountPaise }
            val typical = Metrics.median(amounts)
            if (typical <= 0) return null
            val stable = amounts.count { abs(it - typical).toDouble() / typical <= AMOUNT_TOLERANCE }
            if (stable < (amounts.size * 0.7)) return null

            val last = history.last()
            val lastDate = Time.toLocalDate(last.timestamp)
            val next = when (cadence) {
                Cadence.WEEKLY -> lastDate.plusWeeks(1)
                Cadence.MONTHLY -> lastDate.plusMonths(1)
                Cadence.QUARTERLY -> lastDate.plusMonths(3)
                Cadence.YEARLY -> lastDate.plusYears(1)
            }

            return Candidate(
                merchantName = name,
                merchantId = last.merchantId ?: 0L,
                amountPaise = typical,
                cadence = cadence,
                occurrences = history.size,
                lastSeenAt = last.timestamp,
                nextExpectedAt = Time.startOfDay(next),
            )
        }

        private fun cadenceFor(gaps: List<Long>): Cadence? {
            val median = Metrics.median(gaps.map { it }).toDouble()
            val consistent = gaps.count { abs(it - median) <= median * GAP_TOLERANCE }
            if (consistent < gaps.size * 0.7) return null
            return when {
                median in 6.0..8.0 -> Cadence.WEEKLY
                median in 27.0..32.0 -> Cadence.MONTHLY
                median in 85.0..95.0 -> Cadence.QUARTERLY
                median in 355.0..375.0 -> Cadence.YEARLY
                else -> null
            }
        }

    }
}
