package dev.pixelchutney.tally.data.db

import androidx.room.Embedded

/** One row per calendar day: powers the daily bars, heatmap and streak counters. */
data class DailyTotal(
    val day: String,          // yyyy-MM-dd in device-local time
    val totalPaise: Long,
    val txnCount: Int,
)

data class CategoryTotal(
    val categoryId: Long,
    val name: String,
    val emoji: String,
    val colorIndex: Int,
    val totalPaise: Long,
    val txnCount: Int,
)

data class MerchantTotal(
    val merchantId: Long?,
    val merchantName: String,
    val totalPaise: Long,
    val txnCount: Int,
    val lastAt: Long,
)

data class HourBucket(val hour: Int, val totalPaise: Long, val txnCount: Int)

data class WeekdayBucket(val weekday: Int, val totalPaise: Long, val txnCount: Int)

data class NecessityTotal(val necessity: String?, val totalPaise: Long, val txnCount: Int)

/** One stroke on the tally strip. Deliberately tiny — thousands may be drawn. */
data class TxnStroke(val id: Long, val timestamp: Long, val amountPaise: Long, val colorIndex: Int)

data class TransactionRow(
    @Embedded val txn: TransactionEntity,
    val categoryName: String,
    val categoryEmoji: String,
    val categoryColorIndex: Int,
)

data class SourceAppTotal(val sourceApp: String?, val totalPaise: Long, val txnCount: Int)

data class SessionOutcomeCount(val outcome: String?, val sessionCount: Int)
