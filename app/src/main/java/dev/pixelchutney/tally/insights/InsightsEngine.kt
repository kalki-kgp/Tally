package dev.pixelchutney.tally.insights

import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.CategoryTotal
import dev.pixelchutney.tally.data.db.DailyTotal
import dev.pixelchutney.tally.data.db.MerchantTotal
import dev.pixelchutney.tally.data.db.RecurringDao
import dev.pixelchutney.tally.data.db.SessionDao
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.db.TransactionRow
import dev.pixelchutney.tally.data.model.Cadence
import dev.pixelchutney.tally.data.model.Necessity
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class BudgetState(
    val limitPaise: Long,
    val spentPaise: Long,
    val pace: Double,
    val safeToSpendTodayPaise: Long,
) {
    val remainingPaise: Long get() = limitPaise - spentPaise
    val fraction: Float get() = Metrics.share(spentPaise, limitPaise)
    val overspent: Boolean get() = spentPaise > limitPaise
}

data class NecessitySplit(
    val needPaise: Long = 0,
    val wantPaise: Long = 0,
    val unsortedPaise: Long = 0,
) {
    val total: Long get() = needPaise + wantPaise + unsortedPaise
    val wantShare: Float get() = Metrics.share(wantPaise, total)
}

data class HomeSnapshot(
    val todayPaise: Long = 0,
    val todayCount: Int = 0,
    val weekPaise: Long = 0,
    val monthPaise: Long = 0,
    val monthCount: Int = 0,
    val budget: BudgetState? = null,
    val projectionPaise: Long = 0,
    val trimmedProjectionPaise: Long = 0,
    val momDelta: Metrics.Delta = Metrics.Delta(0, null),
    val lastMonthToDatePaise: Long = 0,
    val burnRatePaise: Long = 0,
    val streak: Metrics.Streak = Metrics.Streak(0, 0),
    val topCategories: List<CategoryTotal> = emptyList(),
    val movers: List<Metrics.Mover> = emptyList(),
    val recent: List<TransactionRow> = emptyList(),
    val monthStrokes: List<StrokeDatum> = emptyList(),
    val necessity: NecessitySplit = NecessitySplit(),
    val subscriptionsMonthlyPaise: Long = 0,
    val smallLeakPaise: Long = 0,
    val smallLeakCount: Int = 0,
    val captureRate: Float? = null,
    val medianLogSeconds: Long? = null,
    val dailyThisMonth: List<Long> = emptyList(),
)

data class StrokeDatum(val id: Long, val amountPaise: Long, val colorIndex: Int, val timestamp: Long)

data class Anomaly(
    val kind: Kind,
    val headline: String,
    val detail: String,
    val amountPaise: Long,
) {
    enum class Kind { CATEGORY_SPIKE, BIG_TRANSACTION, NEW_MERCHANT, BUDGET_PACE, LEAK }
}

data class PatternsSnapshot(
    val hourly: List<Long> = List(24) { 0L },
    val hourlyCounts: List<Int> = List(24) { 0 },
    val weekday: List<Long> = List(7) { 0L },
    val weekdayCounts: List<Int> = List(7) { 0 },
    val smallLeakPaise: Long = 0,
    val smallLeakCount: Int = 0,
    val medianTxnPaise: Long = 0,
    val largestPaise: Long = 0,
    val txnPerDay: Double = 0.0,
    val busiestHour: Int? = null,
    val priciestWeekday: Int? = null,
)

@Singleton
class InsightsEngine @Inject constructor(
    private val txns: TransactionDao,
    private val sessions: SessionDao,
    private val recurring: RecurringDao,
) {

    // ── Home ──────────────────────────────────────────────────────────────────

    suspend fun homeSnapshot(
        monthBudgetPaise: Long?,
        smallLeakThresholdPaise: Long,
    ): HomeSnapshot {
        val today = Time.today()
        val dayRange = Time.dayRange(today)
        val weekRange = Time.weekRange(today)
        val mtd = Time.monthToDate(today)
        val lastMonthSame = Time.lastMonthToSameDay(today)
        val fullMonth = Time.monthRange(YearMonth.from(today))
        val lastFullMonth = Time.monthRange(YearMonth.from(today).minusMonths(1))

        val todayPaise = txns.spendBetween(dayRange.first, dayRange.last)
        val todayCount = txns.countBetween(dayRange.first, dayRange.last)
        val weekPaise = txns.spendBetween(weekRange.first, weekRange.last)
        val monthPaise = txns.spendBetween(mtd.first, mtd.last)
        val monthCount = txns.countBetween(mtd.first, mtd.last)
        val lastMonthPaise = txns.spendBetween(lastMonthSame.first, lastMonthSame.last)

        val dailyRows = txns.dailyTotals(fullMonth.first, fullMonth.last)
        val dailyMap = dailyRows.associate { LocalDate.parse(it.day) to it.totalPaise }
        val elapsedDays = today.dayOfMonth
        val dailySeries = (1..elapsedDays).map { day ->
            dailyMap[today.withDayOfMonth(day)] ?: 0L
        }

        val daysInMonth = Time.daysInMonth(today)
        val budget = monthBudgetPaise?.takeIf { it > 0 }?.let { limit ->
            BudgetState(
                limitPaise = limit,
                spentPaise = monthPaise,
                pace = Metrics.pace(monthPaise, limit, elapsedDays, daysInMonth),
                safeToSpendTodayPaise = Metrics.safeToSpendPerDay(
                    limitPaise = limit,
                    spentThisMonthPaise = monthPaise - todayPaise,
                    daysRemaining = Time.daysRemainingInMonth(today),
                ) - todayPaise,
            )
        }

        val thisMonthCats = txns.categoryTotals(mtd.first, mtd.last)
        val lastMonthCats = txns.categoryTotals(lastFullMonth.first, lastFullMonth.last)
        val movers = Metrics.biggestMovers(
            current = thisMonthCats.associate { it.name to it.totalPaise },
            previous = lastMonthCats.associate { it.name to it.totalPaise },
        )

        // 90 days of history so the streak counter has something to say early on.
        val streakWindow = Time.lastNDays(90, today)
        val streakDays = txns.dailyTotals(streakWindow.first, streakWindow.last)
            .associate { LocalDate.parse(it.day) to it.totalPaise }
        val streak = Metrics.noSpendStreak(streakDays, today, today.minusDays(89))

        val burn = Metrics.burnRate(
            txns.dailyTotals(Time.lastNDays(7, today).first, Time.lastNDays(7, today).last)
                .let { rows -> fillDays(rows, today.minusDays(6), today) }
        )

        val split = necessitySplitFor(mtd.first, mtd.last)

        val lags = txns.sortedLogLags(Time.lastNDays(60, today).first, Time.lastNDays(60, today).last)
        val medianLagSeconds = if (lags.isEmpty()) null else Metrics.median(lags) / 1000

        val monthAmounts = txns.sortedAmounts(mtd.first, mtd.last)
        val smallOnes = monthAmounts.filter { it < smallLeakThresholdPaise }

        return HomeSnapshot(
            todayPaise = todayPaise,
            todayCount = todayCount,
            weekPaise = weekPaise,
            monthPaise = monthPaise,
            monthCount = monthCount,
            budget = budget,
            projectionPaise = Metrics.projection(monthPaise, elapsedDays, daysInMonth),
            trimmedProjectionPaise = Metrics.trimmedProjection(dailySeries, daysInMonth),
            momDelta = Metrics.delta(monthPaise, lastMonthPaise),
            lastMonthToDatePaise = lastMonthPaise,
            burnRatePaise = burn,
            streak = streak,
            topCategories = thisMonthCats.take(4),
            movers = movers,
            monthStrokes = txns.rowsBetween(fullMonth.first, fullMonth.last)
                .filter { !it.txn.excluded && it.txn.amountPaise > 0 }
                .map {
                    StrokeDatum(
                        it.txn.id, it.txn.amountPaise, it.categoryColorIndex, it.txn.timestamp
                    )
                },
            necessity = split,
            subscriptionsMonthlyPaise = subscriptionsMonthlyTotal(),
            smallLeakPaise = smallOnes.sum(),
            smallLeakCount = smallOnes.size,
            captureRate = captureRate(Time.lastNDays(30, today)),
            medianLogSeconds = medianLagSeconds,
            dailyThisMonth = dailySeries,
        )
    }

    // ── Patterns ──────────────────────────────────────────────────────────────

    suspend fun patterns(days: Int = 84, thresholdPaise: Long = 20_000L): PatternsSnapshot {
        val today = Time.today()
        val range = Time.lastNDays(days, today)
        val rows = txns.rowsBetween(range.first, range.last)
            .filter { !it.txn.excluded && it.txn.amountPaise > 0 }

        val hourly = LongArray(24)
        val hourlyCounts = IntArray(24)
        val weekday = LongArray(7)
        val weekdayCounts = IntArray(7)

        rows.forEach { row ->
            val dt = Time.toLocalDateTime(row.txn.timestamp)
            hourly[dt.hour] += row.txn.amountPaise
            hourlyCounts[dt.hour]++
            val dow = dt.dayOfWeek.value % 7   // Monday=1 … Sunday=7 → Sunday=0
            weekday[dow] += row.txn.amountPaise
            weekdayCounts[dow]++
        }

        val amounts = rows.map { it.txn.amountPaise }
        val weeks = days / 7.0

        return PatternsSnapshot(
            hourly = hourly.toList(),
            hourlyCounts = hourlyCounts.toList(),
            // Weekday totals are shown as a per-week average, so a 12-week window
            // does not make every bar look enormous.
            weekday = weekday.map { (it / weeks).toLong() },
            weekdayCounts = weekdayCounts.toList(),
            smallLeakPaise = amounts.filter { it < thresholdPaise }.sum(),
            smallLeakCount = amounts.count { it < thresholdPaise },
            medianTxnPaise = Metrics.median(amounts),
            largestPaise = amounts.maxOrNull() ?: 0L,
            txnPerDay = if (days > 0) rows.size.toDouble() / days else 0.0,
            busiestHour = hourlyCounts.withIndex().maxByOrNull { it.value }
                ?.takeIf { it.value > 0 }?.index,
            priciestWeekday = weekday.withIndex().maxByOrNull { it.value }
                ?.takeIf { it.value > 0 }?.index,
        )
    }

    // ── Anomalies ─────────────────────────────────────────────────────────────

    /**
     * Compares this week against the eight before it, per category. Only speaks up
     * when a category is genuinely out of character and the rupees involved matter.
     */
    suspend fun anomalies(minPaise: Long = 50_000L): List<Anomaly> {
        val today = Time.today()
        val thisWeek = Time.weekRange(today)
        val current = txns.categoryTotals(thisWeek.first, thisWeek.last)

        val historyByCategory = mutableMapOf<Long, MutableList<Long>>()
        repeat(8) { weeksBack ->
            val week = Time.weekRange(today.minusWeeks((weeksBack + 1).toLong()))
            txns.categoryTotals(week.first, week.last).forEach { row ->
                historyByCategory.getOrPut(row.categoryId) { mutableListOf() }.add(row.totalPaise)
            }
        }

        val out = mutableListOf<Anomaly>()

        current.filter { it.totalPaise >= minPaise }.forEach { row ->
            val history = historyByCategory[row.categoryId].orEmpty()
            val z = Metrics.zScore(row.totalPaise, history)
            if (z >= 2.0 && history.isNotEmpty()) {
                val typical = Metrics.mean(history)
                val multiple = if (typical > 0) row.totalPaise / typical else 0.0
                out += Anomaly(
                    kind = Anomaly.Kind.CATEGORY_SPIKE,
                    headline = "${row.emoji} ${row.name} is running hot",
                    detail = "This week is %.1f× your usual week for %s."
                        .format(multiple, row.name.lowercase()),
                    amountPaise = row.totalPaise,
                )
            }
        }

        // A transaction is "big" relative to the last 90 days of your own habits.
        val ninety = Time.lastNDays(90, today)
        val medianTxn = Metrics.median(txns.sortedAmounts(ninety.first, ninety.last))
        if (medianTxn > 0) {
            txns.largestTransactions(
                from = thisWeek.first,
                to = thisWeek.last,
                minPaise = medianTxn * 3,
                limit = 2,
            ).forEach { row ->
                out += Anomaly(
                    kind = Anomaly.Kind.BIG_TRANSACTION,
                    headline = row.txn.merchantName ?: row.categoryName,
                    detail = "Well above your typical payment this week.",
                    amountPaise = row.txn.amountPaise,
                )
            }
        }

        return out.sortedByDescending { it.amountPaise }.take(4)
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    suspend fun necessitySplitFor(from: Long, to: Long): NecessitySplit {
        var need = 0L; var want = 0L; var unsorted = 0L
        txns.necessitySplit(from, to).forEach { row ->
            when (row.necessity) {
                Necessity.NEED.name -> need += row.totalPaise
                Necessity.WANT.name -> want += row.totalPaise
                else -> unsorted += row.totalPaise
            }
        }
        return NecessitySplit(need, want, unsorted)
    }

    suspend fun subscriptionsMonthlyTotal(): Long =
        recurring.confirmed().sumOf { rule ->
            when (rule.cadence) {
                Cadence.WEEKLY -> rule.expectedAmountPaise * 52 / 12
                Cadence.MONTHLY -> rule.expectedAmountPaise
                Cadence.QUARTERLY -> rule.expectedAmountPaise / 3
                Cadence.YEARLY -> rule.expectedAmountPaise / 12
            }
        }

    /**
     * Of the payment moments where a prompt went unanswered or was swiped away,
     * how many became a saved entry. Answering "no payment" counts as correct
     * behaviour, not a miss — otherwise checking your balance would look like failure.
     */
    suspend fun captureRate(range: LongRange): Float? {
        val counts = sessions.outcomeCounts(range.first, range.last).associate {
            it.outcome to it.sessionCount
        }
        val logged = counts["LOGGED"] ?: 0
        val missed = (counts["DISMISSED"] ?: 0) + (counts["IGNORED"] ?: 0)
        val denominator = logged + missed
        return if (denominator == 0) null else logged.toFloat() / denominator
    }

    suspend fun monthlyHistory(months: Int): List<Pair<YearMonth, Long>> {
        val thisMonth = YearMonth.from(Time.today())
        return (months - 1 downTo 0).map { back ->
            val ym = thisMonth.minusMonths(back.toLong())
            val range = Time.monthRange(ym)
            ym to txns.spendBetween(range.first, range.last)
        }
    }

    suspend fun necessityHistory(months: Int): List<Pair<YearMonth, NecessitySplit>> {
        val thisMonth = YearMonth.from(Time.today())
        return (months - 1 downTo 0).map { back ->
            val ym = thisMonth.minusMonths(back.toLong())
            val range = Time.monthRange(ym)
            ym to necessitySplitFor(range.first, range.last)
        }
    }

    suspend fun topMerchants(range: LongRange, limit: Int = 12): List<MerchantTotal> =
        txns.topMerchantsBySpend(range.first, range.last, limit)

    /** Expands sparse daily rows into a zero-filled series, one entry per day. */
    fun fillDays(rows: List<DailyTotal>, from: LocalDate, to: LocalDate): List<Long> {
        val map = rows.associate { LocalDate.parse(it.day) to it.totalPaise }
        val out = mutableListOf<Long>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            out += map[cursor] ?: 0L
            cursor = cursor.plusDays(1)
        }
        return out
    }

    fun dailyMap(rows: List<DailyTotal>): Map<LocalDate, DailyTotal> =
        rows.associateBy { LocalDate.parse(it.day) }

    companion object {
        fun percentLabel(delta: Metrics.Delta): String? {
            val pct = delta.percent ?: return null
            val rounded = abs(pct).let { if (it >= 10) it.toInt().toString() else "%.1f".format(it) }
            return (if (delta.absolutePaise >= 0) "+" else "−") + rounded + "%"
        }
    }
}
