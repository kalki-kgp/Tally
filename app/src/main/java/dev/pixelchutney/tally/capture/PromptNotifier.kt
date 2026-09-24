package dev.pixelchutney.tally.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dev.pixelchutney.tally.MainActivity
import dev.pixelchutney.tally.R
import dev.pixelchutney.tally.capture.ui.CaptureActivity
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.data.db.CategoryEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The whole entry flow lives in the notification shade.
 *
 * It runs in three steps against one notification id, so the shade never fills up
 * with a trail: type the amount, tap a category, and it is saved. The note comes
 * afterwards and is optional, because blocking a save on an optional field is how
 * expense trackers end up unused.
 */
@Singleton
class PromptNotifier @Inject constructor(
    private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    init { ensureChannels() }

    private fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        LEGACY_CHANNELS.forEach { runCatching { system?.deleteNotificationChannel(it) } }

        val capture = NotificationChannel(
            CHANNEL_CAPTURE,
            "Log a payment",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Asks you to record a payment right after you leave a payment app."
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 40)
        }
        val insight = NotificationChannel(
            CHANNEL_INSIGHT,
            "Insights and budgets",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Weekly digests, budget pace and unusual spending." }

        // Silent on purpose. A payment read from a notification is already safe in
        // the ledger; the only question left is what it was for, and that can wait.
        val logged = NotificationChannel(
            CHANNEL_LOGGED,
            "Payments logged",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Quietly shows a payment Tally read, with buttons to sort it." }

        val health = NotificationChannel(
            CHANNEL_HEALTH,
            "Detection health",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Tells you when payment detection has stopped working." }

        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannels(listOf(capture, logged, insight, health))
    }

    // ── Step 1: how much? ─────────────────────────────────────────────────────

    fun askForAmount(sessionId: Long, packageName: String, appLabel: String) {
        val amountInput = RemoteInput.Builder(KEY_AMOUNT)
            .setLabel("Amount in ₹")
            .build()

        val builder = base(sessionId)
            .setContentTitle("Did you pay in $appLabel?")
            .setContentText("Type the amount to log it.")
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Amount",
                    broadcast(sessionId, packageName, ACTION_AMOUNT, mutable = true),
                )
                    .addRemoteInput(amountInput)
                    .setAllowGeneratedReplies(false)
                    .build()
            )
            .addAction(0, "No payment", broadcast(sessionId, packageName, ACTION_NO_PAYMENT))
            .setDeleteIntent(broadcast(sessionId, packageName, ACTION_DISMISS))

        post(sessionId, builder.build())
    }

    // ── Step 2: what for? ─────────────────────────────────────────────────────

    fun askForCategory(
        sessionId: Long,
        packageName: String,
        amountPaise: Long,
        categories: List<CategoryEntity>,
    ) {
        val quick = categories.take(2)
        val builder = base(sessionId)
            .setContentTitle(Money.format(amountPaise))
            .setContentText("Pick a category to save it.")

        quick.forEach { category ->
            builder.addAction(
                0,
                "${category.emoji} ${category.name}",
                broadcast(sessionId, packageName, ACTION_CATEGORY, categoryId = category.id),
            )
        }

        // Everything else, as typed choices rather than a fourth button Android
        // would refuse to show.
        val categoryInput = RemoteInput.Builder(KEY_CATEGORY)
            .setLabel("Category")
            .setChoices(categories.map { it.name }.toTypedArray())
            .build()

        builder.addAction(
            NotificationCompat.Action.Builder(
                0,
                "Other…",
                broadcast(sessionId, packageName, ACTION_CATEGORY, mutable = true),
            )
                .addRemoteInput(categoryInput)
                .setAllowGeneratedReplies(false)
                .build()
        )
        builder.setDeleteIntent(broadcast(sessionId, packageName, ACTION_DISMISS))

        post(sessionId, builder.build())
    }

    /** Retry state when a typed category matched nothing. */
    fun categoryNotRecognised(
        sessionId: Long,
        packageName: String,
        amountPaise: Long,
        typed: String,
        categories: List<CategoryEntity>,
    ) {
        askForCategory(sessionId, packageName, amountPaise, categories)
        val builder = base(sessionId)
            .setContentTitle(Money.format(amountPaise))
            .setContentText("No category called \"$typed\". Pick one below.")
        categories.take(2).forEach { category ->
            builder.addAction(
                0,
                "${category.emoji} ${category.name}",
                broadcast(sessionId, packageName, ACTION_CATEGORY, categoryId = category.id),
            )
        }
        val categoryInput = RemoteInput.Builder(KEY_CATEGORY)
            .setLabel("Category")
            .setChoices(categories.map { it.name }.toTypedArray())
            .build()
        builder.addAction(
            NotificationCompat.Action.Builder(
                0,
                "Other…",
                broadcast(sessionId, packageName, ACTION_CATEGORY, mutable = true),
            )
                .addRemoteInput(categoryInput)
                .setAllowGeneratedReplies(false)
                .build()
        )
        post(sessionId, builder.build())
    }

    // ── Step 3: saved, note optional ──────────────────────────────────────────

    fun confirmSaved(
        sessionId: Long,
        packageName: String,
        transactionId: Long,
        amountPaise: Long,
        categoryLabel: String,
    ) {
        val noteInput = RemoteInput.Builder(KEY_NOTE).setLabel("What was it for?").build()

        val builder = base(sessionId)
            .setContentTitle("Saved ${Money.format(amountPaise)} · $categoryLabel")
            .setContentText("Add a note, or swipe this away.")
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Add note",
                    broadcast(
                        sessionId, packageName, ACTION_NOTE,
                        transactionId = transactionId, mutable = true,
                    ),
                )
                    .addRemoteInput(noteInput)
                    .setAllowGeneratedReplies(false)
                    .build()
            )
            .addAction(
                0,
                "Undo",
                broadcast(sessionId, packageName, ACTION_UNDO, transactionId = transactionId),
            )
            .setDeleteIntent(broadcast(sessionId, packageName, ACTION_DISMISS))

        post(sessionId, builder.build())
    }

    fun showNoteSaved(sessionId: Long, amountPaise: Long, note: String) {
        val builder = base(sessionId)
            .setContentTitle("Saved ${Money.format(amountPaise)}")
            .setContentText(note)
            .setAutoCancel(true)
            .setTimeoutAfter(4_000)
        post(sessionId, builder.build())
    }

    // ── Read from a payment notification ─────────────────────────────────────

    /**
     * A payment whose amount Tally read by itself, so only the category is asked.
     *
     * [loud] is the usual prompt after leaving a payment app — the same heads-up
     * as always, with the expected amount already in it. It takes over the
     * visit's own notification, so a "Did you pay?" already showing turns into
     * this rather than a second one appearing. Tapping it opens the sheet on the
     * payment, where a wrong amount can be corrected.
     *
     * Quiet (sort later) is one silent notification replaced by each new payment.
     */
    fun showLogged(
        transactionId: Long,
        amountPaise: Long,
        merchant: String?,
        ranked: List<CategoryEntity>,
        waiting: Int,
        loud: Boolean,
        sessionId: Long? = null,
    ) {
        val notificationId = if (sessionId != null) notificationIdFor(sessionId) else LOGGED_ID
        val builder = NotificationCompat.Builder(context, if (loud) CHANNEL_CAPTURE else CHANNEL_LOGGED)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(if (loud) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setCategory(if (loud) Notification.CATEGORY_REMINDER else Notification.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!loud)
            .setContentIntent(if (loud) editSheet(transactionId) else openInbox())
            .setContentTitle(listOfNotNull(Money.format(amountPaise), merchant).joinToString(" · "))
            .setContentText(
                when {
                    loud -> "Pick a category to save it. Tap to fix the amount."
                    waiting > 1 -> "Logged. $waiting payments to sort — tap one, or later."
                    else -> "Logged. What was it? Tap one, or sort it later."
                }
            )

        ranked.take(2).forEach { category ->
            builder.addAction(
                0,
                "${category.emoji} ${category.name}",
                sortBroadcast(transactionId, notificationId, ACTION_SORT, categoryId = category.id),
            )
        }
        val categoryInput = RemoteInput.Builder(KEY_CATEGORY)
            .setLabel("Category")
            // Ranked for this payment, not alphabetical: the likely ones come first.
            .setChoices(ranked.map { it.name }.toTypedArray())
            .build()
        builder.addAction(
            NotificationCompat.Action.Builder(
                0,
                "Other…",
                sortBroadcast(transactionId, notificationId, ACTION_SORT, mutable = true),
            )
                .addRemoteInput(categoryInput)
                .setAllowGeneratedReplies(false)
                .build()
        )
        runCatching { manager.notify(notificationId, builder.build()) }
    }

    /** Sorted from the shade. The note is still the most useful thing to add. */
    fun confirmSorted(notificationId: Int, transactionId: Long, amountPaise: Long, categoryLabel: String) {
        val noteInput = RemoteInput.Builder(KEY_NOTE).setLabel("What was it for?").build()
        val builder = NotificationCompat.Builder(context, CHANNEL_LOGGED)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openInbox())
            .setContentTitle("Saved ${Money.format(amountPaise)} · $categoryLabel")
            .setContentText("Add a note, or swipe this away.")
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Add note",
                    sortBroadcast(transactionId, notificationId, ACTION_SORT_NOTE, mutable = true),
                )
                    .addRemoteInput(noteInput)
                    .setAllowGeneratedReplies(false)
                    .build()
            )
        runCatching { manager.notify(notificationId, builder.build()) }
    }

    fun showSortedNote(notificationId: Int, amountPaise: Long, note: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_LOGGED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Saved ${Money.format(amountPaise)}")
            .setContentText(note)
            .setAutoCancel(true)
            .setTimeoutAfter(4_000)
        runCatching { manager.notify(notificationId, builder.build()) }
    }

    /** Clears the logged notification once what it shows has been sorted elsewhere. */
    fun clearLogged() = manager.cancel(LOGGED_ID)

    private fun openInbox(): PendingIntent = PendingIntent.getActivity(
        context,
        LOGGED_ID,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_INBOX)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun editSheet(transactionId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        (transactionId % Int.MAX_VALUE).toInt(),
        CaptureActivity.editIntent(context, transactionId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun sortBroadcast(
        transactionId: Long,
        notificationId: Int,
        action: String,
        categoryId: Long? = null,
        mutable: Boolean = false,
    ): PendingIntent {
        val intent = Intent(context, CaptureActionReceiver::class.java).apply {
            this.action = action
            putExtra(CaptureActionReceiver.EXTRA_TRANSACTION_ID, transactionId)
            putExtra(CaptureActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            categoryId?.let { putExtra(CaptureActionReceiver.EXTRA_CATEGORY_ID, it) }
        }
        val requestCode = (transactionId.toInt() * 131) + action.hashCode() + (categoryId?.toInt() ?: 0)
        val mutability = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            mutability or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // ── Shared ────────────────────────────────────────────────────────────────

    private fun base(sessionId: Long): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            // The flow updates this notification in place; letting a tap dismiss it
            // mid-way would lose the amount already typed.
            .setAutoCancel(false)
            .setContentIntent(openSheet(sessionId))

    private fun openSheet(sessionId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        sessionId.toInt(),
        CaptureActivity.intent(context, sessionId = sessionId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * [mutable] is mandatory, not a preference: Android rejects an entire
     * notification whose action carries a RemoteInput on an immutable
     * PendingIntent, because it has to write the typed text into the intent.
     * These all name an explicit receiver inside this app, so mutability costs
     * nothing here.
     */
    private fun broadcast(
        sessionId: Long,
        packageName: String,
        action: String,
        categoryId: Long? = null,
        transactionId: Long? = null,
        mutable: Boolean = false,
    ): PendingIntent {
        val intent = Intent(context, CaptureActionReceiver::class.java).apply {
            this.action = action
            putExtra(CaptureActionReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(CaptureActionReceiver.EXTRA_PACKAGE, packageName)
            categoryId?.let { putExtra(CaptureActionReceiver.EXTRA_CATEGORY_ID, it) }
            transactionId?.let { putExtra(CaptureActionReceiver.EXTRA_TRANSACTION_ID, it) }
        }
        // Distinct request codes, or Android reuses one PendingIntent for every action.
        val requestCode = (sessionId.toInt() * 71) + action.hashCode() + (categoryId?.toInt() ?: 0)
        val mutability =
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            mutability or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun post(sessionId: Long, notification: Notification) {
        runCatching { manager.notify(notificationIdFor(sessionId), notification) }
    }

    fun cancelPrompt(sessionId: Long) = manager.cancel(notificationIdFor(sessionId))

    fun canPost(): Boolean = manager.areNotificationsEnabled()

    fun showInsight(id: Int, title: String, body: String) {
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_INSIGHT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(id, notification) }
    }

    fun showHealthWarning(message: String) {
        val open = PendingIntent.getActivity(
            context,
            HEALTH_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_HEALTH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tally has stopped detecting payments")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            // The watchdog re-posts this every 15 minutes while it is true.
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(HEALTH_ID, notification) }
    }

    fun clearHealthWarning() = manager.cancel(HEALTH_ID)

    private fun notificationIdFor(sessionId: Long) = (CAPTURE_ID_BASE + sessionId).toInt()

    companion object {
        // Versioned: a blocked channel cannot be un-blocked in code, only replaced.
        const val CHANNEL_CAPTURE = "capture_v2"
        const val CHANNEL_INSIGHT = "insight_v2"
        const val CHANNEL_HEALTH = "health_v2"
        const val CHANNEL_LOGGED = "logged_v1"
        private val LEGACY_CHANNELS = listOf("capture", "insight", "health")

        const val KEY_AMOUNT = "tally_amount"
        const val KEY_CATEGORY = "tally_category"
        const val KEY_NOTE = "tally_note"

        const val ACTION_AMOUNT = "dev.pixelchutney.tally.AMOUNT"
        const val ACTION_CATEGORY = "dev.pixelchutney.tally.CATEGORY"
        const val ACTION_NOTE = "dev.pixelchutney.tally.NOTE"
        const val ACTION_UNDO = "dev.pixelchutney.tally.UNDO"
        const val ACTION_NO_PAYMENT = "dev.pixelchutney.tally.NO_PAYMENT"
        const val ACTION_DISMISS = "dev.pixelchutney.tally.DISMISS"
        const val ACTION_SORT = "dev.pixelchutney.tally.SORT"
        const val ACTION_SORT_NOTE = "dev.pixelchutney.tally.SORT_NOTE"

        const val CAPTURE_ID_BASE = 100_000L
        const val HEALTH_ID = 9001
        const val DIGEST_ID = 9002
        const val BUDGET_ID = 9003
        const val LOGGED_ID = 9004
    }
}
