package dev.pixelchutney.tally.capture.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pixelchutney.tally.ai.MerchantCategoriser
import dev.pixelchutney.tally.capture.MetadataCollector
import dev.pixelchutney.tally.capture.PromptNotifier
import dev.pixelchutney.tally.capture.SessionTracker
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.CategoryEntity
import dev.pixelchutney.tally.data.db.Seed
import dev.pixelchutney.tally.data.db.SessionDao
import dev.pixelchutney.tally.data.model.EntryMethod
import dev.pixelchutney.tally.data.model.Necessity
import dev.pixelchutney.tally.data.repo.TallyRepository
import dev.pixelchutney.tally.data.repo.TransactionDraft
import dev.pixelchutney.tally.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CaptureState(
    /** What the numpad has collected, in rupees: `"240"`, `"240."`, `"240.50"`. */
    val amountEntry: String = "",
    val negative: Boolean = false,
    val categories: List<CategoryEntity> = emptyList(),
    val selectedCategoryId: Long? = null,
    val merchant: String = "",
    val merchantSuggestions: List<String> = emptyList(),
    val note: String = "",
    val showNote: Boolean = false,
    val necessity: Necessity = Necessity.UNSORTED,
    val timestamp: Long = Time.now(),
    val excluded: Boolean = false,
    val duplicateWarning: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val prefilled: Boolean = false,
    val sourceApp: String? = null,
    val sessionId: Long? = null,
    /** Set when the sheet was opened to correct an existing entry. */
    val editingId: Long? = null,
) {
    val amountPaise: Long
        get() {
            val raw = Money.fromEntry(amountEntry)
            return if (negative) -raw else raw
        }
    val canSave: Boolean get() = amountPaise != 0L && selectedCategoryId != null && !saving
}

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repository: TallyRepository,
    private val sessions: SessionDao,
    private val tracker: SessionTracker,
    private val notifier: PromptNotifier,
    private val categoriser: MerchantCategoriser,
    private val metadata: MetadataCollector,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    fun start(
        sessionId: Long?,
        packageName: String?,
        amountPaise: Long?,
        merchant: String?,
        editingId: Long? = null,
    ) {
        viewModelScope.launch {
            repository.ensureSeeded()
            val categories = repository.allCategories().filter { !it.archived }

            // A visit whose payment a notification already saved is that payment:
            // open it for correcting rather than starting a duplicate.
            val linkedId = sessionId?.let { sessions.byId(it)?.linkedTransactionId }
            val editing = editingId ?: linkedId

            // Correcting an existing entry: load it and stop, nothing to guess.
            if (editing != null) {
                val existing = repository.byId(editing)
                if (existing != null) {
                    _state.update {
                        it.copy(
                            categories = categories,
                            editingId = editing,
                            amountEntry = Money.toEntry(existing.amountPaise),
                            negative = existing.amountPaise < 0,
                            selectedCategoryId = existing.categoryId,
                            merchant = existing.merchantName.orEmpty(),
                            note = existing.note.orEmpty(),
                            showNote = !existing.note.isNullOrBlank(),
                            necessity = existing.necessity,
                            timestamp = existing.timestamp,
                            excluded = existing.excluded,
                            sourceApp = existing.sourceApp,
                        )
                    }
                    return@launch
                }
            }

            val memory = repository.recall(merchant)
            val suggestedCategory = memory.suggestedCategoryId
                ?: categories.firstOrNull()?.id

            _state.update {
                it.copy(
                    categories = categories,
                    selectedCategoryId = suggestedCategory,
                    amountEntry = amountPaise?.takeIf { value -> value > 0 }
                        ?.let(Money::toEntry).orEmpty(),
                    merchant = memory.merchant?.canonicalName ?: merchant.orEmpty(),
                    necessity = memory.suggestedNecessity
                        ?: categories.firstOrNull { c -> c.id == suggestedCategory }?.defaultNecessity
                        ?: Necessity.UNSORTED,
                    sessionId = sessionId,
                    sourceApp = packageName,
                    prefilled = amountPaise != null && amountPaise > 0,
                    timestamp = sessionId?.let { id -> sessions.byId(id)?.endedAt } ?: Time.now(),
                )
            }

            checkDuplicate()

            // A merchant nobody has filed yet: ask the model in the background so the
            // chip is already right by the time the amount is typed.
            if (!merchant.isNullOrBlank() && memory.suggestedCategoryId == null &&
                settings.current().autoCategorise
            ) {
                val guess = categoriser.categorise(
                    rawMerchant = merchant,
                    amountPaise = amountPaise ?: 0L,
                    timestamp = Time.now(),
                    sourceApp = packageName,
                )
                if (guess != null && !_state.value.saving) {
                    _state.update {
                        if (it.selectedCategoryId != suggestedCategory) it
                        else it.copy(
                            selectedCategoryId = guess.categoryId,
                            merchant = guess.canonicalName,
                            necessity = guess.necessity,
                        )
                    }
                }
            }
        }
    }

    fun digit(value: String) {
        _state.update { current ->
            val entry = current.amountEntry
            val dot = entry.indexOf('.')
            when {
                // Two paise digits is all there is room for.
                dot >= 0 && entry.length - dot > 2 -> current
                dot < 0 && entry.length >= MAX_RUPEE_DIGITS -> current
                entry.isEmpty() && value == "0" -> current
                else -> current.copy(amountEntry = entry + value)
            }
        }
        checkDuplicate()
    }

    /** Paise are the exception, so the decimal point is pressed only when wanted. */
    fun decimal() {
        _state.update { current ->
            if (current.amountEntry.contains('.')) current
            else current.copy(amountEntry = current.amountEntry.ifEmpty { "0" } + ".")
        }
    }

    fun backspace() {
        _state.update { it.copy(amountEntry = it.amountEntry.dropLast(1)) }
    }

    fun toggleSign() = _state.update { it.copy(negative = !it.negative) }

    fun selectCategory(id: Long) {
        _state.update { current ->
            val category = current.categories.firstOrNull { it.id == id }
            current.copy(
                selectedCategoryId = id,
                necessity = if (current.necessity == Necessity.UNSORTED) {
                    category?.defaultNecessity ?: Necessity.UNSORTED
                } else {
                    current.necessity
                },
            )
        }
    }

    fun setNecessity(value: Necessity) = _state.update { it.copy(necessity = value) }
    fun setNote(value: String) = _state.update { it.copy(note = value) }
    fun toggleNote() = _state.update { it.copy(showNote = !it.showNote) }
    fun setExcluded(value: Boolean) = _state.update { it.copy(excluded = value) }
    fun setTimestamp(value: Long) = _state.update { it.copy(timestamp = value) }

    fun setMerchant(value: String) {
        _state.update { it.copy(merchant = value) }
        viewModelScope.launch {
            val suggestions = repository.suggestMerchants(value).map { it.canonicalName }
            _state.update { it.copy(merchantSuggestions = suggestions) }
            checkDuplicate()
        }
    }

    fun applySuggestion(name: String) {
        viewModelScope.launch {
            val memory = repository.recall(name)
            _state.update {
                it.copy(
                    merchant = name,
                    merchantSuggestions = emptyList(),
                    selectedCategoryId = memory.suggestedCategoryId ?: it.selectedCategoryId,
                    necessity = memory.suggestedNecessity ?: it.necessity,
                )
            }
        }
    }

    private fun checkDuplicate() {
        val current = _state.value
        if (current.amountPaise <= 0) return
        viewModelScope.launch {
            val duplicate = repository.looksLikeDuplicate(
                amountPaise = current.amountPaise,
                merchantName = current.merchant.takeIf { it.isNotBlank() },
                at = current.timestamp,
            )
            _state.update { it.copy(duplicateWarning = duplicate) }
        }
    }

    fun save(onDone: () -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true) }

        viewModelScope.launch {
            val editingId = current.editingId
            if (editingId != null) {
                repository.byId(editingId)?.let { existing ->
                    repository.update(
                        existing.copy(
                            amountPaise = current.amountPaise,
                            categoryId = current.selectedCategoryId ?: existing.categoryId,
                            merchantName = current.merchant.takeIf { it.isNotBlank() },
                            note = current.note.takeIf { it.isNotBlank() },
                            timestamp = current.timestamp,
                            necessity = current.necessity,
                            excluded = current.excluded,
                            // Opening an unsorted payment and saving it is sorting it.
                            reviewed = true,
                        )
                    )
                    // Opened from a prompt that already showed this payment's amount.
                    existing.sessionId?.let { notifier.cancelPrompt(it) }
                }
                _state.update { it.copy(saving = false, saved = true) }
                onDone()
                return@launch
            }

            repository.save(
                TransactionDraft(
                    amountPaise = current.amountPaise,
                    categoryId = current.selectedCategoryId ?: Seed.FALLBACK_CATEGORY_ID,
                    merchantName = current.merchant.takeIf { it.isNotBlank() },
                    note = current.note,
                    timestamp = current.timestamp,
                    necessity = current.necessity,
                    sourceApp = current.sourceApp,
                    entryMethod = when {
                        current.prefilled -> EntryMethod.AUTO_PARSED
                        current.sessionId != null -> EntryMethod.PROMPT_MANUAL
                        else -> EntryMethod.FULLY_MANUAL
                    },
                    excluded = current.excluded,
                    sessionId = current.sessionId,
                    context = metadata.consume(current.sessionId),
                )
            )
            current.sessionId?.let { notifier.cancelPrompt(it) }
            current.sourceApp?.let { tracker.recordLogged(it) }
            _state.update { it.copy(saving = false, saved = true) }
            onDone()
        }
    }

    fun deleteEditing(onDone: () -> Unit) {
        val id = _state.value.editingId ?: return
        viewModelScope.launch {
            repository.deleteByIds(listOf(id))
            onDone()
        }
    }

    fun dismissAsNoPayment(onDone: () -> Unit) {
        val current = _state.value
        viewModelScope.launch {
            current.sessionId?.let { id ->
                sessions.byId(id)?.let {
                    sessions.update(it.copy(outcome = dev.pixelchutney.tally.data.model.SessionOutcome.NO_PAYMENT))
                }
                notifier.cancelPrompt(id)
            }
            current.sourceApp?.let { tracker.recordNoPayment(it) }
            onDone()
        }
    }

    private companion object {
        /** ₹99,99,999 — past anything this app will ever be asked to record. */
        const val MAX_RUPEE_DIGITS = 7
    }
}
