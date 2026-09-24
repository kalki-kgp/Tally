package dev.pixelchutney.tally.ai

import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.AiCacheEntity
import dev.pixelchutney.tally.data.db.AiDao
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A payment read out of a notification. */
data class ParsedPayment(
    val amountPaise: Long,
    /** Who the money went to, as the notification wrote it: a name or a UPI ID. */
    val payee: String?,
    /** The UPI / bank reference, when given. Tells one payment seen twice from two. */
    val upiRef: String?,
)

sealed interface Extraction {
    data class Payment(val payment: ParsedPayment) : Extraction
    /**
     * Money came in. Paying yourself shows up this way — Navi announces the
     * receiving side — so the amount is still worth offering, never saving.
     */
    data class Incoming(val payment: ParsedPayment) : Extraction
    /** Read, and it is not money going out: a credit, OTP, offer, reminder… */
    data object NotAPayment : Extraction
    /** Could not ask right now — offline, rate limited. Worth trying again. */
    data class Unavailable(val reason: String) : Extraction
    /** AI is off, no key, or over the monthly cap. Retrying will not help. */
    data class Blocked(val reason: String) : Extraction
}

/**
 * Reads a payment out of a notification with Haiku, instead of with patterns.
 *
 * Every bank words its texts differently — "Debited Rs:237.00", "A transaction
 * of Rs. 138.43 was made using your card", "your payment for INR 138.43 … is
 * successful" — and each new one broke a hand-written parser. A model reads all
 * of them the way a person would, including the cases that must be refused:
 * money coming in, OTPs, requests, failures, reminders, offers.
 *
 * The reply is structured output, so it always parses. The amount comes back
 * as the rupee figure written in the notification and is converted to paise
 * here — the model is never asked to do arithmetic. Answers are cached by the
 * notification's text, so the same notification posted twice costs one call.
 */
@Singleton
class PaymentExtractor @Inject constructor(
    private val claude: ClaudeClient,
    private val aiDao: AiDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun extract(sourceApp: String, title: String, body: String): Extraction {
        val prompt = buildString {
            appendLine("Payment app that posted it: $sourceApp")
            appendLine("Title: ${title.ifBlank { "(none)" }}")
            appendLine("Text: ${body.ifBlank { "(none)" }}")
        }
        val cacheKey = "pay2:" + sha1(prompt)
        aiDao.cached(cacheKey)?.response?.let { cached -> read(cached)?.let { return it } }

        val result = claude.send(
            model = Models.FAST,
            system = SYSTEM,
            messages = listOf(userMessage(prompt)),
            maxTokens = 300,
            jsonSchema = SCHEMA,
        )
        val ok = when (result) {
            is ClaudeResult.Ok -> result
            is ClaudeResult.Failed -> return Extraction.Unavailable(result.message)
            ClaudeResult.NoKey -> return Extraction.Blocked("No API key in Settings")
            ClaudeResult.Disabled -> return Extraction.Blocked("AI is switched off in Settings")
            is ClaudeResult.OverBudget -> return Extraction.Blocked("This month's AI budget is used up")
        }
        if (ok.response.stop_reason != "end_turn") return Extraction.Unavailable("Incomplete answer")

        val text = ok.response.text
        val extraction = read(text) ?: return Extraction.Unavailable("Unreadable answer")
        aiDao.cache(AiCacheEntity(cacheKey = cacheKey, response = text, model = Models.FAST, createdAt = Time.now()))
        return extraction
    }

    private fun read(reply: String): Extraction? {
        val obj = runCatching { json.parseToJsonElement(reply).jsonObject }.getOrNull() ?: return null
        fun field(name: String) = obj[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val direction = field("direction") ?: return null
        if (direction == "none") return Extraction.NotAPayment
        val paise = field("amount")?.let(Money::fromRupeeString)?.takeIf { it > 0 }
            ?: return Extraction.NotAPayment
        val payment = ParsedPayment(amountPaise = paise, payee = field("payee"), upiRef = field("reference"))
        return if (direction == "outgoing") Extraction.Payment(payment) else Extraction.Incoming(payment)
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SYSTEM =
            "You read one notification posted by an Indian UPI payment app and decide whether it " +
                "reports a completed transaction. direction is \"outgoing\" when money has just " +
                "left the owner (paid, sent, debited, spent), \"incoming\" when money has just " +
                "arrived (received, credited, deposited, refunded), and \"none\" for everything " +
                "else: OTPs, payment or collect requests, failed, declined, pending or reversed " +
                "payments, future or scheduled debits, reminders, balance updates, offers and ads. " +
                "For a transaction, give the amount exactly as written in rupees (digits and at " +
                "most one decimal point, no symbols or commas), the other party as written (a name " +
                "or UPI ID), and the UPI reference number if one is given. Use empty strings for " +
                "anything not stated."

        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("direction") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("outgoing", "incoming", "none").forEach { add(JsonPrimitive(it)) } }
                }
                putJsonObject("amount") { put("type", "string") }
                putJsonObject("payee") { put("type", "string") }
                putJsonObject("reference") { put("type", "string") }
            }
            putJsonArray("required") {
                listOf("direction", "amount", "payee", "reference").forEach { add(JsonPrimitive(it)) }
            }
            put("additionalProperties", false)
        }
    }
}
