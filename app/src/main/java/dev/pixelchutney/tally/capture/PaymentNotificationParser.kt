package dev.pixelchutney.tally.capture

/** What a payment notification says, once the wording is stripped away. */
data class ParsedPayment(
    val amountPaise: Long,
    /** Who the money went to, as the notification wrote it: a name or a UPI ID. */
    val payee: String?,
    /** The 12-digit UPI reference, when given. The only reliable way to tell two
     *  notifications about one payment from two payments of the same amount. */
    val upiRef: String?,
)

/**
 * Reads an amount out of a UPI app notification or a bank SMS.
 *
 * Only money going *out* is read. Credits, refunds, requests, reminders, OTPs and
 * failed payments are all refused, because every one of them names an amount
 * without that amount having been spent. A missed notification costs a prompt; a
 * wrongly accepted one puts money in the ledger that was never paid, which is the
 * worse error.
 *
 * Pure text in, result out, so every format seen in the wild can live in a test.
 */
object PaymentNotificationParser {

    fun parse(text: String): ParsedPayment? {
        val body = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (body.isEmpty()) return null
        if (REJECT.containsMatchIn(body)) return null

        val debit = DEBIT.containsMatchIn(body)
        // "Rs 450 debited … Blinkit credited" is a debit. A credit word only
        // disqualifies a message that has no debit word of its own.
        if (!debit) return null
        if (!DEBIT_STRONG.containsMatchIn(body) && CREDIT.containsMatchIn(body)) return null

        val amount = amountIn(body) ?: return null
        if (amount <= 0 || amount > MAX_PAISE) return null

        return ParsedPayment(
            amountPaise = amount,
            payee = payeeIn(body),
            upiRef = UPI_REF.find(body)?.groupValues?.get(1),
        )
    }

    /**
     * A bank SMS arrives through the messaging app, next to every other text
     * message. It has to look like a bank wrote it before it is read at all, or a
     * friend writing "paid ₹500 for the cab" would land in the ledger.
     */
    fun looksLikeBankMessage(text: String): Boolean = BANKISH.containsMatchIn(text)

    /**
     * Banks text from sender IDs — "AX-HDFCBK", "VM-SBIUPI-S", "ICICIT" — never
     * from a contact name or a phone number. A saved contact called "Rahul
     * Sharma" or "+91 98450 12345" fails this, which is the point.
     */
    fun looksLikeBankSender(title: String): Boolean {
        val sender = title.trim()
        return SENDER_ID.matches(sender) && sender.any { it.isLetter() } &&
            sender.none { it.isLowerCase() }
    }

    private fun amountIn(body: String): Long? {
        val match = CURRENCY_AMOUNT.find(body) ?: BARE_DEBIT_AMOUNT.find(body) ?: return null
        return toPaise(match.groupValues[1])
    }

    internal fun toPaise(raw: String): Long? {
        val cleaned = raw.replace(",", "")
        val parts = cleaned.split('.')
        val rupees = parts[0].toLongOrNull() ?: return null
        val paise = parts.getOrNull(1)?.take(2)?.padEnd(2, '0')?.toLongOrNull() ?: 0L
        return rupees * 100 + paise
    }

    private fun payeeIn(body: String): String? {
        VPA.find(body)?.let { return it.groupValues[1].lowercase() }
        for (pattern in PAYEE_PATTERNS) {
            val raw = pattern.find(body)?.groupValues?.get(1) ?: continue
            val cleaned = raw.trim().trimEnd('.', ',', ';', ':', '-').trim()
            if (cleaned.length < 2) continue
            if (NOT_A_PAYEE.matches(cleaned)) continue
            return cleaned
        }
        return null
    }

    private val SENDER_ID = Regex("[A-Za-z0-9]{2}-[A-Za-z0-9]{3,10}(?:-[A-Za-z])?|[A-Za-z]{5,10}")

    private const val MAX_PAISE = 10_00_000_00L // ₹10 lakh

    private val OPTS = setOf(RegexOption.IGNORE_CASE)

    private val REJECT = Regex(
        listOf(
            "\\bOTP\\b", "one[- ]time password", "verification code", "do not share",
            "\\brequest(?:ed|ing)?\\b", "collect request",
            "\\bfail(?:ed|ure)?\\b", "declined", "unsuccessful", "\\breversed\\b",
            "\\bdue (?:on|by|date)\\b", "\\breminder\\b", "\\bwill be debited\\b",
            "\\bauto ?pay (?:set ?up|mandate)\\b", "\\bmandate (?:created|registered)\\b",
            "\\bpre-?approved\\b", "\\bget (?:up ?to|flat)\\b", "\\bwin\\b",
        ).joinToString("|"),
        OPTS,
    )

    private val DEBIT_STRONG = Regex("\\bdebited\\b|\\bspent\\b|\\bwithdrawn\\b|\\bsent\\b", OPTS)
    private val DEBIT = Regex(
        "\\bdebited\\b|\\bpaid\\b|\\bsent\\b|\\bspent\\b|\\bwithdrawn\\b|\\btransferred\\b|" +
            "\\bpayment (?:of .{1,20} )?(?:is )?successful\\b|\\bpayment to\\b|\\bpurchase\\b",
        OPTS,
    )
    private val CREDIT = Regex("\\bcredited\\b|\\breceived\\b|\\brefund(?:ed)?\\b|\\bcashback\\b", OPTS)

    private val BANKISH = Regex(
        "\\ba/?c\\b|\\bacct\\b|\\baccount\\b|\\bUPI\\b|\\bcard\\b|\\bbank\\b|\\bVPA\\b|\\bIMPS\\b|\\bNEFT\\b",
        OPTS,
    )

    /** ₹450, Rs.450, Rs 1,250.00, INR 99 — the first one in a message is the payment. */
    private val CURRENCY_AMOUNT = Regex(
        "(?:₹|\\brs\\.?|\\binr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        OPTS,
    )

    /** SBI writes "debited by 450.0" with no currency at all. */
    private val BARE_DEBIT_AMOUNT = Regex(
        "debited (?:by|for|with)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        OPTS,
    )

    private val VPA = Regex("\\b([a-z0-9][a-z0-9._-]{1,63}@[a-z][a-z0-9]{1,31})\\b", OPTS)

    private val UPI_REF = Regex("(?<![0-9])([0-9]{12})(?![0-9])")

    private const val STOP =
        "(?=\\s+on\\b|\\s+via\\b|\\s+using\\b|\\s+ref(?:no)?\\b|\\s+upi\\b|\\s+from\\b|\\s+at\\s+\\d|" +
            "\\s+avl\\b|\\s+avail|\\s+info\\b|[.;,(]|\\s+-|$)"

    private val PAYEE_PATTERNS = listOf(
        // "…; Blinkit credited." — ICICI puts the payee before the word.
        Regex(";\\s*([A-Za-z][A-Za-z0-9 &'./-]{1,40}?)\\s+credited", OPTS),
        Regex("\\b(?:paid|sent|payment|transferred)\\s+(?:.{0,24}?\\s)?to\\s+([A-Za-z0-9][A-Za-z0-9 &'./-]{1,40}?)$STOP", OPTS),
        Regex("\\btrf to\\s+([A-Za-z0-9][A-Za-z0-9 &'./-]{1,40}?)$STOP", OPTS),
        Regex("\\bto\\s+([A-Za-z0-9][A-Za-z0-9 &'./-]{1,40}?)$STOP", OPTS),
        Regex("\\bat\\s+([A-Za-z][A-Za-z0-9 &'./-]{1,40}?)$STOP", OPTS),
    )

    /** What the loose "to …" pattern catches when a message has no payee at all. */
    private val NOT_A_PAYEE = Regex(
        "(?:your|you|a/?c|acct|account|the|bank|vpa|upi|mobile|beneficiary)(?:\\b.*)?",
        OPTS,
    )
}
