package dev.pixelchutney.tally.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Every dashboard number is a query, not a Kotlin fold. With a few years of
 * transactions the difference is the whole feel of the app.
 *
 * "Spending" throughout means: not excluded, and money going out (`amountPaise > 0`).
 * Refunds are stored as negatives and deliberately left out of spend totals so a
 * ₹2,000 refund cannot make a bad week look good.
 */
@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(txn: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(txns: List<TransactionEntity>): List<Long>

    @Update suspend fun update(txn: TransactionEntity)
    @Delete suspend fun delete(txn: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: Long): TransactionEntity?

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    // ── A. How much ───────────────────────────────────────────────────────────

    @Query(
        """SELECT COALESCE(SUM(amountPaise), 0) FROM transactions
           WHERE excluded = 0 AND amountPaise > 0
             AND timestamp BETWEEN :from AND :to"""
    )
    fun spendBetweenFlow(from: Long, to: Long): Flow<Long>

    @Query(
        """SELECT COALESCE(SUM(amountPaise), 0) FROM transactions
           WHERE excluded = 0 AND amountPaise > 0
             AND timestamp BETWEEN :from AND :to"""
    )
    suspend fun spendBetween(from: Long, to: Long): Long

    @Query(
        """SELECT COUNT(*) FROM transactions
           WHERE excluded = 0 AND amountPaise > 0
             AND timestamp BETWEEN :from AND :to"""
    )
    suspend fun countBetween(from: Long, to: Long): Int

    @Query(
        """SELECT COALESCE(SUM(amountPaise), 0) FROM transactions
           WHERE excluded = 0 AND amountPaise > 0
             AND categoryId = :categoryId
             AND timestamp BETWEEN :from AND :to"""
    )
    suspend fun spendInCategory(categoryId: Long, from: Long, to: Long): Long

    /** Daily totals, gap-free days are simply absent — callers fill zeroes. */
    @Query(
        """SELECT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') AS day,
                  COALESCE(SUM(amountPaise), 0) AS totalPaise,
                  COUNT(*) AS txnCount
           FROM transactions
           WHERE excluded = 0 AND amountPaise > 0 AND timestamp BETWEEN :from AND :to
           GROUP BY day ORDER BY day ASC"""
    )
    fun dailyTotalsFlow(from: Long, to: Long): Flow<List<DailyTotal>>

    @Query(
        """SELECT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') AS day,
                  COALESCE(SUM(amountPaise), 0) AS totalPaise,
                  COUNT(*) AS txnCount
           FROM transactions
           WHERE excluded = 0 AND amountPaise > 0 AND timestamp BETWEEN :from AND :to
           GROUP BY day ORDER BY day ASC"""
    )
    suspend fun dailyTotals(from: Long, to: Long): List<DailyTotal>

    // ── B. Where it goes ──────────────────────────────────────────────────────

    @Query(
        """SELECT c.id AS categoryId, c.name AS name, c.emoji AS emoji,
                  c.colorIndex AS colorIndex,
                  COALESCE(SUM(t.amountPaise), 0) AS totalPaise,
                  COUNT(t.id) AS txnCount
           FROM transactions t JOIN categories c ON c.id = t.categoryId
           WHERE t.excluded = 0 AND t.amountPaise > 0 AND t.timestamp BETWEEN :from AND :to
           GROUP BY c.id HAVING totalPaise > 0 ORDER BY totalPaise DESC"""
    )
    fun categoryTotalsFlow(from: Long, to: Long): Flow<List<CategoryTotal>>

    @Query(
        """SELECT c.id AS categoryId, c.name AS name, c.emoji AS emoji,
                  c.colorIndex AS colorIndex,
                  COALESCE(SUM(t.amountPaise), 0) AS totalPaise,
                  COUNT(t.id) AS txnCount
           FROM transactions t JOIN categories c ON c.id = t.categoryId
           WHERE t.excluded = 0 AND t.amountPaise > 0 AND t.timestamp BETWEEN :from AND :to
           GROUP BY c.id HAVING totalPaise > 0 ORDER BY totalPaise DESC"""
    )
    suspend fun categoryTotals(from: Long, to: Long): List<CategoryTotal>

    @Query(
        """SELECT t.merchantId AS merchantId,
                  COALESCE(t.merchantName, 'Unnamed') AS merchantName,
                  COALESCE(SUM(t.amountPaise), 0) AS totalPaise,
                  COUNT(t.id) AS txnCount,
                  MAX(t.timestamp) AS lastAt
           FROM transactions t
           WHERE t.excluded = 0 AND t.amountPaise > 0 AND t.timestamp BETWEEN :from AND :to
           GROUP BY COALESCE(t.merchantName, 'Unnamed')
           ORDER BY totalPaise DESC LIMIT :limit"""
    )
    fun topMerchantsBySpendFlow(from: Long, to: Long, limit: Int): Flow<List<MerchantTotal>>

    @Query(
        """SELECT t.merchantId AS merchantId,
                  COALESCE(t.merchantName, 'Unnamed') AS merchantName,
                  COALESCE(SUM(t.amountPaise), 0) AS totalPaise,
                  COUNT(t.id) AS txnCount,
                  MAX(t.timestamp) AS lastAt
           FROM transactions t
           WHERE t.excluded = 0 AND t.amountPaise > 0 AND t.timestamp BETWEEN :from AND :to
           GROUP BY COALESCE(t.merchantName, 'Unnamed')
           ORDER BY txnCount DESC, totalPaise DESC LIMIT :limit"""
    )
    fun topMerchantsByCountFlow(from: Long, to: Long, limit: Int): Flow<List<MerchantTotal>>

    @Query(
        """SELECT t.merchantId AS merchantId,
                  COALESCE(t.merchantName, 'Unnamed') AS merchantName,
                  COALESCE(SUM(t.amountPaise), 0) AS totalPaise,
                  COUNT(t.id) AS txnCount,
                  MAX(t.timestamp) AS lastAt
           FROM transactions t
           WHERE t.excluded = 0 AND t.amountPaise > 0 AND t.timestamp BETWEEN :from AND :to
           GROUP BY COALESCE(t.merchantName, 'Unnamed')
           ORDER BY totalPaise DESC LIMIT :limit"""
    )
    suspend fun topMerchantsBySpend(from: Long, to: Long, limit: Int): List<MerchantTotal>

    @Query(
        """SELECT t.necessity AS necessity,
                  COALESCE(SUM(t.amountPaise), 0) AS totalPaise,
                  COUNT(t.id) AS txnCount
           FROM transactions t
           WHERE t.excluded = 0 AND t.amountPaise > 0 AND t.timestamp BETWEEN :from AND :to
           GROUP BY t.necessity"""
    )
    fun necessitySplitFlow(from: Long, to: Long): Flow<List<NecessityTotal>>

    @Query(
        """SELECT t.necessity AS necessity,
                  COALESCE(SUM(t.amountPaise), 0) AS totalPaise,
                  COUNT(t.id) AS txnCount
           FROM transactions t
           WHERE t.excluded = 0 AND t.amountPaise > 0 AND t.timestamp BETWEEN :from AND :to
           GROUP BY t.necessity"""
    )
    suspend fun necessitySplit(from: Long, to: Long): List<NecessityTotal>

    // ── C. When and how ───────────────────────────────────────────────────────

    @Query(
        """SELECT CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
                  COALESCE(SUM(amountPaise), 0) AS totalPaise,
                  COUNT(*) AS txnCount
           FROM transactions
           WHERE excluded = 0 AND amountPaise > 0 AND timestamp BETWEEN :from AND :to
           GROUP BY hour ORDER BY hour ASC"""
    )
    fun hourProfileFlow(from: Long, to: Long): Flow<List<HourBucket>>

    /** `%w`: 0 = Sunday … 6 = Saturday. */
    @Query(
        """SELECT CAST(strftime('%w', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) AS weekday,
                  COALESCE(SUM(amountPaise), 0) AS totalPaise,
                  COUNT(*) AS txnCount
           FROM transactions
           WHERE excluded = 0 AND amountPaise > 0 AND timestamp BETWEEN :from AND :to
           GROUP BY weekday ORDER BY weekday ASC"""
    )
    fun weekdayProfileFlow(from: Long, to: Long): Flow<List<WeekdayBucket>>

    /** Death by a thousand small payments. */
    @Query(
        """SELECT COALESCE(SUM(amountPaise), 0) FROM transactions
           WHERE excluded = 0 AND amountPaise > 0 AND amountPaise < :thresholdPaise
             AND timestamp BETWEEN :from AND :to"""
    )
    fun smallLeakTotalFlow(from: Long, to: Long, thresholdPaise: Long): Flow<Long>

    @Query(
        """SELECT COUNT(*) FROM transactions
           WHERE excluded = 0 AND amountPaise > 0 AND amountPaise < :thresholdPaise
             AND timestamp BETWEEN :from AND :to"""
    )
    fun smallLeakCountFlow(from: Long, to: Long, thresholdPaise: Long): Flow<Int>

    /** Raw amounts for percentile work SQLite will not do for us. */
    @Query(
        """SELECT amountPaise FROM transactions
           WHERE excluded = 0 AND amountPaise > 0 AND timestamp BETWEEN :from AND :to
           ORDER BY amountPaise ASC"""
    )
    suspend fun sortedAmounts(from: Long, to: Long): List<Long>

    /** Strokes for the tally strip: one per transaction, in time order. */
    @Query(
        """SELECT t.id AS id, t.timestamp AS timestamp, t.amountPaise AS amountPaise,
                  c.colorIndex AS colorIndex
           FROM transactions t JOIN categories c ON c.id = t.categoryId
           WHERE t.excluded = 0 AND t.amountPaise > 0 AND t.timestamp BETWEEN :from AND :to
           ORDER BY t.timestamp ASC"""
    )
    fun strokesFlow(from: Long, to: Long): Flow<List<TxnStroke>>

    @Query(
        """SELECT sourceApp AS sourceApp, COALESCE(SUM(amountPaise), 0) AS totalPaise,
                  COUNT(*) AS txnCount
           FROM transactions
           WHERE excluded = 0 AND amountPaise > 0 AND timestamp BETWEEN :from AND :to
           GROUP BY sourceApp ORDER BY totalPaise DESC"""
    )
    fun sourceAppTotalsFlow(from: Long, to: Long): Flow<List<SourceAppTotal>>

    /** Median lag between paying and logging — the "under 5 seconds" promise, measured. */
    @Query(
        """SELECT (loggedAt - timestamp) FROM transactions
           WHERE entryMethod IN ('AUTO_PARSED', 'PROMPT_MANUAL')
             AND loggedAt >= timestamp AND timestamp BETWEEN :from AND :to
           ORDER BY (loggedAt - timestamp) ASC"""
    )
    suspend fun sortedLogLags(from: Long, to: Long): List<Long>

    // ── D. Anomalies ──────────────────────────────────────────────────────────

    @Query(
        """SELECT t.*, c.name AS categoryName, c.emoji AS categoryEmoji,
                  c.colorIndex AS categoryColorIndex
           FROM transactions t JOIN categories c ON c.id = t.categoryId
           WHERE t.excluded = 0 AND t.amountPaise >= :minPaise
             AND t.timestamp BETWEEN :from AND :to
           ORDER BY t.amountPaise DESC LIMIT :limit"""
    )
    suspend fun largestTransactions(from: Long, to: Long, minPaise: Long, limit: Int): List<TransactionRow>

    @Query(
        """SELECT COUNT(*) FROM transactions
           WHERE merchantName = :merchantName AND timestamp < :before"""
    )
    suspend fun priorVisitsTo(merchantName: String, before: Long): Int

    /** Same merchant, same amount, minutes apart: almost always a double entry. */
    @Query(
        """SELECT COUNT(*) FROM transactions
           WHERE amountPaise = :amountPaise
             AND COALESCE(merchantName, '') = COALESCE(:merchantName, '')
             AND timestamp BETWEEN :from AND :to"""
    )
    suspend fun duplicateCandidates(amountPaise: Long, merchantName: String?, from: Long, to: Long): Int

    /** Occurrences of one merchant, oldest first — feeds recurring detection. */
    @Query(
        """SELECT * FROM transactions
           WHERE merchantName = :merchantName AND excluded = 0 AND amountPaise > 0
           ORDER BY timestamp ASC"""
    )
    suspend fun historyFor(merchantName: String): List<TransactionEntity>

    @Query(
        """SELECT DISTINCT COALESCE(merchantName, '') FROM transactions
           WHERE excluded = 0 AND amountPaise > 0 AND merchantName IS NOT NULL
             AND timestamp >= :since"""
    )
    suspend fun distinctMerchantNames(since: Long): List<String>

    // ── Lists ─────────────────────────────────────────────────────────────────

    @Query(
        """SELECT t.*, c.name AS categoryName, c.emoji AS categoryEmoji,
                  c.colorIndex AS categoryColorIndex
           FROM transactions t JOIN categories c ON c.id = t.categoryId
           ORDER BY t.timestamp DESC LIMIT :limit"""
    )
    fun recentFlow(limit: Int): Flow<List<TransactionRow>>

    @Query(
        """SELECT t.*, c.name AS categoryName, c.emoji AS categoryEmoji,
                  c.colorIndex AS categoryColorIndex
           FROM transactions t JOIN categories c ON c.id = t.categoryId
           WHERE (:from IS NULL OR t.timestamp >= :from)
             AND (:to IS NULL OR t.timestamp <= :to)
             AND (:categoryId IS NULL OR t.categoryId = :categoryId)
             AND (:necessity IS NULL OR t.necessity = :necessity)
             AND (:query IS NULL OR :query = ''
                  OR t.merchantName LIKE '%' || :query || '%'
                  OR t.note LIKE '%' || :query || '%'
                  OR c.name LIKE '%' || :query || '%'
                  OR CAST(t.amountPaise / 100 AS TEXT) LIKE :query || '%')
           ORDER BY t.timestamp DESC LIMIT :limit"""
    )
    fun searchFlow(
        from: Long?,
        to: Long?,
        categoryId: Long?,
        necessity: String?,
        query: String?,
        limit: Int,
    ): Flow<List<TransactionRow>>

    @Query(
        """SELECT t.*, c.name AS categoryName, c.emoji AS categoryEmoji,
                  c.colorIndex AS categoryColorIndex
           FROM transactions t JOIN categories c ON c.id = t.categoryId
           WHERE t.timestamp BETWEEN :from AND :to
           ORDER BY t.timestamp DESC"""
    )
    fun rowsBetweenFlow(from: Long, to: Long): Flow<List<TransactionRow>>

    @Query(
        """SELECT t.*, c.name AS categoryName, c.emoji AS categoryEmoji,
                  c.colorIndex AS categoryColorIndex
           FROM transactions t JOIN categories c ON c.id = t.categoryId
           WHERE t.timestamp BETWEEN :from AND :to
           ORDER BY t.timestamp ASC"""
    )
    suspend fun rowsBetween(from: Long, to: Long): List<TransactionRow>

    @Query("SELECT * FROM transactions ORDER BY timestamp ASC")
    suspend fun allForExport(): List<TransactionEntity>

    @Query("SELECT MIN(timestamp) FROM transactions")
    suspend fun earliestTimestamp(): Long?

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE id IN (:ids)")
    suspend fun recategorise(ids: List<Long>, categoryId: Long)

    @Query("UPDATE transactions SET categoryId = :fallbackId WHERE categoryId = :categoryId")
    suspend fun reassignCategory(categoryId: Long, fallbackId: Long)

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    // ── To sort ───────────────────────────────────────────────────────────────

    @Query(
        """SELECT t.*, c.name AS categoryName, c.emoji AS categoryEmoji,
                  c.colorIndex AS categoryColorIndex
           FROM transactions t JOIN categories c ON c.id = t.categoryId
           WHERE t.reviewed = 0
           ORDER BY t.timestamp DESC"""
    )
    fun unsortedFlow(): Flow<List<TransactionRow>>

    @Query("SELECT COUNT(*) FROM transactions WHERE reviewed = 0")
    fun unsortedCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transactions WHERE reviewed = 0")
    suspend fun unsortedCount(): Int

    @Query("SELECT * FROM transactions WHERE upiRef = :ref LIMIT 1")
    suspend fun byUpiRef(ref: String): TransactionEntity?

    /** Same amount, close in time: possibly one payment reported twice. */
    @Query(
        """SELECT * FROM transactions
           WHERE amountPaise = :amountPaise AND timestamp BETWEEN :from AND :to
           ORDER BY ABS(timestamp - :around) ASC"""
    )
    suspend fun sameAmountNear(amountPaise: Long, from: Long, to: Long, around: Long): List<TransactionEntity>

    /** What the category ranker learns from: only payments a person has sorted. */
    @Query(
        """SELECT * FROM transactions
           WHERE reviewed = 1 AND amountPaise > 0 AND timestamp >= :since AND id != :excludeId
           ORDER BY timestamp DESC LIMIT :limit"""
    )
    suspend fun sortedHistory(since: Long, excludeId: Long, limit: Int): List<TransactionEntity>
}
