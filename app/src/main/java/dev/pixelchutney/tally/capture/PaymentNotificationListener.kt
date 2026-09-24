package dev.pixelchutney.tally.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.pixelchutney.tally.data.db.WatchedAppDao
import dev.pixelchutney.tally.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * Parsing is cheap and happens off the main thread. Everything read that is not a
 * debit is dropped on the spot and never stored.
 */
@AndroidEntryPoint
class PaymentNotificationListener : NotificationListenerService() {

    @Inject lateinit var ingestor: PaymentIngestor
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

        if (kind == Kind.SMS) {
            // A friend's "paid ₹500 for the cab" must never become a payment.
            if (!PaymentNotificationParser.looksLikeBankSender(title)) return
            if (!PaymentNotificationParser.looksLikeBankMessage(body)) return
        }

        val parsed = PaymentNotificationParser.parse("$title\n$body") ?: return
        ingestor.ingest(
            payment = parsed,
            notifyingPackage = pkg,
            postedAt = postedAt,
            fromPaymentApp = isPaymentApp,
        )
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
