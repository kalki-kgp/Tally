package dev.pixelchutney.tally.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pixelchutney.tally.ai.DigestGenerator
import dev.pixelchutney.tally.capture.PromptNotifier
import dev.pixelchutney.tally.capture.UsageWatcherService
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.backup.BackupManager
import dev.pixelchutney.tally.data.db.AiDao
import dev.pixelchutney.tally.data.db.InsightEntity
import dev.pixelchutney.tally.data.db.SessionDao
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.repo.TallyRepository
import dev.pixelchutney.tally.data.settings.SettingsStore
import dev.pixelchutney.tally.insights.Metrics
import dev.pixelchutney.tally.insights.RecurringDetector
import java.time.YearMonth

/**
 * Restarts the watcher if it is not running. Nothing else.
 *
 * The foreground service is `START_STICKY` and gets restarted after an ordinary
 * process death, but that is not the case that actually happens: OEM battery
 * managers kill it outright, and force-stopping the app or swiping it out of
 * recents can too. Recovery used to be the daily maintenance pass or opening the
 * app, which meant detection could be dead for hours without a sign.
 *
 * Fifteen minutes is WorkManager's floor for periodic work. Starting a service
 * that is already running is a no-op, so this is cheap enough to run forever.
 */
@HiltWorker
class WatchdogWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val notifier: PromptNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (UsageWatcherService.running) {
            notifier.clearHealthWarning()
            return Result.success()
        }

        if (!UsageWatcherService.hasUsageAccess(context)) {
            notifier.showHealthWarning(
                "Usage access is off, so Tally cannot tell when you open a payment app. " +
                    "Open Tally to switch it back on."
            )
            return Result.success()
        }

        // A refused restart is the case worth saying out loud: detection is down
        // and this app cannot fix it by itself.
        if (UsageWatcherService.start(context)) {
            notifier.clearHealthWarning()
        } else {
            notifier.showHealthWarning(
                "Detection stopped and Android would not let Tally restart it in the " +
                    "background. Open Tally to start it again, and exempt Tally from " +
                    "battery optimisation so it stops happening."
            )
        }
        return Result.success()
    }
}

@HiltWorker
class WeeklyDigestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val digests: DigestGenerator,
    private val settings: SettingsStore,
    private val notifier: PromptNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = settings.current()
        if (!config.aiEnabled || !config.weeklyDigestEnabled) return Result.success()

        val insight = digests.weekly() ?: return Result.success()
        settings.setLastDigestAt(Time.now())
        notifier.showInsight(
            id = PromptNotifier.DIGEST_ID,
            title = "Your week in numbers",
            body = insight.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
        )
        return Result.success()
    }
}

@HiltWorker
class MonthlyReviewWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val digests: DigestGenerator,
    private val settings: SettingsStore,
    private val notifier: PromptNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.current().aiEnabled) return Result.success()
        // Only on the first few days of a month, whenever the worker happens to run.
        if (Time.today().dayOfMonth > 3) return Result.success()

        val insight = digests.monthly() ?: return Result.success()
        notifier.showInsight(
            id = PromptNotifier.DIGEST_ID + 1,
            title = "${insight.title} in review",
            body = insight.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
        )
        return Result.success()
    }
}

/**
 * The daily housekeeping pass: prunes logs, closes sessions the app never got to
 * answer for, looks for new subscriptions, checks budget pace, backs up, and — the
 * important one — notices when payment detection has silently stopped.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val sessions: SessionDao,
    private val recurring: RecurringDetector,
    private val txns: TransactionDao,
    private val repository: TallyRepository,
    private val aiDao: AiDao,
    private val settings: SettingsStore,
    private val notifier: PromptNotifier,
    private val backups: BackupManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = Time.now()
        val config = settings.current()

        sessions.expireStale(now - config.promptExpiryMinutes * 60_000L)
        sessions.pruneBefore(now - 180L * 86_400_000L)
        aiDao.pruneCache(now - 180L * 86_400_000L)

        runCatching { recurring.scanAndStore() }

        checkDetectionHealth()
        checkBudgetPace()

        if (config.autoBackupEnabled) {
            runCatching { backups.writeAutoBackup() }
                .onSuccess { settings.setLastBackupAt(now) }
        }

        return Result.success()
    }

    private fun checkDetectionHealth() {
        when {
            !UsageWatcherService.hasUsageAccess(context) -> notifier.showHealthWarning(
                "Usage access is off, so Tally cannot tell when you open a payment app. " +
                    "Open Tally to switch it back on."
            )
            !notifier.canPost() -> notifier.showHealthWarning(
                "Tally is not allowed to post notifications, so the prompt never appears."
            )
            else -> {
                notifier.clearHealthWarning()
                // The watcher is killed by reboots and aggressive battery managers;
                // this is the daily nudge that brings it back.
                UsageWatcherService.start(context)
            }
        }
    }

    /**
     * Two notifications a month, at 80% and at 100%. Anything more often becomes
     * noise and gets swiped away along with the prompts that matter.
     */
    private suspend fun checkBudgetPace() {
        val budget = repository.overallBudget() ?: return
        val today = Time.today()
        val month = Time.monthToDate(today)
        val spent = txns.spendBetween(month.first, month.last)
        val fraction = Metrics.share(spent, budget.monthlyLimitPaise)
        val monthStart = Time.monthRange(YearMonth.from(today)).first

        suspend fun alertOnce(kind: String, title: String, body: String) {
            val last = aiDao.latestOfKind(kind)
            if (last != null && last.periodStart == monthStart) return
            aiDao.insertInsight(
                InsightEntity(
                    kind = kind,
                    title = title,
                    body = body,
                    createdAt = Time.now(),
                    periodStart = monthStart,
                    periodEnd = month.last,
                )
            )
            notifier.showInsight(PromptNotifier.BUDGET_ID + kind.hashCode() % 100, title, body)
        }

        val remaining = budget.monthlyLimitPaise - spent
        val daysLeft = Time.daysRemainingInMonth(today)
        when {
            fraction >= 1f -> alertOnce(
                "BUDGET_100",
                "Monthly budget spent",
                "You have used all ${Money.format(budget.monthlyLimitPaise)} with $daysLeft days still to go.",
            )
            fraction >= 0.8f -> alertOnce(
                "BUDGET_80",
                "80% of the month's budget is gone",
                "${Money.format(remaining)} left for $daysLeft days — about " +
                    "${Money.format(remaining / daysLeft.coerceAtLeast(1))} a day.",
            )
        }
    }
}
