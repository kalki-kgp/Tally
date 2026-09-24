package dev.pixelchutney.tally.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TallyScheduler @Inject constructor(
    private val context: Context,
) {
    fun scheduleAll() {
        val manager = WorkManager.getInstance(context)

        // UPDATE, not KEEP: this one has to survive being replaced by a build that
        // changes it, and re-arming it costs nothing.
        manager.enqueueUniquePeriodicWork(
            WORK_WATCHDOG,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build(),
        )

        manager.enqueueUniquePeriodicWork(
            WORK_MAINTENANCE,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayUntil(hour = 3), TimeUnit.MILLISECONDS)
                .build(),
        )

        // Sunday evening, when there is still a day left to act on what it says.
        manager.enqueueUniquePeriodicWork(
            WORK_WEEKLY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WeeklyDigestWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInitialDelay(delayUntilWeekly(DayOfWeek.SUNDAY, hour = 20), TimeUnit.MILLISECONDS)
                .build(),
        )

        manager.enqueueUniquePeriodicWork(
            WORK_MONTHLY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MonthlyReviewWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInitialDelay(delayUntil(hour = 9), TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    private fun delayUntil(hour: Int): Long {
        val now = LocalDateTime.now()
        var target = now.withHour(hour).withMinute(0).withSecond(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis()
    }

    private fun delayUntilWeekly(day: DayOfWeek, hour: Int): Long {
        val now = LocalDateTime.now()
        var target = now.with(day).withHour(hour).withMinute(0).withSecond(0)
        if (!target.isAfter(now)) target = target.plusWeeks(1)
        return Duration.between(now, target).toMillis()
    }

    private companion object {
        const val WORK_WATCHDOG = "tally_watchdog"
        const val WORK_MAINTENANCE = "tally_maintenance"
        const val WORK_WEEKLY = "tally_weekly_digest"
        const val WORK_MONTHLY = "tally_monthly_review"
        val ZONE: ZoneId = ZoneId.systemDefault()
    }
}
