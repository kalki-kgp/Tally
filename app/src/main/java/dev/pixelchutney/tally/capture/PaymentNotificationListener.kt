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
 * Listens to three kinds of sender and nothing else: the watched UPI apps, bank
 * apps, and the SMS app — where every Indian bank texts a debit within seconds.
 * The bank text is the one that always arrives, whichever app paid.
 *
 * This is a notification listener, not an accessibility service. The payment apps
 * that refuse to work alongside accessibility services (Navi, CRED) check for
 * those specifically; this is a different permission.
 *
 * Reading is done by Haiku (`PaymentExtractor`), not by patterns: bank wording
 * varies too much for hand-written rules to keep up. What reaches the model is
 * gated by *who sent it*, never by what it says — a payment app, a bank app, or
 * a text from a bank's sender ID. Texts from people never leave the phone.
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

        // Cheap reject before touching the extras: most notifications on a phone
        // come from apps this could never be interested in.
        val kind = KNOWN_KIND[pkg] ?: if (pkg in watched) Kind.PAYMENT_APP else return

        val text = textOf(notification)
        // A conversation in a payment app is people talking — "paid ₹500 for the
        // cab" in a WhatsApp chat is a message, not a payment. Only the SMS app's
        // conversations are read, and those pass the bank-sender check below.
        if (text.conversation && kind != Kind.SMS) return
        val title = text.title
        val body = text.body
        val postedAt = posted.postTime

        scope.launch {
            runCatching { handle(pkg, kind, title, body, postedAt) }
                .onFailure { Log.w(TAG, "could not read a notification from $pkg", it) }
        }
    }

    private suspend fun handle(pkg: String, kind: Kind, title: String, body: String, postedAt: Long) {
        if (!settings.current().readPaymentNotifications) return
        val isPaymentApp = kind == Kind.PAYMENT_APP

        // Texts from people are never sent anywhere — only bank sender IDs.
        if (kind == Kind.SMS && !looksLikeBankSender(title)) return
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
                        fromPaymentApp = isPaymentApp,
                    )
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
     * Banks text from sender IDs — "BP-HDFCBK-S", "JK-UNIONB-T" — never from a
     * contact name or a phone number. This decides only what may be read, not
     * what it says.
     */
    private fun looksLikeBankSender(title: String): Boolean {
        val sender = title.trim()
        return SENDER_ID.matches(sender) && sender.none { it.isLowerCase() }
    }

    /**
     * Title and body. SMS apps post a conversation, whose text field is a summary
     * of several messages; the newest message is the one that just arrived.
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

    private enum class Kind { PAYMENT_APP, BANK_APP, SMS }

    companion object {
        private const val TAG = "PaymentListener"

        private const val RETRIES = 2
        private const val RETRY_DELAY_MS = 8_000L
        private val SENDER_ID = Regex("[A-Z0-9]{2}-[A-Z0-9]{3,10}(?:-[A-Z])?|[A-Z]{5,10}")

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

        private val KNOWN_KIND: Map<String, Kind> = buildMap {
            listOf(
                "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
                "com.dreamplug.androidapp", "in.org.npci.upiapp", "com.naviapp",
                "money.super.payments", "com.hdfcbank.payzapp",
                "in.amazon.mShop.android.shopping", "com.mobikwik_new", "com.freecharge.android",
            ).forEach { put(it, Kind.PAYMENT_APP) }
            listOf(
                "com.snapwork.hdfc", "com.csam.icici.bank.imobile", "com.sbi.lotusintouch",
                "com.axis.mobile", "com.msf.kbank.mobile", "com.idfcfirstbank.optimus",
                "com.epifi.paisa", "money.jupiter",
            ).forEach { put(it, Kind.BANK_APP) }
            listOf(
                "com.google.android.apps.messaging", "com.samsung.android.messaging",
                "com.android.mms", "com.android.messaging", "com.oneplus.mms",
                "com.truecaller",
            ).forEach { put(it, Kind.SMS) }
        }
    }
}
