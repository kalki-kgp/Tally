package dev.pixelchutney.tally.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.pixelchutney.tally.data.model.Cadence
import dev.pixelchutney.tally.data.model.EntryMethod
import dev.pixelchutney.tally.data.model.Necessity
import dev.pixelchutney.tally.data.model.SessionOutcome

@Entity(
    tableName = "categories",
    indices = [Index("sortOrder")],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
    /** Index into CategoryPalette, so colours stay consistent across charts. */
    val colorIndex: Int,
    val defaultNecessity: Necessity = Necessity.UNSORTED,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)

@Entity(
    tableName = "merchants",
    indices = [Index(value = ["canonicalName"], unique = true)],
)
data class MerchantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "Blinkit" — what a human calls it. */
    val canonicalName: String,
    /** Raw strings seen in notifications, newline-separated. Grows as aliases appear. */
    val aliases: String = "",
    val defaultCategoryId: Long? = null,
    val defaultNecessity: Necessity? = null,
    val visitCount: Int = 0,
    val totalPaise: Long = 0,
    val lastSeenAt: Long = 0,
    /** True once a human confirmed the category, so AI never overrides it again. */
    val userConfirmed: Boolean = false,
)

@Entity(
    tableName = "transactions",
    indices = [Index("timestamp"), Index("categoryId"), Index("merchantId")],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_DEFAULT,
        ),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Negative means money came back (refund). */
    val amountPaise: Long,
    /** When the payment happened. */
    val timestamp: Long,
    /** When it got saved. The gap is the friction metric. */
    val loggedAt: Long,
    @ColumnInfo(defaultValue = "1")
    val categoryId: Long,
    val merchantId: Long? = null,
    /** Denormalised so lists render without a join on every row. */
    val merchantName: String? = null,
    val note: String? = null,
    val sourceApp: String? = null,
    val entryMethod: EntryMethod = EntryMethod.FULLY_MANUAL,
    val necessity: Necessity = Necessity.UNSORTED,
    /** Rent split, money moved to savings — real, but not "spending". */
    val excluded: Boolean = false,
    val sessionId: Long? = null,

    // ── Payment context ───────────────────────────────────────────────────────
    // Sampled at the moment of capture and stored unused for now. The point is to
    // have a history worth reasoning over by the time category suggestion is
    // built, rather than starting to collect on the day it is needed.
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyM: Float? = null,
    /** When the fix was taken. Not the same as `loggedAt` if the prompt sat unanswered. */
    val locationAt: Long? = null,
    val placeName: String? = null,
    val placeAddress: String? = null,
    /** Wi-Fi network name, or "Mobile data" / "Offline". */
    val networkName: String? = null,
    /** See `LocationSource`: live, last_known, cached, unavailable, denied. */
    val locationSource: String? = null,
    val networkOperator: String? = null,
    val roaming: Boolean? = null,
    /** The app in front before the payment app was opened. */
    val precedingApp: String? = null,
    val calendarEvent: String? = null,

    // ── Read from a payment notification ─────────────────────────────────────
    /**
     * False while a payment read from a notification waits in "To sort". The
     * amount is real and counts from the moment it lands; the category is only a
     * guess until this flips.
     */
    @ColumnInfo(defaultValue = "1")
    val reviewed: Boolean = true,
    /** The payee exactly as the notification wrote it — a name or a UPI ID. */
    val payee: String? = null,
    /** 12-digit UPI reference. Tells one payment seen twice from two payments. */
    val upiRef: String? = null,
    /** The app whose notification carried the amount: a UPI app or the SMS app. */
    val detectedFrom: String? = null,
)

@Entity(tableName = "chats", indices = [Index("updatedAt")])
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Taken from the first question asked, so the list reads like the questions. */
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "chat_messages",
    indices = [Index("chatId")],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val fromUser: Boolean,
    val text: String,
    /** Names of the queries that produced the answer, newline-separated. */
    val toolsUsed: String = "",
    val createdAt: Long,
)

@Entity(
    tableName = "payment_sessions",
    indices = [Index("startedAt"), Index("packageName")],
)
data class PaymentSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val outcome: SessionOutcome = SessionOutcome.OPEN,
    val linkedTransactionId: Long? = null,
    /** Amount typed into the prompt, held between the amount and category steps. */
    val parsedAmountPaise: Long? = null,
    val parsedMerchant: String? = null,
    val promptedAt: Long? = null,
    /**
     * The `PaymentContext` sampled when the visit ended, as JSON. Held in the row
     * rather than only in memory, because a visit sorted hours later has long
     * outlived the process that sampled it.
     */
    val contextJson: String? = null,
)

@Entity(tableName = "budgets", indices = [Index(value = ["categoryId"], unique = true)])
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null is the whole-month budget. */
    val categoryId: Long? = null,
    val monthlyLimitPaise: Long,
    val active: Boolean = true,
)

@Entity(tableName = "recurring_rules", indices = [Index("merchantId")])
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantId: Long,
    val merchantName: String,
    val expectedAmountPaise: Long,
    val cadence: Cadence,
    val nextExpectedAt: Long,
    val lastSeenAt: Long,
    val occurrences: Int,
    /** Detected rules stay unconfirmed until tapped, so guesses never inflate totals. */
    val confirmed: Boolean = false,
    val dismissed: Boolean = false,
)

@Entity(tableName = "watched_apps", indices = [Index(value = ["packageName"], unique = true)])
data class WatchedAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val label: String,
    val enabled: Boolean = true,
    /**
     * Dead. Auto-muting was removed — see `SessionTracker.recordNoPayment`. The
     * column stays so the schema needs no migration, and nothing reads it, so any
     * value left over from an older build is already inert.
     */
    val mutedUntil: Long = 0,
    /** Drives a hint in Settings, nothing else. */
    val consecutiveNoPayment: Int = 0,
)

@Entity(tableName = "ai_cache", indices = [Index(value = ["cacheKey"], unique = true)])
data class AiCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cacheKey: String,
    val response: String,
    val model: String,
    val createdAt: Long,
)

@Entity(tableName = "insights", indices = [Index("createdAt")])
data class InsightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** WEEKLY_DIGEST, MONTHLY_REVIEW, ANOMALY, RECURRING, BUDGET */
    val kind: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val periodStart: Long,
    val periodEnd: Long,
    val model: String? = null,
    val dismissed: Boolean = false,
)
