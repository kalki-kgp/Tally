package dev.pixelchutney.tally.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.CategoryEntity
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.db.TransactionRow
import dev.pixelchutney.tally.data.model.Necessity
import dev.pixelchutney.tally.data.model.TxnFilterRange
import dev.pixelchutney.tally.data.repo.TallyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

data class ActivityFilters(
    val query: String = "",
    val range: TxnFilterRange = TxnFilterRange.MONTH,
    val categoryId: Long? = null,
    val necessity: Necessity? = null,
)

data class ActivityUiState(
    val rows: List<TransactionRow> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val filters: ActivityFilters = ActivityFilters(),
    val selectedIds: Set<Long> = emptySet(),
    val totalPaise: Long = 0,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val txns: TransactionDao,
    private val repository: TallyRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(ActivityFilters())
    private val selected = MutableStateFlow<Set<Long>>(emptySet())

    private val rows = filters
        .debounce { if (it.query.isEmpty()) 0L else 220L }
        .flatMapLatest { current ->
            val range = when (current.range) {
                TxnFilterRange.TODAY -> Time.dayRange(Time.today())
                TxnFilterRange.WEEK -> Time.weekRange()
                TxnFilterRange.MONTH -> Time.monthRange()
                TxnFilterRange.LAST_MONTH -> Time.monthRange(YearMonth.now().minusMonths(1))
                TxnFilterRange.ALL -> null
            }
            txns.searchFlow(
                from = range?.first,
                to = range?.last,
                categoryId = current.categoryId,
                necessity = current.necessity?.name,
                query = current.query.takeIf { it.isNotBlank() },
                limit = 500,
            )
        }

    val state: StateFlow<ActivityUiState> = combine(
        rows,
        repository.activeCategories,
        filters,
        selected,
    ) { list, categories, current, selectedIds ->
        ActivityUiState(
            rows = list,
            categories = categories,
            filters = current,
            selectedIds = selectedIds,
            totalPaise = list.filter { !it.txn.excluded && it.txn.amountPaise > 0 }
                .sumOf { it.txn.amountPaise },
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    fun setQuery(value: String) = filters.update { it.copy(query = value) }
    fun setRange(value: TxnFilterRange) = filters.update { it.copy(range = value) }
    fun setCategory(id: Long?) = filters.update { it.copy(categoryId = id) }
    fun setNecessity(value: Necessity?) = filters.update { it.copy(necessity = value) }

    fun toggleSelection(id: Long) = selected.update { current ->
        if (id in current) current - id else current + id
    }

    fun clearSelection() { selected.value = emptySet() }

    fun deleteSelected() {
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.deleteByIds(ids)
            selected.value = emptySet()
        }
    }

    fun recategoriseSelected(categoryId: Long) {
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.recategorise(ids, categoryId)
            selected.value = emptySet()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.deleteByIds(listOf(id)) }
    }
}
