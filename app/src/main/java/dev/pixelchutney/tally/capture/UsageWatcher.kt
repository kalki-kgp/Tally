package dev.pixelchutney.tally.capture

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.pixelchutney.tally.MainActivity
import dev.pixelchutney.tally.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Watches which app is in the foreground using the Usage Access API.
 *
 * This replaced an AccessibilityService for one practical reason: Indian banking
 * and payment apps scan for enabled accessibility services and refuse to let you
 * pay until you switch them off. Usage Access reads the same information — which
 * app came to the front, and when — without tripping that check.
 *
 * The cost is that it has to be polled rather than pushed, which means a
 * foreground service and the permanent notification Android requires with it.
 *
 * Every step of startup is guarded on its own. A service that dies in `onCreate`
 * stops detecting silently, and silence is the one failure mode this app cannot
 * afford.
 */
@AndroidEntryPoint
class UsageWatcherService : Service() {

    @Inject lateinit var tracker: SessionTracker
    @Inject lateinit var metadata: MetadataCollector

    private val scope = CoroutineScope(SupervisorJob())
    private var loop: Job? = null
    private var receiverRegistered = false

    /** Locking the phone ends a visit; usage events alone do not report that. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                runCatching { tracker.onScreenOff() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        enterForeground()

        // Android 14+ requires an explicit export flag here. Getting it wrong
        // throws, and an unguarded throw would take polling down with it.
        runCatching {
            ContextCompat.registerReceiver(
                this,
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }.onFailure { lastError = "screenOffReceiver: ${it.message}" }

        running = true
        start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        // Re-asserted on every start so granting location later upgrades the
        // service's type without needing a restart.
        enterForeground()
        if (loop?.isActive != true) start()
        return START_STICKY
    }

    /**
     * Declares only the service types this app is actually allowed to run.
     *
     * The manifest declares `specialUse|location`, but from Android 14 a service
     * that claims the `location` type while the permission is denied does not
     * start at all — it throws, and detection dies with it. Since location is only
     * ever nice-to-have metadata and detection is the point, the type is computed
     * from what has actually been granted.
     */
    private fun enterForeground() {
        val notification = statusNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (metadata.hasLocationPermission()) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { lastError = "startForeground: ${it.message}" }
    }

    /**
     * Swiping Tally out of recents takes the service with it on most OEM builds,
     * even though it is a foreground service. Asking to be restarted is the only
     * way back, and this is the moment it can be asked for.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        runCatching { start(this) }
    }

    private fun start() {
        loop = scope.launch {
            while (isActive) {
                runCatching { poll() }
                    .onFailure { lastError = "poll: ${it.message}" }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Works out what is on screen and tells the tracker — every tick, whether or
     * not anything changed.
     *
     * The previous version diffed events between polls and skipped anything older
     * than the last poll's clock. Usage events are written with a lag of a second
     * or two, so an event that arrived late fell into that gap and was discarded
     * forever. Opening a payment app three times and having one of them register
     * is exactly what that looks like.
     *
     * Reading a whole window and keeping only the most recent resume can't drop
     * anything, because it never depends on having seen an earlier reading.
     */
    private fun poll() {
        val manager = getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - WINDOW_MS, now)

        val event = UsageEvents.Event()
        var frontAt = 0L
        var front: String? = null
        var darkAt = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val pkg = event.packageName ?: continue
                    if (SessionTracker.isTransparent(pkg)) continue
                    if (event.timeStamp >= frontAt) {
                        frontAt = event.timeStamp
                        front = pkg
                    }
                }
                // Locking the phone leaves the payment app on top as far as
                // activity events are concerned, so it needs its own reading.
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN ->
                    if (event.timeStamp > darkAt) darkAt = event.timeStamp
            }
        }

        lastPollAt = now

        // A window with no readings in it says nothing about what changed — the
        // person has simply been sitting in one app. Leave the state alone.
        if (front == null && darkAt == 0L) return

        tracker.onForeground(if (darkAt > frontAt) null else front, packageName)
    }

    private fun statusNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Payment detection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Required by Android while Tally watches for payment apps."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tally is watching for payments")
            .setContentText("You'll be asked to log a payment after you use a payment app.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        running = false
        if (receiverRegistered) runCatching { unregisterReceiver(screenOffReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "detection_v2"
        private const val NOTIFICATION_ID = 4242
        private const val POLL_INTERVAL_MS = 1_500L

        /**
         * How far back each poll looks. Long enough that a late-written event is
         * always still inside it, short enough that a stale reading can't outlive
         * the app you actually switched to.
         */
        private const val WINDOW_MS = 12_000L

        // Aggressive battery managers kill foreground services without telling
        // anyone, and a dead watcher looks exactly like a quiet week. Settings
        // reads these so the difference is visible.
        @Volatile var running: Boolean = false; private set
        @Volatile var lastPollAt: Long = 0L; private set
        @Volatile var lastError: String? = null; private set

        /** Usage Access is a special permission — a plain permission check won't see it. */
        fun hasUsageAccess(context: Context): Boolean = runCatching {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        fun openUsageAccessSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        /**
         * Returns whether the start was accepted. Android 12+ refuses to let an
         * app start a foreground service from the background in most situations,
         * so this genuinely fails sometimes and the caller has to say so rather
         * than assume detection came back.
         */
        fun start(context: Context): Boolean {
            if (!hasUsageAccess(context)) return false
            return runCatching {
                context.startForegroundService(Intent(context, UsageWatcherService::class.java))
                true
            }.onFailure { lastError = "start: ${it.message}" }.getOrDefault(false)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, UsageWatcherService::class.java)) }
        }
    }
}
