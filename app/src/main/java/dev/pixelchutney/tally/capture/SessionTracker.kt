package dev.pixelchutney.tally.capture

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.PaymentSessionEntity
import dev.pixelchutney.tally.data.db.SessionDao
import dev.pixelchutney.tally.data.db.WatchedAppDao
import dev.pixelchutney.tally.data.model.SessionOutcome
import dev.pixelchutney.tally.data.settings.CaptureMode
import dev.pixelchutney.tally.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/** A visit to a payment app that a payment notification has been matched to. */
data class VisitLink(
    val sessionId: Long,
    val packageName: String,
    /** Only known for a visit still in progress; otherwise it is in the saved sample. */
    val precededBy: String?,
    /** Still inside the payment app: the prompt waits until they leave. */
    val inProgress: Boolean,
)

/**
 * Turns "which app is in front right now" into payment sessions.
 *
 * The whole design hinges on one judgement: prompt when the person *leaves* the
 * payment app, not when they open it. Leaving is when the payment is finished and
 * the amount is still in their head.
 *
 * This reads the foreground app as a *state*, not as a stream of transitions. An
 * earlier version consumed open/close events one by one, which meant a single
 * dropped event left the tracker permanently convinced a payment app was still on
 * screen: every later visit to that app was swallowed as "already open", and no
 * prompt ever came. Reading the state instead makes every poll self-correcting —
 * a missed reading costs a second and a half, never the session.
 */
@Singleton
class SessionTracker @Inject constructor(
    private val context: Context,
    private val sessions: SessionDao,
    private val watchedApps: WatchedAppDao,
    private val settings: SettingsStore,
    private val notifier: PromptNotifier,
    private val metadata: MetadataCollector,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    private var activePackage: String? = null
    @Volatile private var activeSessionId: Long? = null
    private var activeStartedAt: Long = 0L
    private var exitJob: Job? = null

    /**
     * Visits a payment notification has already been matched to, with when. A
     * visit in here needs no prompt: its amount is known and it is saved.
     */
    private val claimed = mutableMapOf<Long, Long>()

    private val _visitsEnded = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /**
     * Visits that ended with their payment already read. The prompt for those
     * goes out on leaving, like every other prompt — see `PaymentIngestor`.
     */
    val visitsEndedWithPayment: SharedFlow<Long> = _visitsEnded.asSharedFlow()

    fun isInProgress(sessionId: Long): Boolean = activeSessionId == sessionId

    /** Visits waiting a short while for a payment notification before asking. */
    private val graceJobs = mutableMapOf<Long, Job>()

    /** The last non-payment app seen in front, kept as the payment's lead-in. */
    private var lastOtherApp: String? = null
    private var sessionPrecededBy: String? = null

    /**
     * Reports what is on screen right now. `null` means the phone is locked or
     * the screen is off, which counts as leaving.
     *
     * Safe to call on every poll with an unchanged value — repeats are cheap and
     * the repetition is the point.
     */
    fun onForeground(packageName: String?, selfPackage: String) {
        scope.launch {
            mutex.withLock {
                // Tally's own screens count as leaving the payment app.
                val front = packageName?.takeIf { it != selfPackage }
                val watched = front?.let { watchedApps.byPackage(it)?.takeIf { app -> app.enabled } }

                if (watched != null) {
                    // Already inside this app: any exit timer was a false alarm.
                    if (activePackage == front) {
                        cancelExitTimer()
                        return@withLock
                    }
                    closeActiveSession(promptIfWorthwhile = true)
                    openSession(front)
                } else {
                    // What you were doing before you paid. Coming from a food app
                    // and coming from the launcher are very different payments,
                    // and this is the only place that difference is visible.
                    if (front != null) lastOtherApp = front
                    if (activePackage != null) scheduleExit()
                }
            }
        }
    }

    /** The screen going off ends any visit to a payment app. */
    fun onScreenOff() {
        scope.launch { mutex.withLock { if (activePackage != null) scheduleExit() } }
    }

    private suspend fun openSession(packageName: String) {
        activePackage = packageName
        sessionPrecededBy = lastOtherApp
        activeStartedAt = Time.now()
        activeSessionId = sessions.insert(
            PaymentSessionEntity(
                packageName = packageName,
                startedAt = activeStartedAt,
                outcome = SessionOutcome.OPEN,
            )
        )
        Log.d(TAG, "session open: $packageName")
    }

    /**
     * Drops the pending exit timer.
     *
     * The identity check matters: when a visit ends on the debounce, this runs
     * *inside* the timer's own coroutine, and cancelling it there would kill the
     * coroutine mid-write — the next suspending call throws and the session is
     * never stamped as ended.
     */
    private suspend fun cancelExitTimer() {
        val pending = exitJob
        exitJob = null
        if (pending != null && pending !== coroutineContext[Job]) pending.cancel()
    }

    private fun scheduleExit() {
        // Deliberately does NOT restart a timer that is already counting down.
        // Some launchers report themselves resumed every second or so, and
        // cancelling on each one would postpone the exit forever.
        if (exitJob?.isActive == true) return
        exitJob = scope.launch {
            val debounce = settings.current().debounceSeconds.coerceIn(0, 30)
            delay(debounce * 1000L)
            mutex.withLock { closeActiveSession(promptIfWorthwhile = true) }
        }
    }

    /**
     * Ends the visit and asks about it.
     *
     * The row is stamped with `endedAt` before anything that could fail. An
     * earlier version read settings and looked up the app first, so any failure
     * in that work left the session looking permanently in progress while the
     * in-memory state had already moved on — invisible, and unrecoverable.
     */
    private suspend fun closeActiveSession(promptIfWorthwhile: Boolean) {
        val sessionId = activeSessionId ?: return
        val packageName = activePackage ?: return
        val startedAt = activeStartedAt

        activeSessionId = null
        activePackage = null
        cancelExitTimer()

        val endedAt = Time.now()
        val durationSeconds = (endedAt - startedAt) / 1000

        try {
            sessions.markEnded(sessionId, endedAt)

            // The payment notification arrived while the app was still open, so the
            // amount is known. The prompt still goes out now, on leaving — with the
            // amount filled in instead of asked for.
            if (sessionId in claimed) {
                Log.d(TAG, "visit to $packageName already has its payment")
                _visitsEnded.tryEmit(sessionId)
                return
            }

            val config = settings.current()
            if (!promptIfWorthwhile || !config.promptsEnabled) {
                sessions.byId(sessionId)?.let {
                    sessions.update(it.copy(outcome = SessionOutcome.DISMISSED))
                }
                return
            }

            // Sampled here, not when anything is typed: this is the moment the
            // payment app was closed, so it is the moment the phone is still at
            // the place where the money was spent. It is kept on the session row,
            // because a visit can be sorted hours after this process has died.
            metadata.startCapture(sessionId, sessionPrecededBy)

            // Asking now asks now, exactly as it always did. If the bank's text
            // lands afterwards, the prompt is rewritten in place with the amount.
            if (config.captureMode == CaptureMode.SORT_LATER &&
                readsPaymentNotifications(config.readPaymentNotifications)
            ) {
                // Sorting later: nothing is shown, so there is time to wait for a
                // bank text before filing the visit as "no amount found".
                graceJobs[sessionId] = scope.launch {
                    delay(NOTIFICATION_GRACE_MS)
                    mutex.withLock {
                        graceJobs.remove(sessionId)
                        if (sessionId !in claimed) resolveWithoutAmount(sessionId, packageName, durationSeconds)
                    }
                }
            } else {
                resolveWithoutAmount(sessionId, packageName, durationSeconds)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "closing session failed", t)
        }
    }

    /**
     * A visit ended and no payment notification explained it. Either there was
     * no payment, or the app and the bank both stayed quiet — only the owner
     * knows which. "Ask now" asks; "sort later" files it in "To sort", where it
     * waits visibly instead of interrupting.
     */
    private suspend fun resolveWithoutAmount(sessionId: Long, packageName: String, durationSeconds: Long) {
        val stored = sessions.byId(sessionId) ?: return
        if (stored.linkedTransactionId != null) return
        when (settings.current().captureMode) {
            CaptureMode.ASK_NOW -> {
                sessions.update(stored.copy(promptedAt = Time.now()))
                Log.d(TAG, "prompting for $packageName after ${durationSeconds}s")
                notifier.askForAmount(
                    sessionId = sessionId,
                    packageName = packageName,
                    appLabel = watchedApps.byPackage(packageName)?.label ?: packageName,
                )
            }
            CaptureMode.SORT_LATER -> sessions.update(stored.copy(outcome = SessionOutcome.TO_SORT))
        }
    }

    /**
     * Matches a payment notification to the visit it came from.
     *
     * The visit still in progress wins — the notification landed while the app
     * was open. Otherwise the most recent unanswered visit that ended in the last
     * few minutes, preferring one to the app that posted the notification. Its
     * prompt then shows the amount instead of asking for it.
     */
    suspend fun claimForPayment(
        notifyingPackage: String?,
        at: Long,
        /** Only a visit to [notifyingPackage] itself — for amounts that are only offered. */
        samePackageOnly: Boolean = false,
    ): VisitLink? = mutex.withLock {
        val now = Time.now()
        claimed.entries.removeAll { now - it.value > CLAIM_MEMORY_MS }

        val activeId = activeSessionId
        val activePkg = activePackage
        if (activeId != null && activePkg != null && activeId !in claimed &&
            (!samePackageOnly || activePkg == notifyingPackage)
        ) {
            claimed[activeId] = now
            return@withLock VisitLink(activeId, activePkg, sessionPrecededBy, inProgress = true)
        }

        val candidates = sessions.claimable(at - CLAIM_WINDOW_MS).filter { it.id !in claimed }
        val pick = candidates.firstOrNull { it.packageName == notifyingPackage }
            ?: candidates.firstOrNull()?.takeIf { !samePackageOnly }
            ?: return@withLock null

        claimed[pick.id] = now
        graceJobs.remove(pick.id)?.cancel()
        // Any "how much?" prompt showing for this visit is left in place for the
        // ingestor to rewrite with the amount — same notification, no flicker.
        VisitLink(pick.id, pick.packageName, precededBy = null, inProgress = false)
    }

    private fun readsPaymentNotifications(enabled: Boolean): Boolean =
        enabled && NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    /**
     * Counts "no payment" answers so Settings can point out an app that is never
     * worth asking about.
     *
     * It used to mute the app for two hours after three of them. That silently
     * traded away real payments to save three notifications, and since nothing
     * reported the mute, a muted app was indistinguishable from broken detection:
     * the visit was recorded as dismissed and no prompt ever appeared. Turning an
     * app off is now the only thing that stops it asking, and that is a switch you
     * can see.
     */
    suspend fun recordNoPayment(packageName: String) {
        val app = watchedApps.byPackage(packageName) ?: return
        watchedApps.update(app.copy(consecutiveNoPayment = app.consecutiveNoPayment + 1))
    }

    suspend fun recordLogged(packageName: String) {
        val app = watchedApps.byPackage(packageName) ?: return
        watchedApps.update(app.copy(consecutiveNoPayment = 0, mutedUntil = 0))
    }

    fun onServiceDisconnected() {
        scope.launch { mutex.withLock { closeActiveSession(promptIfWorthwhile = false) } }
    }

    companion object {
        private const val TAG = "SessionTracker"

        /** How long a finished visit waits for a payment notification. */
        private const val NOTIFICATION_GRACE_MS = 45_000L
        /** How far back a notification may reach for the visit it belongs to. */
        private const val CLAIM_WINDOW_MS = 5 * 60_000L
        private const val CLAIM_MEMORY_MS = 60 * 60_000L

        /** Windows that appear over a payment app without meaning you left it. */
        private val TRANSPARENT = setOf(
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.microsoft.swiftkey",
            "com.touchtype.swiftkey",
            "android",
        )

        fun isTransparent(packageName: String): Boolean =
            packageName in TRANSPARENT || packageName.contains("inputmethod", ignoreCase = true)
    }
}
