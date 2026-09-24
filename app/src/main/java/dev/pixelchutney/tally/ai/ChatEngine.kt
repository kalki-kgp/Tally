package dev.pixelchutney.tally.ai

import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.insights.InsightsEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class ChatTurn(val fromUser: Boolean, val text: String, val toolsUsed: List<String> = emptyList())

/**
 * "Ask" gives Claude query tools rather than the transaction history.
 *
 * The queries run here, on the device; only their results are sent. That keeps
 * years of spending off the wire, and — the reason that actually matters day to
 * day — means totals are computed by SQLite instead of estimated by a model.
 */
@Singleton
class ChatEngine @Inject constructor(
    private val claude: ClaudeClient,
    private val txns: TransactionDao,
    private val insights: InsightsEngine,
) {

    private val systemPrompt = """
        You are the analysis side of Tally, a private expense tracker on the user's phone.
        You are looking at one person's own spending, in Indian rupees.

        Use the tools for every number. Never estimate a total you could query.
        Answer in two or three sentences unless asked for more, cite the actual
        figures, and skip preamble. If a number looks bad, say so plainly — the
        user built this app to see where the money is going, not to be reassured.
        Today is ${Time.today()}.
    """.trimIndent()

    private val tools: JsonArray = buildJsonArray {
        add(
            tool(
                name = "sum_spending",
                description = "Total spending in a date range, optionally filtered to one category or merchant.",
                properties = buildJsonObject {
                    putJsonObject("from") { put("type", "string"); put("description", "Start date, YYYY-MM-DD, inclusive") }
                    putJsonObject("to") { put("type", "string"); put("description", "End date, YYYY-MM-DD, inclusive") }
                    putJsonObject("category") { put("type", "string"); put("description", "Optional category name") }
                    putJsonObject("merchant") { put("type", "string"); put("description", "Optional merchant name") }
                },
                required = listOf("from", "to"),
            )
        )
        add(
            tool(
                name = "category_breakdown",
                description = "Spending grouped by category for a date range, largest first.",
                properties = buildJsonObject {
                    putJsonObject("from") { put("type", "string") }
                    putJsonObject("to") { put("type", "string") }
                },
                required = listOf("from", "to"),
            )
        )
        add(
            tool(
                name = "top_merchants",
                description = "Merchants ranked by total spent in a date range, with visit counts.",
                properties = buildJsonObject {
                    putJsonObject("from") { put("type", "string") }
                    putJsonObject("to") { put("type", "string") }
                    putJsonObject("limit") { put("type", "integer") }
                },
                required = listOf("from", "to"),
            )
        )
        add(
            tool(
                name = "list_transactions",
                description = "Individual transactions in a date range, newest first.",
                properties = buildJsonObject {
                    putJsonObject("from") { put("type", "string") }
                    putJsonObject("to") { put("type", "string") }
                    putJsonObject("merchant") { put("type", "string") }
                    putJsonObject("limit") { put("type", "integer") }
                },
                required = listOf("from", "to"),
            )
        )
        add(
            tool(
                name = "compare_periods",
                description = "Totals for two date ranges side by side, with the difference.",
                properties = buildJsonObject {
                    putJsonObject("a_from") { put("type", "string") }
                    putJsonObject("a_to") { put("type", "string") }
                    putJsonObject("b_from") { put("type", "string") }
                    putJsonObject("b_to") { put("type", "string") }
                },
                required = listOf("a_from", "a_to", "b_from", "b_to"),
            )
        )
        add(
            tool(
                name = "spending_patterns",
                description = "Time-of-day and day-of-week spending patterns, small-payment totals, and median transaction size over the last 12 weeks.",
                properties = buildJsonObject {},
                required = emptyList(),
            )
        )
    }

    private fun tool(
        name: String,
        description: String,
        properties: JsonObject,
        required: List<String>,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("input_schema") {
            put("type", "object")
            put("properties", properties)
            putJsonArray("required") { required.forEach { add(it) } }
        }
    }

    suspend fun ask(history: List<ChatTurn>, question: String): Result<ChatTurn> {
        val messages = mutableListOf<JsonObject>()
        history.takeLast(8).forEach { turn ->
            messages += if (turn.fromUser) userMessage(turn.text) else assistantMessage(turn.text)
        }
        messages += userMessage(question)

        val used = mutableListOf<String>()

        repeat(MAX_TOOL_ROUNDS) {
            when (val result = claude.send(
                model = Models.SMART,
                system = systemPrompt,
                messages = messages,
                tools = tools,
                maxTokens = 1600,
            )) {
                is ClaudeResult.Ok -> {
                    val response = result.response
                    if (response.toolCalls.isEmpty()) {
                        return Result.success(ChatTurn(false, response.text, used.distinct()))
                    }

                    // Verbatim, including any block types this client does not model.
                    messages += buildJsonObject {
                        put("role", "assistant")
                        put("content", result.rawContent)
                    }

                    val results = response.toolCalls.map { call ->
                        used += call.name.orEmpty()
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", call.id.orEmpty())
                            put("content", runTool(call.name.orEmpty(), call.input))
                        }
                    }
                    messages += buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray { results.forEach { add(it) } })
                    }
                }
                is ClaudeResult.Failed -> return Result.failure(IllegalStateException(result.message))
                ClaudeResult.NoKey -> return Result.failure(
                    IllegalStateException("Add your Claude API key in Settings to use Ask.")
                )
                ClaudeResult.Disabled -> return Result.failure(
                    IllegalStateException("Turn on AI features in Settings to use Ask.")
                )
                is ClaudeResult.OverBudget -> return Result.failure(
                    IllegalStateException(
                        "This month's API budget of ${Money.format(result.capPaise)} is used up. " +
                            "Raise it in Settings if you want to keep going."
                    )
                )
            }
        }
        return Result.failure(IllegalStateException("Gave up after $MAX_TOOL_ROUNDS rounds of lookups."))
    }

    private suspend fun runTool(name: String, input: JsonObject?): String {
        fun arg(key: String): String? =
            input?.get(key)?.jsonPrimitive?.let { runCatching { it.content }.getOrNull() }
                ?.takeIf { it.isNotBlank() && it != "null" }

        fun range(fromKey: String, toKey: String): LongRange? {
            val from = arg(fromKey)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val to = arg(toKey)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            return Time.startOfDay(from)..(Time.startOfDay(to.plusDays(1)) - 1)
        }

        return when (name) {
            "sum_spending" -> {
                val r = range("from", "to") ?: return "Invalid dates."
                val category = arg("category")
                val merchant = arg("merchant")
                when {
                    merchant != null -> {
                        val rows = txns.rowsBetween(r.first, r.last)
                            .filter { it.txn.merchantName?.contains(merchant, true) == true }
                            .filter { !it.txn.excluded && it.txn.amountPaise > 0 }
                        "Total ${Money.format(rows.sumOf { it.txn.amountPaise })} across ${rows.size} payments to merchants matching \"$merchant\"."
                    }
                    category != null -> {
                        val row = txns.categoryTotals(r.first, r.last)
                            .firstOrNull { it.name.equals(category, true) }
                        if (row == null) "No spending in category \"$category\" for that range."
                        else "${row.name}: ${Money.format(row.totalPaise)} across ${row.txnCount} payments."
                    }
                    else -> {
                        val total = txns.spendBetween(r.first, r.last)
                        val count = txns.countBetween(r.first, r.last)
                        "Total ${Money.format(total)} across $count payments."
                    }
                }
            }

            "category_breakdown" -> {
                val r = range("from", "to") ?: return "Invalid dates."
                val rows = txns.categoryTotals(r.first, r.last)
                if (rows.isEmpty()) "No spending in that range."
                else rows.joinToString("\n") {
                    "${it.name}: ${Money.format(it.totalPaise)} (${it.txnCount} payments)"
                }
            }

            "top_merchants" -> {
                val r = range("from", "to") ?: return "Invalid dates."
                val limit = arg("limit")?.toIntOrNull() ?: 10
                val rows = txns.topMerchantsBySpend(r.first, r.last, limit.coerceIn(1, 30))
                if (rows.isEmpty()) "No merchants in that range."
                else rows.joinToString("\n") {
                    "${it.merchantName}: ${Money.format(it.totalPaise)} over ${it.txnCount} payments"
                }
            }

            "list_transactions" -> {
                val r = range("from", "to") ?: return "Invalid dates."
                val merchant = arg("merchant")
                val limit = arg("limit")?.toIntOrNull() ?: 25
                val rows = txns.rowsBetween(r.first, r.last)
                    .filter { merchant == null || it.txn.merchantName?.contains(merchant, true) == true }
                    .sortedByDescending { it.txn.timestamp }
                    .take(limit.coerceIn(1, 60))
                if (rows.isEmpty()) "No transactions found."
                else rows.joinToString("\n") { row ->
                    val date = Time.toLocalDate(row.txn.timestamp)
                    "$date  ${Money.format(row.txn.amountPaise)}  ${row.txn.merchantName ?: "—"}  [${row.categoryName}]"
                }
            }

            "compare_periods" -> {
                val a = range("a_from", "a_to") ?: return "Invalid dates."
                val b = range("b_from", "b_to") ?: return "Invalid dates."
                val totalA = txns.spendBetween(a.first, a.last)
                val totalB = txns.spendBetween(b.first, b.last)
                val diff = totalA - totalB
                val pct = if (totalB == 0L) "n/a" else "%.1f%%".format(diff.toDouble() / totalB * 100)
                "Period A: ${Money.format(totalA)}\nPeriod B: ${Money.format(totalB)}\n" +
                    "Difference: ${Money.format(diff)} ($pct)"
            }

            "spending_patterns" -> {
                val p = insights.patterns()
                val busiest = p.busiestHour?.let { "${it}:00" } ?: "n/a"
                val days = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
                val priciest = p.priciestWeekday?.let { days[it] } ?: "n/a"
                """
                    Last 12 weeks:
                    Median payment: ${Money.format(p.medianTxnPaise)}
                    Largest payment: ${Money.format(p.largestPaise)}
                    Payments per day: ${"%.1f".format(p.txnPerDay)}
                    Busiest hour: $busiest
                    Most expensive weekday on average: $priciest
                    Payments under ₹200: ${p.smallLeakCount}, totalling ${Money.format(p.smallLeakPaise)}
                """.trimIndent()
            }

            else -> "Unknown tool: $name"
        }
    }

    private companion object {
        const val MAX_TOOL_ROUNDS = 5
    }
}
