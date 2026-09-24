package dev.pixelchutney.tally.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pixelchutney.tally.capture.MetadataCollector
import dev.pixelchutney.tally.capture.UsageWatcherService
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.backup.BackupManager
import dev.pixelchutney.tally.data.backup.Exporter
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.db.WatchedAppDao
import dev.pixelchutney.tally.data.db.WatchedAppEntity
import dev.pixelchutney.tally.data.repo.TallyRepository
import dev.pixelchutney.tally.data.settings.CaptureMode
import dev.pixelchutney.tally.data.settings.SecretStore
import dev.pixelchutney.tally.data.settings.SettingsStore
import dev.pixelchutney.tally.data.settings.TallySettings
import dev.pixelchutney.tally.insights.InsightsEngine
import dev.pixelchutney.tally.insights.Metrics
import dev.pixelchutney.tally.update.AppUpdater
import dev.pixelchutney.tally.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class InstalledApp(val packageName: String, val label: String)

private data class PermissionState(
    val usageAccess: Boolean = false,
    val notifications: Boolean = true,
    val location: Boolean = false,
    val calendar: Boolean = false,
    val notificationAccess: Boolean = false,
)

/** A one-line result banner. Failures must not be dressed as successes. */
data class UiMessage(val text: String, val isError: Boolean = false)

data class WatcherHealth(
    val running: Boolean = false,
    val lastPollAt: Long = 0,
    val error: String? = null,
) {
    /** A watcher that has not polled in 15s is not working, whatever it claims. */
    val healthy: Boolean
        get() = running && lastPollAt > 0 && (Time.now() - lastPollAt) < 15_000
}

data class Diagnostics(
    /** Usage Access: how Tally sees which app is in front. */
    val usageAccessOn: Boolean = false,
    val watcher: WatcherHealth = WatcherHealth(),
    /** Whether Tally may post notifications at all — without this nothing prompts. */
    val canPostNotifications: Boolean = true,
    /** Payment metadata: recorded with each payment, unused until later. */
    val locationOn: Boolean = false,
    val calendarOn: Boolean = false,
    /** Notification access: how amounts are read from UPI apps and bank texts. */
    val notificationAccessOn: Boolean = false,
    val installedApps: List<InstalledApp> = emptyList(),
    val message: UiMessage? = null,
    val captureRatePercent: Int? = null,
    val medianLogSeconds: Long? = null,
    val transactionCount: Int = 0,
)

data class SettingsUiState(
    val config: TallySettings = TallySettings(),
    val watchedApps: List<WatchedAppEntity> = emptyList(),
    val monthlyBudgetPaise: Long = 0,
    val hasApiKey: Boolean = false,
    val diagnostics: Diagnostics = Diagnostics(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val repository: TallyRepository,
    private val watchedApps: WatchedAppDao,
    private val metadata: MetadataCollector,
    private val exporter: Exporter,
    private val backups: BackupManager,
    private val insights: InsightsEngine,
    private val txns: TransactionDao,
    private val updater: AppUpdater,
) : ViewModel() {

    val update: StateFlow<UpdateState> = updater.state
    val versionLabel: String get() = updater.currentVersionLabel

    fun checkForUpdate() {
        viewModelScope.launch { updater.check() }
    }

    fun installUpdate() {
        viewModelScope.launch { updater.downloadAndInstall() }
    }

    private val permissions = MutableStateFlow(PermissionState())
    private val message = MutableStateFlow<UiMessage?>(null)
    private val stats = MutableStateFlow(Triple<Int?, Long?, Int>(null, null, 0))
    private val watcher = MutableStateFlow(WatcherHealth())
    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())

    init {
        refresh()
        loadInstalledApps()
    }

    private val diagnostics = combine(
        permissions,
        message,
        stats,
        installed,
        watcher,
    ) { perms, msg, counts, apps, health ->
        Diagnostics(
            usageAccessOn = perms.usageAccess,
            watcher = health,
            canPostNotifications = perms.notifications,
            locationOn = perms.location,
            calendarOn = perms.calendar,
            notificationAccessOn = perms.notificationAccess,
            installedApps = apps,
            message = msg,
            captureRatePercent = counts.first,
            medianLogSeconds = counts.second,
            transactionCount = counts.third,
        )
    }

    val state: StateFlow<SettingsUiState> = combine(
        settings.settings,
        watchedApps.allFlow(),
        repository.overallBudgetFlow(),
        diagnostics,
    ) { config, apps, budget, diag ->
        SettingsUiState(
            config = config,
            watchedApps = apps,
            monthlyBudgetPaise = budget?.monthlyLimitPaise ?: 0L,
            hasApiKey = secrets.hasKey(),
            diagnostics = diag,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun refresh() {
        permissions.value = PermissionState(
            usageAccess = UsageWatcherService.hasUsageAccess(context),
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            location = metadata.hasLocationPermission(),
            calendar = metadata.hasCalendarPermission(),
            notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName),
        )
        // Returning from the Usage Access screen is the moment this becomes possible.
        UsageWatcherService.start(context)
        refreshWatcherHealth()
        viewModelScope.launch {
            val range = Time.lastNDays(30)
            val rate = insights.captureRate(range)?.let { (it * 100).toInt() }
            val lags = txns.sortedLogLags(range.first, range.last)
            val median = if (lags.isEmpty()) null else Metrics.median(lags) / 1000
            stats.value = Triple(rate, median, txns.count())
        }
    }

    /** Polled while Settings is open, so a dead watcher shows up as dead. */
    fun refreshWatcherHealth() {
        watcher.value = WatcherHealth(
            running = UsageWatcherService.running,
            lastPollAt = UsageWatcherService.lastPollAt,
            error = UsageWatcherService.lastError,
        )
    }

    fun restartWatcher() {
        UsageWatcherService.stop(context)
        UsageWatcherService.start(context)
        refreshWatcherHealth()
        message.value = UiMessage("Watcher restarted.")
    }

    /** Every launchable app on the phone, so a payment app can be added by name. */
    fun loadInstalledApps() {
        viewModelScope.launch {
            installed.value = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = context.packageManager
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                        .filter { it.packageName != context.packageName }
                        .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
                        .sortedBy { it.label.lowercase() }
                }.getOrDefault(emptyList())
            }
        }
    }

    fun addWatchedApp(app: InstalledApp) {
        viewModelScope.launch {
            if (watchedApps.byPackage(app.packageName) != null) {
                message.value = UiMessage("${app.label} is already on the list.", isError = false)
                return@launch
            }
            watchedApps.upsert(
                WatchedAppEntity(packageName = app.packageName, label = app.label, enabled = true)
            )
            message.value = UiMessage("Now watching ${app.label}.", isError = false)
        }
    }

    fun setBudget(rupees: String) {
        val paise = rupees.filter { it.isDigit() }.toLongOrNull()?.times(100) ?: 0L
        viewModelScope.launch { repository.setBudget(null, paise) }
    }

    fun setSmallLeakThreshold(rupees: String) {
        val paise = rupees.filter { it.isDigit() }.toLongOrNull()?.times(100) ?: return
        viewModelScope.launch { settings.setSmallLeakThreshold(paise.coerceIn(1_000, 500_000)) }
    }

    fun setPrompts(value: Boolean) = viewModelScope.launch { settings.setPromptsEnabled(value) }
    fun setReadNotifications(value: Boolean) =
        viewModelScope.launch { settings.setReadPaymentNotifications(value) }
    fun setSortLater(value: Boolean) = viewModelScope.launch {
        settings.setCaptureMode(if (value) CaptureMode.SORT_LATER else CaptureMode.ASK_NOW)
    }

    /**
     * Straight to Tally's own switch where Android allows it. On a sideloaded
     * app the switch is greyed out until "Allow restricted settings" has been
     * tapped in App info — the row's description says so.
     */
    fun openNotificationAccessSettings() {
        val component = android.content.ComponentName(
            context,
            dev.pixelchutney.tally.capture.PaymentNotificationListener::class.java,
        )
        val detail = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else null
        val opened = detail?.let {
            runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        } ?: false
        if (!opened) launchSettings(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    fun setAiEnabled(value: Boolean) = viewModelScope.launch { settings.setAiEnabled(value) }
    fun setAutoCategorise(value: Boolean) = viewModelScope.launch { settings.setAutoCategorise(value) }
    fun setWeeklyDigest(value: Boolean) = viewModelScope.launch { settings.setWeeklyDigest(value) }
    fun setAutoBackup(value: Boolean) = viewModelScope.launch { settings.setAutoBackup(value) }

    fun setAiCap(rupees: String) {
        val paise = rupees.filter { it.isDigit() }.toLongOrNull()?.times(100) ?: return
        viewModelScope.launch { settings.setAiMonthlyCap(paise) }
    }

    /**
     * Adding a key is the decision to use AI, so it switches AI on. Leaving the
     * switch off after a key was saved looked exactly like broken amount-reading:
     * the key was there and nothing happened.
     */
    fun saveApiKey(key: String) {
        secrets.apiKey = key.trim()
        val saved = secrets.hasKey()
        if (saved) viewModelScope.launch { settings.setAiEnabled(true) }
        message.value = UiMessage(if (saved) "API key saved. AI is on." else "API key cleared.")
        refresh()
    }

    fun clearApiKey() {
        secrets.clear()
        message.value = UiMessage("API key cleared.", isError = false)
        refresh()
    }

    fun toggleWatchedApp(app: WatchedAppEntity) {
        viewModelScope.launch {
            watchedApps.update(app.copy(enabled = !app.enabled, mutedUntil = 0, consecutiveNoPayment = 0))
        }
    }

    /** Hands the CSV to the system share sheet rather than writing somewhere unseen. */
    fun exportCsv() {
        viewModelScope.launch {
            runCatching {
                val file = exporter.writeToCache()
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "Export Tally data").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.onFailure { message.value = UiMessage("Export failed: ${it.message}", isError = true) }
        }
    }

    fun backupNow() {
        viewModelScope.launch {
            runCatching { backups.writeAutoBackup() }
                .onSuccess { message.value = UiMessage("Backup written to ${it.name}.", isError = false) }
                .onFailure { message.value = UiMessage("Backup failed: ${it.message}", isError = true) }
        }
    }

    fun restore(uri: Uri) {
        viewModelScope.launch {
            runCatching { backups.restore(uri) }
                .onSuccess { message.value = UiMessage("Restored $it transactions.", isError = false) }
                .onFailure { message.value = UiMessage("Restore failed: ${it.message}", isError = true) }
            refresh()
        }
    }

    fun openUsageAccessSettings() = UsageWatcherService.openUsageAccessSettings(context)

    /**
     * Location and calendar are both runtime permissions, and a request can only
     * come from an Activity. Sending you to the app's permission screen works from
     * here and is also the only route once a permission has been denied twice.
     */
    fun openAppPermissions() {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { message.value = UiMessage("Could not open permissions.", isError = true) }
    }

    fun openBatterySettings() =
        launchSettings(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun openAppNotificationSettings() {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { message.value = UiMessage("Could not open notification settings.", isError = true) }
    }

    private fun launchSettings(action: String) {
        runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { message.value = UiMessage("Could not open that settings screen.", isError = true) }
    }

    fun dismissMessage() { message.value = null }
}
