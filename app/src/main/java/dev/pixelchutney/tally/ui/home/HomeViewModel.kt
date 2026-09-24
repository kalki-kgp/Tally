package dev.pixelchutney.tally.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pixelchutney.tally.ai.DigestGenerator
import dev.pixelchutney.tally.capture.UsageWatcherService
import dev.pixelchutney.tally.data.db.AiDao
import dev.pixelchutney.tally.data.db.InsightEntity
import dev.pixelchutney.tally.data.db.SessionDao
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.db.TransactionRow
import dev.pixelchutney.tally.data.repo.TallyRepository
import dev.pixelchutney.tally.data.settings.SettingsStore
import dev.pixelchutney.tally.insights.Anomaly
import dev.pixelchutney.tally.insights.HomeSnapshot
import dev.pixelchutney.tally.insights.InsightsEngine
import dev.pixelchutney.tally.update.AppUpdater
import dev.pixelchutney.tally.update.UpdateState
import dev.pixelchutney.tally.work.TallyScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PermissionHealth(
    val usageAccessOn: Boolean = true,
    val canPostNotifications: Boolean = true,
) {
    val allGood: Boolean get() = usageAccessOn && canPostNotifications
    val message: String get() = when {
        !usageAccessOn ->
            "Usage access is off, so Tally can't tell when you open a payment app."
        else ->
            "Tally can't post notifications, so the prompt never reaches you."
    }
}

data class HomeUiState(
    val snapshot: HomeSnapshot = HomeSnapshot(),
    val insight: InsightEntity? = null,
    val anomalies: List<Anomaly> = emptyList(),
    val recent: List<TransactionRow> = emptyList(),
    val health: PermissionHealth = PermissionHealth(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TallyRepository,
    private val insights: InsightsEngine,
    private val txns: TransactionDao,
    private val aiDao: AiDao,
    private val settings: SettingsStore,
    private val digests: DigestGenerator,
    sessions: SessionDao,
    private val updater: AppUpdater,
    scheduler: TallyScheduler,
) : ViewModel() {

    val update: StateFlow<UpdateState> = updater.state

    fun installUpdate() {
        viewModelScope.launch { updater.downloadAndInstall() }
    }

    /** Payments and visits waiting in "To sort". */
    val toSort: StateFlow<Int> = combine(
        txns.unsortedCountFlow(),
        sessions.toSortFlow(),
    ) { payments, visits -> payments + visits.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val health = MutableStateFlow(PermissionHealth())
    private val generating = MutableStateFlow(false)
    val isGeneratingDigest: StateFlow<Boolean> = generating.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
            scheduler.scheduleAll()
        }
    }

    /**
     * Recomputes whenever a transaction, a budget or a setting changes. The
     * `recentFlow` subscription is what makes Room tell us a transaction landed —
     * everything else is a suspend query against the same snapshot.
     */
    val state: StateFlow<HomeUiState> = combine(
        txns.recentFlow(6),
        repository.overallBudgetFlow(),
        settings.settings,
        aiDao.insightsFlow(1),
        health,
    ) { recent, budget, config, insightList, permissions ->
        val snapshot = insights.homeSnapshot(
            monthBudgetPaise = budget?.monthlyLimitPaise,
            smallLeakThresholdPaise = config.smallLeakThresholdPaise,
        )
        HomeUiState(
            snapshot = snapshot,
            insight = insightList.firstOrNull(),
            anomalies = insights.anomalies(),
            recent = recent,
            health = permissions,
            loading = false,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun refreshHealth() {
        health.value = PermissionHealth(
            usageAccessOn = UsageWatcherService.hasUsageAccess(context),
            canPostNotifications =
                androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
        // Cheap to call repeatedly; Android ignores a start for a running service.
        UsageWatcherService.start(context)
        viewModelScope.launch { updater.checkIfDue() }
    }

    fun dismissInsight(id: Long) {
        viewModelScope.launch { aiDao.dismissInsight(id) }
    }

    /** Manual "write it now" for the weekly review, rather than waiting for Sunday. */
    fun generateDigestNow() {
        if (generating.value) return
        viewModelScope.launch {
            generating.value = true
            runCatching { digests.weekly() }
            generating.value = false
        }
    }
}
