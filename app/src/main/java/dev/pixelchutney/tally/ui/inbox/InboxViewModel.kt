package dev.pixelchutney.tally.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pixelchutney.tally.ai.CategoryRanker
import dev.pixelchutney.tally.ai.RankInput
import dev.pixelchutney.tally.ai.Ranking
import dev.pixelchutney.tally.capture.AppLabels
import dev.pixelchutney.tally.capture.PaymentContext
import dev.pixelchutney.tally.capture.PromptNotifier
import dev.pixelchutney.tally.capture.SessionTracker
import dev.pixelchutney.tally.data.db.PaymentSessionEntity
import dev.pixelchutney.tally.data.db.SessionDao
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.db.TransactionEntity
import dev.pixelchutney.tally.data.db.TransactionRow
import dev.pixelchutney.tally.data.model.SessionOutcome
import dev.pixelchutney.tally.data.repo.TallyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A payment with an amount, waiting for "what was it for". */
data class SortItem(
    val row: TransactionRow,
    val note: String,
    val ranking: Ranking?,
    /** Haiku is re-reading it with the note. */
    val thinking: Boolean,
    val appLabel: String?,
)

/** A visit to a payment app where no amount turned up. */
data class VisitItem(
    val session: PaymentSessionEntity,
    val appLabel: String,
)

data class InboxState(
    val items: List<SortItem> = emptyList(),
    val visits: List<VisitItem> = emptyList(),
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = items.isEmpty() && visits.isEmpty()
    val totalPaise: Long get() = items.sumOf { it.row.txn.amountPaise }
}

/**
 * "To sort": everything Tally caught without asking, waiting for the one thing
 * only the owner knows — what the money was for.
 *
 * Typing a note re-ranks the categories. That is deliberate: the note is the
 * strongest evidence there is, so the chips should visibly respond to it.
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repository: TallyRepository,
    private val txns: TransactionDao,
    private val sessions: SessionDao,
    private val tracker: SessionTracker,
    private val ranker: CategoryRanker,
    private val notifier: PromptNotifier,
    private val labels: AppLabels,
) : ViewModel() {

    private val notes = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val rankings = MutableStateFlow<Map<Long, Ranking>>(emptyMap())
    private val thinking = MutableStateFlow<Set<Long>>(emptySet())
    private val rankJobs = mutableMapOf<Long, Job>()

    val state: StateFlow<InboxState> = combine(
        txns.unsortedFlow(),
        sessions.toSortFlow(),
        notes,
        rankings,
        thinking,
    ) { rows, visits, noteMap, rankMap, busy ->
        rows.forEach { row -> if (row.txn.id !in rankMap && row.txn.id !in rankJobs) rankLater(row.txn, withModel = true) }
        InboxState(
            items = rows.map { row ->
                SortItem(
                    row = row,
                    note = noteMap[row.txn.id] ?: row.txn.note.orEmpty(),
                    ranking = rankMap[row.txn.id],
                    thinking = row.txn.id in busy,
                    appLabel = labels.of(row.txn.sourceApp ?: row.txn.detectedFrom),
                )
            },
            visits = visits.map { VisitItem(it, labels.of(it.packageName) ?: it.packageName) },
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxState())

    fun setNote(id: Long, text: String) {
        notes.update { it + (id to text) }
        // Re-rank once typing pauses, not on every letter — each pass can be a call.
        val txn = state.value.items.firstOrNull { it.row.txn.id == id }?.row?.txn ?: return
        rankJobs[id]?.cancel()
        rankJobs[id] = viewModelScope.launch {
            delay(NOTE_PAUSE_MS)
            rank(txn, text, withModel = true)
        }
    }

    fun sort(id: Long, categoryId: Long) {
        val item = state.value.items.firstOrNull { it.row.txn.id == id } ?: return
        viewModelScope.launch {
            repository.sort(
                id = id,
                categoryId = categoryId,
                note = item.note,
                merchantName = item.row.txn.merchantName ?: item.ranking?.merchant,
                necessity = item.ranking?.necessity.takeIf { item.ranking?.top?.id == categoryId },
            )
            notes.update { it - id }
            rankings.update { it - id }
            // Its prompt, if one is still in the shade, has been answered here.
            item.row.txn.sessionId?.let { notifier.cancelPrompt(it) }
            if (txns.unsortedCount() == 0) notifier.clearLogged()
        }
    }

    /** A misread notification: not a payment after all. */
    fun discard(id: Long) {
        viewModelScope.launch {
            val txn = txns.byId(id)
            repository.deleteByIds(listOf(id))
            txn?.sessionId?.let { notifier.cancelPrompt(it) }
            if (txns.unsortedCount() == 0) notifier.clearLogged()
        }
    }

    fun noPayment(session: PaymentSessionEntity) {
        viewModelScope.launch {
            sessions.byId(session.id)?.let { sessions.update(it.copy(outcome = SessionOutcome.NO_PAYMENT)) }
            tracker.recordNoPayment(session.packageName)
        }
    }

    private fun rankLater(txn: TransactionEntity, withModel: Boolean) {
        rankJobs[txn.id] = viewModelScope.launch { rank(txn, txn.note, withModel) }
    }

    private suspend fun rank(txn: TransactionEntity, note: String?, withModel: Boolean) {
        val input = RankInput(
            amountPaise = txn.amountPaise,
            timestamp = txn.timestamp,
            sourceApp = txn.sourceApp,
            payee = txn.payee,
            merchantName = txn.merchantName,
            note = note?.takeIf { it.isNotBlank() },
            context = txn.toContext(),
            excludeTxnId = txn.id,
        )
        // The on-device order shows at once; Haiku's replaces it when it lands.
        val local = ranker.rank(input, allowModel = false)
        rankings.update { current -> if (current[txn.id]?.fromModel == true) current else current + (txn.id to local) }
        if (!withModel) return
        thinking.update { it + txn.id }
        try {
            val ranked = ranker.rank(input, allowModel = true)
            rankings.update { it + (txn.id to ranked) }
        } finally {
            thinking.update { it - txn.id }
        }
    }

    private fun TransactionEntity.toContext() = PaymentContext(
        latitude = latitude,
        longitude = longitude,
        locationAccuracyM = locationAccuracyM,
        locationAt = locationAt,
        placeName = placeName,
        placeAddress = placeAddress,
        locationSource = locationSource,
        networkName = networkName,
        networkOperator = networkOperator,
        roaming = roaming,
        precedingApp = precedingApp,
        calendarEvent = calendarEvent,
    )

    private companion object {
        const val NOTE_PAUSE_MS = 1_200L
    }
}
