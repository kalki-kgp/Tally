package dev.pixelchutney.tally.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.CategoryDao
import dev.pixelchutney.tally.data.db.CategoryEntity
import dev.pixelchutney.tally.data.db.Seed
import dev.pixelchutney.tally.data.db.SessionDao
import dev.pixelchutney.tally.data.model.EntryMethod
import dev.pixelchutney.tally.data.model.Necessity
import dev.pixelchutney.tally.data.model.SessionOutcome
import dev.pixelchutney.tally.data.repo.TallyRepository
import dev.pixelchutney.tally.data.repo.TransactionDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Runs the notification flow: amount, then category, then the optional note.
 *
 * Each step re-posts the same notification. That matters beyond tidiness — after a
 * typed reply Android shows a spinner on the action until the notification is
 * updated, so a step that failed to re-post would look like it had hung.
 */
@AndroidEntryPoint
class CaptureActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: TallyRepository
    @Inject lateinit var sessions: SessionDao
    @Inject lateinit var categories: CategoryDao
    @Inject lateinit var watchedApps: dev.pixelchutney.tally.data.db.WatchedAppDao
    @Inject lateinit var tracker: SessionTracker
    @Inject lateinit var notifier: PromptNotifier
    @Inject lateinit var metadata: MetadataCollector
    @Inject lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        // Payments read from a notification are keyed by transaction, not visit.
        if (intent.action == PromptNotifier.ACTION_SORT || intent.action == PromptNotifier.ACTION_SORT_NOTE) {
            val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, -1L).takeIf { it > 0 } ?: return
            val pending = goAsync()
            scope.launch {
                try {
                    if (intent.action == PromptNotifier.ACTION_SORT) onSort(intent, transactionId)
                    else onSortNote(intent, transactionId)
                } finally {
                    pending.finish()
                }
            }
            return
        }

        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L).takeIf { it > 0 } ?: return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val action = intent.action ?: return
        val pending = goAsync()

        scope.launch {
            try {
                when (action) {
                    PromptNotifier.ACTION_AMOUNT -> onAmount(intent, sessionId, packageName)
                    PromptNotifier.ACTION_CATEGORY -> onCategory(intent, sessionId, packageName)
                    PromptNotifier.ACTION_NOTE -> onNote(intent, sessionId)
                    PromptNotifier.ACTION_UNDO -> onUndo(intent, sessionId)
                    PromptNotifier.ACTION_NO_PAYMENT -> {
                        sessions.byId(sessionId)?.let {
                            sessions.update(it.copy(outcome = SessionOutcome.NO_PAYMENT))
                        }
                        tracker.recordNoPayment(packageName)
                        notifier.cancelPrompt(sessionId)
                    }
                    PromptNotifier.ACTION_DISMISS -> {
                        sessions.byId(sessionId)?.let {
                            if (it.outcome != SessionOutcome.LOGGED) {
                                sessions.update(it.copy(outcome = SessionOutcome.DISMISSED))
                            }
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun onAmount(intent: Intent, sessionId: Long, packageName: String) {
        val typed = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PromptNotifier.KEY_AMOUNT)?.toString().orEmpty()

        val paise = Money.fromRupeeString(typed)?.takeIf { it > 0 }
        val session = sessions.byId(sessionId) ?: return

        if (paise == null) {
            // Unreadable input: ask again rather than guessing at a number.
            notifier.askForAmount(sessionId, packageName, appLabelFor(packageName))
            return
        }

        // The bank's text already saved this payment. What was typed is the
        // owner's own figure, so it wins; the prompt carries on from that row
        // instead of starting a second one.
        session.linkedTransactionId?.let { linkedId ->
            repository.byId(linkedId)?.let { txn ->
                if (txn.amountPaise != paise) repository.update(txn.copy(amountPaise = paise))
                notifier.showLogged(
                    transactionId = linkedId,
                    amountPaise = paise,
                    merchant = txn.merchantName,
                    ranked = orderedCategories(),
                    waiting = 0,
                    loud = true,
                    sessionId = sessionId,
                )
                return
            }
        }

        sessions.update(session.copy(parsedAmountPaise = paise))
        notifier.askForCategory(sessionId, packageName, paise, orderedCategories())
    }

    private suspend fun onCategory(intent: Intent, sessionId: Long, packageName: String) {
        val session = sessions.byId(sessionId) ?: return
        val amount = session.parsedAmountPaise?.takeIf { it > 0 } ?: run {
            notifier.askForAmount(sessionId, packageName, appLabelFor(packageName))
            return
        }

        val available = orderedCategories()
        val tapped = intent.getLongExtra(EXTRA_CATEGORY_ID, -1L).takeIf { it > 0 }
        val typed = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PromptNotifier.KEY_CATEGORY)?.toString()?.trim()

        val category = when {
            tapped != null -> available.firstOrNull { it.id == tapped }
            !typed.isNullOrBlank() -> available.firstOrNull { it.name.equals(typed, true) }
                ?: available.firstOrNull { it.name.contains(typed, true) }
            else -> null
        }

        if (category == null) {
            if (!typed.isNullOrBlank()) {
                notifier.categoryNotRecognised(sessionId, packageName, amount, typed, available)
            } else {
                notifier.askForCategory(sessionId, packageName, amount, available)
            }
            return
        }

        // Saved from a payment notification in the meantime: file that row
        // rather than saving the same payment twice.
        session.linkedTransactionId?.let { linkedId ->
            val existing = repository.byId(linkedId)
            if (existing != null) {
                if (existing.amountPaise != amount) repository.update(existing.copy(amountPaise = amount))
                repository.sort(linkedId, category.id, note = null)
                tracker.recordLogged(packageName)
                notifier.confirmSaved(
                    sessionId = sessionId,
                    packageName = packageName,
                    transactionId = linkedId,
                    amountPaise = amount,
                    categoryLabel = "${category.emoji} ${category.name}",
                )
                return
            }
        }

        val transactionId = repository.save(
            TransactionDraft(
                amountPaise = amount,
                categoryId = category.id,
                merchantName = null,
                note = null,
                timestamp = session.endedAt ?: session.startedAt,
                necessity = category.defaultNecessity,
                sourceApp = packageName.takeIf { it.isNotBlank() },
                entryMethod = EntryMethod.PROMPT_MANUAL,
                sessionId = sessionId,
                context = metadata.consume(sessionId),
            )
        )
        tracker.recordLogged(packageName)
        notifier.confirmSaved(
            sessionId = sessionId,
            packageName = packageName,
            transactionId = transactionId,
            amountPaise = amount,
            categoryLabel = "${category.emoji} ${category.name}",
        )
    }

    private suspend fun onNote(intent: Intent, sessionId: Long) {
        val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, -1L).takeIf { it > 0 } ?: return
        val note = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PromptNotifier.KEY_NOTE)?.toString()?.trim()

        val txn = repository.byId(transactionId) ?: return
        if (!note.isNullOrBlank()) repository.update(txn.copy(note = note))
        notifier.showNoteSaved(sessionId, txn.amountPaise, note.orEmpty())
    }

    private suspend fun onUndo(intent: Intent, sessionId: Long) {
        val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, -1L).takeIf { it > 0 }
        if (transactionId != null) repository.deleteByIds(listOf(transactionId))
        sessions.byId(sessionId)?.let {
            sessions.update(
                it.copy(outcome = SessionOutcome.DISMISSED, linkedTransactionId = null)
            )
        }
        notifier.cancelPrompt(sessionId)
    }

    private suspend fun onSort(intent: Intent, transactionId: Long) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, PromptNotifier.LOGGED_ID)
        val txn = repository.byId(transactionId) ?: return
        val available = categories.all().filter { !it.archived }
        val tapped = intent.getLongExtra(EXTRA_CATEGORY_ID, -1L).takeIf { it > 0 }
        val typed = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PromptNotifier.KEY_CATEGORY)?.toString()?.trim()
        val category = when {
            tapped != null -> available.firstOrNull { it.id == tapped }
            !typed.isNullOrBlank() -> available.firstOrNull { it.name.equals(typed, true) }
                ?: available.firstOrNull { it.name.contains(typed, true) }
            else -> null
        }
        if (category == null) {
            // Re-post so the spinner on the action clears; it stays in "To sort".
            notifier.showLogged(
                transactionId = transactionId,
                amountPaise = txn.amountPaise,
                merchant = txn.merchantName,
                ranked = listOfNotNull(available.firstOrNull { it.id == txn.categoryId }) +
                    available.filter { it.id != txn.categoryId },
                waiting = 0,
                loud = notificationId != PromptNotifier.LOGGED_ID,
                sessionId = txn.sessionId?.takeIf { notificationId != PromptNotifier.LOGGED_ID },
            )
            return
        }
        repository.sort(transactionId, category.id, note = null)
        txn.sourceApp?.let { tracker.recordLogged(it) }
        notifier.confirmSorted(notificationId, transactionId, txn.amountPaise, "${category.emoji} ${category.name}")
    }

    private suspend fun onSortNote(intent: Intent, transactionId: Long) {
        val note = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PromptNotifier.KEY_NOTE)?.toString()?.trim()
        val txn = repository.byId(transactionId) ?: return
        if (!note.isNullOrBlank()) repository.update(txn.copy(note = note))
        notifier.showSortedNote(
            intent.getIntExtra(EXTRA_NOTIFICATION_ID, PromptNotifier.LOGGED_ID),
            txn.amountPaise,
            note.orEmpty(),
        )
    }

    /** Most-used first, so the two buttons that fit are usually the right ones. */
    private suspend fun orderedCategories(): List<CategoryEntity> {
        val since = Time.now() - 90L * 86_400_000L
        return runCatching { categories.byUsage(since) }
            .getOrDefault(emptyList())
            .ifEmpty { categories.all().filter { !it.archived } }
            .ifEmpty { Seed.categories }
    }

    private suspend fun appLabelFor(packageName: String): String =
        watchedApps.byPackage(packageName)?.label ?: "that app"

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_CATEGORY_ID = "category_id"
        const val EXTRA_TRANSACTION_ID = "transaction_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
