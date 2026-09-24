package dev.pixelchutney.tally.ai

import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.AiDao
import dev.pixelchutney.tally.data.db.InsightEntity
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.repo.TallyRepository
import dev.pixelchutney.tally.insights.InsightsEngine
import dev.pixelchutney.tally.insights.Metrics
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the weekly and monthly reviews.
 *
 * Only aggregates leave the device — category totals, top merchants, anomalies.
 * The individual transactions never do, and the digest is just as useful without
 * them.
 */
@Singleton
class DigestGenerator @Inject constructor(
    private val claude: ClaudeClient,
    private val insights: InsightsEngine,
    private val txns: TransactionDao,
    private val repository: TallyRepository,
    private val aiDao: AiDao,
) {

    suspend fun weekly(): InsightEntity? {
        val today = Time.today()
        val thisWeek = Time.weekRange(today.minusWeeks(1))   // the week just finished
        val priorWeek = Time.weekRange(today.minusWeeks(2))

        val total = txns.spendBetween(thisWeek.first, thisWeek.last)
        if (total == 0L) return null

        val brief = buildBrief(
            label = "week",
            range = thisWeek,
            priorRange = priorWeek,
        )

        val prompt = """
            $brief

            Write the weekly review. Rules:
            - At most 5 bullet points, each one line.
            - Lead with what changed, not what stayed the same.
            - Quote real rupee figures from the data above.
            - End with exactly one concrete suggestion for the coming week.
            - No praise, no filler, no "great job". Plain and direct.
        """.trimIndent()

        return generate(
            kind = KIND_WEEKLY,
            title = "Week of ${Time.toLocalDate(thisWeek.first)}",
            prompt = prompt,
            range = thisWeek,
        )
    }

    suspend fun monthly(): InsightEntity? {
        val lastMonth = YearMonth.from(Time.today()).minusMonths(1)
        val range = Time.monthRange(lastMonth)
        val prior = Time.monthRange(lastMonth.minusMonths(1))

        val total = txns.spendBetween(range.first, range.last)
        if (total == 0L) return null

        val brief = buildBrief("month", range, prior)

        val prompt = """
            $brief

            Write the monthly review in two parts:

            "What happened" — at most 4 lines on where the month actually went,
            with figures.

            "Three things to cut" — exactly three, each naming the category or
            merchant, the rupees involved, and a realistic monthly saving. Only
            suggest cuts the data supports. Do not suggest cutting essentials.

            Plain sentences, no headings beyond the two above, no encouragement.
        """.trimIndent()

        return generate(
            kind = KIND_MONTHLY,
            title = Time.formatMonth(lastMonth),
            prompt = prompt,
            range = range,
        )
    }

    private suspend fun buildBrief(label: String, range: LongRange, priorRange: LongRange): String {
        val total = txns.spendBetween(range.first, range.last)
        val priorTotal = txns.spendBetween(priorRange.first, priorRange.last)
        val count = txns.countBetween(range.first, range.last)

        val categories = txns.categoryTotals(range.first, range.last)
        val priorCategories = txns.categoryTotals(priorRange.first, priorRange.last)
        val movers = Metrics.biggestMovers(
            categories.associate { it.name to it.totalPaise },
            priorCategories.associate { it.name to it.totalPaise },
            limit = 4,
        )

        val merchants = txns.topMerchantsBySpend(range.first, range.last, 6)
        val split = insights.necessitySplitFor(range.first, range.last)
        val budget = repository.overallBudget()
        val anomalies = insights.anomalies()
        val subscriptions = insights.subscriptionsMonthlyTotal()

        return buildString {
            appendLine("Spending review for one person, in Indian rupees. This is their own data.")
            appendLine()
            appendLine("This $label: ${Money.format(total)} across $count payments.")
            appendLine("Previous $label: ${Money.format(priorTotal)}.")
            budget?.let { appendLine("Monthly budget: ${Money.format(it.monthlyLimitPaise)}.") }
            appendLine()
            appendLine("By category:")
            categories.forEach { appendLine("  ${it.name}: ${Money.format(it.totalPaise)} (${it.txnCount} payments)") }
            appendLine()
            if (movers.isNotEmpty()) {
                appendLine("Biggest changes vs previous $label:")
                movers.forEach {
                    val direction = if (it.changePaise >= 0) "up" else "down"
                    appendLine("  ${it.label} $direction ${Money.format(kotlin.math.abs(it.changePaise))}")
                }
                appendLine()
            }
            appendLine("Top merchants:")
            merchants.forEach { appendLine("  ${it.merchantName}: ${Money.format(it.totalPaise)} over ${it.txnCount} visits") }
            appendLine()
            appendLine("Needs vs wants: needs ${Money.format(split.needPaise)}, wants ${Money.format(split.wantPaise)}, unsorted ${Money.format(split.unsortedPaise)}.")
            if (subscriptions > 0) appendLine("Confirmed subscriptions: ${Money.format(subscriptions)} per month.")
            if (anomalies.isNotEmpty()) {
                appendLine()
                appendLine("Flagged as unusual:")
                anomalies.forEach { appendLine("  ${it.headline} — ${it.detail}") }
            }
        }
    }

    private suspend fun generate(
        kind: String,
        title: String,
        prompt: String,
        range: LongRange,
    ): InsightEntity? {
        val result = claude.send(
            model = Models.SMART,
            system = "You review one person's own spending for them. Be specific, use their figures, and never pad.",
            messages = listOf(userMessage(prompt)),
            maxTokens = 900,
        )
        val text = (result as? ClaudeResult.Ok)?.response?.text?.takeIf { it.isNotBlank() } ?: return null

        val insight = InsightEntity(
            kind = kind,
            title = title,
            body = text,
            createdAt = Time.now(),
            periodStart = range.first,
            periodEnd = range.last,
            model = Models.SMART,
        )
        val id = aiDao.insertInsight(insight)
        return insight.copy(id = id)
    }

    companion object {
        const val KIND_WEEKLY = "WEEKLY_DIGEST"
        const val KIND_MONTHLY = "MONTHLY_REVIEW"
    }
}
