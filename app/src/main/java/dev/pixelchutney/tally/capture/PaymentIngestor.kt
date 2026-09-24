package dev.pixelchutney.tally.capture

import android.util.Log
import dev.pixelchutney.tally.ai.CategoryRanker
import dev.pixelchutney.tally.ai.ParsedPayment
import dev.pixelchutney.tally.ai.RankInput
import dev.pixelchutney.tally.ai.Ranking
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.db.TransactionEntity
import dev.pixelchutney.tally.data.model.EntryMethod
import dev.pixelchutney.tally.data.model.Necessity
import dev.pixelchutney.tally.data.repo.TallyRepository
import dev.pixelchutney.tally.data.repo.TransactionDraft
import dev.pixelchutney.tally.data.settings.CaptureMode
import dev.pixelchutney.tally.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a payment notification into a saved, unsorted payment.
 *
 * The amount is written immediately and counts from that moment; only the
 * category waits for the owner. That ordering is the point: the number is the
 * part people forget, and the notification already has it.
 *
 * One payment is usually announced twice — by the UPI app and by the bank's
 * text a few seconds later — so every notification is first checked against
 * what is already saved.
 */
@Singleton
class PaymentIngestor @Inject constructor(
    private val repository: TallyRepository,
    private val txns: TransactionDao,
    private val tracker: SessionTracker,
    private val metadata: MetadataCollector,
    private val ranker: CategoryRanker,
    private val notifier: PromptNotifier,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /**
     * Payments read while their payment app is still open, keyed by visit. The
     * prompt goes out when the visit ends — the same moment it always has — not
     * while the person is still looking at the payment screen.
     */
    private val waitingForExit = mutableMapOf<Long, Pair<Long, Ranking>>()

    init {
        scope.launch {
            tracker.visitsEndedWithPayment.collect { sessionId ->
                mutex.withLock {
                    waitingForExit.remove(sessionId)?.let { (id, ranking) ->
                        runCatching { announce(id, ranking, sessionId) }
                    }
                }
            }
        }
    }

    /**
     * [fromPaymentApp] is true when a UPI app posted it, false for a bank text
     * or a bank app — which says nothing about which payment app was used.
     */
    suspend fun ingest(
        payment: ParsedPayment,
        notifyingPackage: String,
        postedAt: Long,
        fromPaymentApp: Boolean,
    ) = mutex.withLock {
        samePaymentAs(payment, postedAt)?.let { existing ->
            enrich(existing, payment, notifyingPackage)
            return@withLock
        }

        val visit = tracker.claimForPayment(notifyingPackage.takeIf { fromPaymentApp }, postedAt)
        val context = when {
            visit != null -> metadata.consume(visit.sessionId, visit.precededBy)
            // A notification replayed long after the fact: the phone is no longer
            // where the money was spent, so say so rather than sample here.
            Time.now() - postedAt > STALE_MS -> PaymentContext(locationSource = LocationSource.UNAVAILABLE)
            else -> metadata.sample()
        }

        val memory = repository.recall(payment.payee)
        val merchantName = memory.merchant?.canonicalName ?: payment.payee?.let(::tidyPayee)
        val sourceApp = visit?.packageName ?: notifyingPackage.takeIf { fromPaymentApp }
        val input = RankInput(
            amountPaise = payment.amountPaise,
            timestamp = postedAt,
            sourceApp = sourceApp,
            payee = payment.payee,
            merchantName = merchantName,
            note = null,
            context = context,
        )

        // Saved on the on-device guess first, so nothing waits on the network.
        val local = ranker.rank(input, allowModel = false)
        val id = repository.save(
            TransactionDraft(
                amountPaise = payment.amountPaise,
                categoryId = local.top.id,
                merchantName = merchantName,
                note = null,
                timestamp = postedAt,
                necessity = memory.suggestedNecessity ?: local.top.defaultNecessity,
                sourceApp = sourceApp,
                entryMethod = EntryMethod.AUTO_PARSED,
                sessionId = visit?.sessionId,
                context = context,
                reviewed = false,
                payee = payment.payee,
                upiRef = payment.upiRef,
                detectedFrom = notifyingPackage,
            )
        )
        sourceApp?.let { tracker.recordLogged(it) }
        Log.d(TAG, "logged ${payment.amountPaise} from $notifyingPackage")

        val sessionId = visit?.sessionId
        if (sessionId != null && visit.inProgress && tracker.isInProgress(sessionId)) {
            waitingForExit[sessionId] = id to local
        } else {
            announce(id, local, sessionId)
        }
        scope.launch { runCatching { refine(id, input.copy(excludeTxnId = id), sessionId) } }
    }

    /** Haiku's pass, after the row is safe. Only ever improves a guess. */
    private suspend fun refine(id: Long, input: RankInput, sessionId: Long?) {
        val ranking = ranker.rank(input, allowModel = true)
        if (!ranking.fromModel) return
        val txn = txns.byId(id) ?: return
        if (txn.reviewed) return
        txns.update(
            txn.copy(
                categoryId = ranking.top.id,
                merchantName = txn.merchantName?.takeIf { repository.recall(it).merchant != null }
                    ?: ranking.merchant
                    ?: txn.merchantName,
                necessity = ranking.necessity ?: ranking.top.defaultNecessity.takeIf { it != Necessity.UNSORTED }
                    ?: txn.necessity,
            )
        )
        mutex.withLock {
            // Still in the payment app: better buttons for when the prompt goes out.
            if (sessionId != null && waitingForExit.containsKey(sessionId)) {
                waitingForExit[sessionId] = id to ranking
            } else {
                announce(id, ranking, sessionId)
            }
        }
    }

    /**
     * The prompt, with the amount already in it. Asking-now mode shows it as the
     * usual heads-up, in the visit's own notification; sort-later keeps it quiet.
     * Re-posting it (after Haiku re-ranks) never buzzes twice.
     */
    private suspend fun announce(id: Long, ranking: Ranking, sessionId: Long?) {
        val txn = txns.byId(id) ?: return
        if (txn.reviewed) return
        val config = settings.current()
        val loud = config.captureMode == CaptureMode.ASK_NOW && config.promptsEnabled
        notifier.showLogged(
            transactionId = id,
            amountPaise = txn.amountPaise,
            merchant = txn.merchantName,
            ranked = ranking.categories,
            waiting = txns.unsortedCount(),
            loud = loud,
            sessionId = sessionId.takeIf { loud },
        )
    }

    /**
     * The same payment, already saved. A matching UPI reference settles it. Short
     * of that, the same amount within a few minutes — unless both carry
     * references and they differ, which makes them two payments.
     */
    private suspend fun samePaymentAs(payment: ParsedPayment, at: Long): TransactionEntity? {
        payment.upiRef?.let { ref -> txns.byUpiRef(ref)?.let { return it } }
        return txns.sameAmountNear(payment.amountPaise, at - DUPLICATE_WINDOW_MS, at + DUPLICATE_WINDOW_MS, at)
            .firstOrNull { it.upiRef == null || payment.upiRef == null }
    }

    /** The second notification often knows what the first did not. */
    private suspend fun enrich(existing: TransactionEntity, payment: ParsedPayment, from: String) {
        val updated = existing.copy(
            payee = existing.payee ?: payment.payee,
            upiRef = existing.upiRef ?: payment.upiRef,
            detectedFrom = existing.detectedFrom ?: from,
            merchantName = existing.merchantName
                ?: payment.payee?.takeIf { !existing.reviewed }?.let(::tidyPayee),
        )
        if (updated != existing) txns.update(updated)
    }

    companion object {
        private const val TAG = "PaymentIngestor"
        private const val DUPLICATE_WINDOW_MS = 10 * 60_000L
        private const val STALE_MS = 10 * 60_000L

        private val PSP_WORDS = setOf(
            "rzp", "razorpay", "payu", "cashfree", "cf", "paytm", "gpay", "phonepe", "ybl", "ibl",
            "axl", "okaxis", "okicici", "okhdfcbank", "oksbi", "upi", "pay", "payments", "mer",
            "merchant", "qr", "bharatpe", "pos", "store",
        )

        /**
         * "blinkit.rzp@axisbank" → "Blinkit", "RAJU TEA STALL" → "Raju Tea Stall".
         * A UPI ID that is mostly digits names nobody; null is more honest.
         */
        fun tidyPayee(raw: String): String? {
            val base = raw.substringBefore('@').trim()
            if (base.count { it.isDigit() } > 4) return null
            val words = base.split(Regex("[^A-Za-z]+"))
                .filter { it.length > 1 && it.lowercase() !in PSP_WORDS }
            if (words.isEmpty()) return null
            return words.joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.titlecase(Locale.ENGLISH) }
            }
        }
    }
}
