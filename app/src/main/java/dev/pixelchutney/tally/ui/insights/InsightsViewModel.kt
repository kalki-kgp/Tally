package dev.pixelchutney.tally.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.CategoryTotal
import dev.pixelchutney.tally.data.db.MerchantTotal
import dev.pixelchutney.tally.data.db.RecurringDao
import dev.pixelchutney.tally.data.db.RecurringRuleEntity
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.db.TransactionRow
import dev.pixelchutney.tally.data.settings.SettingsStore
import dev.pixelchutney.tally.insights.InsightsEngine
import dev.pixelchutney.tally.insights.Metrics
import dev.pixelchutney.tally.insights.NecessitySplit
import dev.pixelchutney.tally.insights.PatternsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

enum class InsightsTab(val label: String) {
    Trends("Trends"),
    Categories("Categories"),
    Merchants("Merchants"),
    Patterns("Patterns"),
    Calendar("Calendar"),
}

data class MonthCell(val date: LocalDate?, val paise: Long, val count: Int)

data class InsightsUiState(
    val tab: InsightsTab = InsightsTab.Trends,
    val month: YearMonth = YearMonth.now(),
    val dailyThisMonth: List<Long> = emptyList(),
    val monthlyHistory: List<Pair<YearMonth, Long>> = emptyList(),
    val necessityHistory: List<Pair<YearMonth, NecessitySplit>> = emptyList(),
    val categories: List<CategoryTotal> = emptyList(),
    val previousCategories: Map<String, Long> = emptyMap(),
    val merchantsBySpend: List<MerchantTotal> = emptyList(),
    val patterns: PatternsSnapshot = PatternsSnapshot(),
    val calendarCells: List<MonthCell> = emptyList(),
    val calendarMax: Long = 1,
    val selectedDay: LocalDate? = null,
    val selectedDayRows: List<TransactionRow> = emptyList(),
    val recurring: List<RecurringRuleEntity> = emptyList(),
    val subscriptionsMonthlyPaise: Long = 0,
    val smallLeakThresholdPaise: Long = 20_000L,
    val medianTxnPaise: Long = 0,
    val projectionPaise: Long = 0,
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val txns: TransactionDao,
    private val engine: InsightsEngine,
    private val recurringDao: RecurringDao,
    private val settings: SettingsStore,
) : ViewModel() {

    private val tab = MutableStateFlow(InsightsTab.Trends)
    private val month = MutableStateFlow(YearMonth.now())
    private val selectedDay = MutableStateFlow<LocalDate?>(null)

    val state: StateFlow<InsightsUiState> = combine(
        txns.recentFlow(1),
        tab,
        month,
        selectedDay,
        combine(recurringDao.allFlow(), settings.settings) { rules, config -> rules to config },
    ) { _, currentTab, currentMonth, day, (rules, config) ->
        val monthRange = Time.monthRange(currentMonth)
        val previousRange = Time.monthRange(currentMonth.minusMonths(1))

        val dailyRows = txns.dailyTotals(monthRange.first, monthRange.last)
        val daily = engine.fillDays(
            dailyRows,
            currentMonth.atDay(1),
            if (currentMonth == YearMonth.now()) Time.today() else currentMonth.atEndOfMonth(),
        )

        val categories = txns.categoryTotals(monthRange.first, monthRange.last)
        val dayMap = engine.dailyMap(dailyRows)

        InsightsUiState(
            tab = currentTab,
            month = currentMonth,
            dailyThisMonth = daily,
            monthlyHistory = engine.monthlyHistory(6),
            necessityHistory = engine.necessityHistory(6),
            categories = categories,
            previousCategories = txns.categoryTotals(previousRange.first, previousRange.last)
                .associate { it.name to it.totalPaise },
            merchantsBySpend = engine.topMerchants(monthRange, 15),
            patterns = engine.patterns(thresholdPaise = config.smallLeakThresholdPaise),
            calendarCells = buildCalendar(currentMonth, dayMap),
            calendarMax = dailyRows.maxOfOrNull { it.totalPaise } ?: 1L,
            selectedDay = day,
            selectedDayRows = day?.let {
                val range = Time.dayRange(it)
                txns.rowsBetween(range.first, range.last)
            }.orEmpty(),
            recurring = rules,
            subscriptionsMonthlyPaise = engine.subscriptionsMonthlyTotal(),
            smallLeakThresholdPaise = config.smallLeakThresholdPaise,
            medianTxnPaise = Metrics.median(
                txns.sortedAmounts(monthRange.first, monthRange.last)
            ),
            projectionPaise = Metrics.trimmedProjection(daily, currentMonth.lengthOfMonth()),
            loading = false,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    /** Pads the grid so the 1st lands under the right weekday, Monday first. */
    private fun buildCalendar(
        month: YearMonth,
        dayMap: Map<LocalDate, dev.pixelchutney.tally.data.db.DailyTotal>,
    ): List<MonthCell> {
        val first = month.atDay(1)
        val leading = (first.dayOfWeek.value + 6) % 7
        val cells = mutableListOf<MonthCell>()
        repeat(leading) { cells += MonthCell(null, 0, 0) }
        (1..month.lengthOfMonth()).forEach { day ->
            val date = month.atDay(day)
            val row = dayMap[date]
            cells += MonthCell(date, row?.totalPaise ?: 0L, row?.txnCount ?: 0)
        }
        while (cells.size % 7 != 0) cells += MonthCell(null, 0, 0)
        return cells
    }

    fun selectTab(value: InsightsTab) { tab.value = value }

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
        selectedDay.value = null
    }

    fun nextMonth() {
        if (month.value < YearMonth.now()) {
            month.value = month.value.plusMonths(1)
            selectedDay.value = null
        }
    }

    fun selectDay(date: LocalDate?) {
        selectedDay.value = if (selectedDay.value == date) null else date
    }

    fun confirmRecurring(rule: RecurringRuleEntity) {
        viewModelScope.launch { recurringDao.update(rule.copy(confirmed = true)) }
    }

    fun dismissRecurring(rule: RecurringRuleEntity) {
        viewModelScope.launch { recurringDao.update(rule.copy(dismissed = true)) }
    }
}
