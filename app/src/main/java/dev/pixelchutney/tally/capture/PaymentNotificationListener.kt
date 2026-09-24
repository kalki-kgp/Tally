package dev.pixelchutney.tally.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.pixelchutney.tally.ai.Extraction
import dev.pixelchutney.tally.ai.PaymentExtractor
import dev.pixelchutney.tally.data.db.WatchedAppDao
import dev.pixelchutney.tally.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reads the amount out of payment notifications, so it never has to be typed.
 *
 * Listens to the payment apps and nothing else — the watched list plus the
 * known UPI apps. The owner asked for exactly this: the payment app's own
 * notification is the one that reliably arrives; bank texts often do not.
 *
 * This is a notification listener, not an accessibility service. The payment apps
 * that refuse to work alongside accessibility services (Navi, CRED) check for
 * those specifically; this is a different permission.
 *
 * Reading is done by Haiku (`PaymentExtractor`), not by patterns: bank wording
 * varies too much for hand-written rules to keep up. What reaches the model is
 * gated by *who sent it* — a payment app — never by what it says.
 * Anything that is not money going out is dropped on the spot and never stored.
 */
@AndroidEntryPoint
class PaymentNotificationListener : NotificationListenerService() {

    @Inject lateinit var ingestor: PaymentIngestor
    @Inject lateinit var extractor: PaymentExtractor
    @Inject lateinit var watchedApps: WatchedAppDao
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var scope: CoroutineScope

    /** Watched packages, kept current from the database so a lookup never waits. */
    @Volatile private var watched: Set<String> = emptySet()
    private var watchJob: Job? = null

    override fun onListenerConnected() {
        connected = true
        watchJob?.cancel()
        watchJob = scope.launch {
            watchedApps.allFlow().collect { apps -> watched = apps.map { it.packageName }.toSet() }
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        watchJob?.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        val pkg = posted.packageName ?: return
        if (pkg == packageName) return
        val notification = posted.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // Only the payment apps' own notifications. Bank texts and bank apps were
        // read once and dropped at the owner's request: they do not always come,
        // and the payment app's notification is the one that does.
        if (pkg !in PAYMENT_APPS && pkg !in watched) return

        val text = textOf(notification)
        // A conversation in a payment app is people talking — "paid ₹500 for the
        // cab" in a WhatsApp chat is a message, not a payment.
        if (text.conversation) return
        val title = text.title
        val body = text.body
        val postedAt = posted.postTime

        scope.launch {
            runCatching { handle(pkg, title, body, postedAt) }
                .onFailure { Log.w(TAG, "could not read a notification from $pkg", it) }
        }
    }

    private suspend fun handle(pkg: String, title: String, body: String, postedAt: Long) {
        if (!settings.current().readPaymentNotifications) return
        // No digit, no amount: not worth a call.
        if ((title + body).none { it.isDigit() }) return

        val source = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        // A payment notification usually lands while the phone is online, but not
        // always. A couple of short retries cover a flaky moment without holding
        // on to the text for long.
        for (attempt in 0..RETRIES) {
            when (val result = extractor.extract(source, title, body)) {
                is Extraction.Payment -> {
                    lastProblem = null
                    ingestor.ingest(
                        payment = result.payment,
                        notifyingPackage = pkg,
                        postedAt = postedAt,
                        fromPaymentApp = true,
                    )
                    return
                }
                is Extraction.Incoming -> {
                    lastProblem = null
                    ingestor.offer(result.payment, pkg, postedAt)
                    return
                }
                Extraction.NotAPayment -> return
                is Extraction.Blocked -> {
                    lastProblem = result.reason
                    return
                }
                is Extraction.Unavailable -> {
                    lastProblem = "Couldn't reach Haiku: ${result.reason}"
                    if (attempt < RETRIES) delay(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
    }

    /**
     * Title and body, and whether it is a conversation (a chat, never a payment).
     */
    private class NotificationText(val title: String, val body: String, val conversation: Boolean)

    private fun textOf(notification: Notification): NotificationText {
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val messaging = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                ?.messages?.lastOrNull()?.text?.toString()
        }.getOrNull()
        val body = messaging
            ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""
        return NotificationText(title, body, conversation = messaging != null)
    }

    companion object {
        private const val TAG = "PaymentListener"

        private const val RETRIES = 2
        private const val RETRY_DELAY_MS = 8_000L

        /**
         * Why the last payment notification could not be read, or null when the
         * last one worked. Shown in Settings: without it, AI being off looks
         * exactly like a broken listener.
         */
        @Volatile var lastProblem: String? = null
            private set

        /** True while Android has this listener bound. Read by Settings. */
        @Volatile var connected: Boolean = false
            private set

        private val PAYMENT_APPS: Set<String> = setOf(
            "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
            "com.dreamplug.androidapp", "in.org.npci.upiapp", "com.naviapp",
            "money.super.payments", "com.hdfcbank.payzapp",
            "in.amazon.mShop.android.shopping", "com.mobikwik_new", "com.freecharge.android",
        )
    }
}
