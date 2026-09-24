package dev.pixelchutney.tally.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE archived = 0 ORDER BY sortOrder ASC, name ASC")
    fun activeFlow(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name ASC")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun byId(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): CategoryEntity?

    /**
     * Chips are ordered by how often I actually use a category, so the right one
     * is usually first. Ties fall back to the manual sort order.
     */
    @Query(
        """SELECT c.* FROM categories c
           LEFT JOIN transactions t ON t.categoryId = c.id AND t.timestamp >= :since
           WHERE c.archived = 0
           GROUP BY c.id
           ORDER BY COUNT(t.id) DESC, c.sortOrder ASC"""
    )
    fun byUsageFlow(since: Long): Flow<List<CategoryEntity>>

    @Query(
        """SELECT c.* FROM categories c
           LEFT JOIN transactions t ON t.categoryId = c.id AND t.timestamp >= :since
           WHERE c.archived = 0
           GROUP BY c.id
           ORDER BY COUNT(t.id) DESC, c.sortOrder ASC"""
    )
    suspend fun byUsage(since: Long): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>): List<Long>

    @Upsert suspend fun upsert(category: CategoryEntity): Long
    @Update suspend fun update(category: CategoryEntity)
    @Delete suspend fun delete(category: CategoryEntity)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface MerchantDao {
    @Query("SELECT * FROM merchants WHERE canonicalName = :name LIMIT 1")
    suspend fun byName(name: String): MerchantEntity?

    @Query("SELECT * FROM merchants WHERE id = :id")
    suspend fun byId(id: Long): MerchantEntity?

    /** Alias match: the raw notification string is stored newline-delimited. */
    @Query("SELECT * FROM merchants WHERE aliases LIKE '%' || :alias || '%' LIMIT 1")
    suspend fun byAlias(alias: String): MerchantEntity?

    @Query(
        """SELECT * FROM merchants WHERE canonicalName LIKE :prefix || '%'
           ORDER BY visitCount DESC LIMIT :limit"""
    )
    suspend fun suggest(prefix: String, limit: Int): List<MerchantEntity>

    @Query("SELECT * FROM merchants ORDER BY visitCount DESC LIMIT :limit")
    fun frequentFlow(limit: Int): Flow<List<MerchantEntity>>

    @Query("SELECT * FROM merchants WHERE defaultCategoryId IS NULL LIMIT :limit")
    suspend fun uncategorised(limit: Int): List<MerchantEntity>

    @Upsert suspend fun upsert(merchant: MerchantEntity): Long
    @Update suspend fun update(merchant: MerchantEntity)

    @Query(
        """UPDATE merchants SET visitCount = visitCount + 1,
                                totalPaise = totalPaise + :amountPaise,
                                lastSeenAt = :at
           WHERE id = :id"""
    )
    suspend fun recordVisit(id: Long, amountPaise: Long, at: Long)
}

@Dao
interface SessionDao {
    @Insert suspend fun insert(session: PaymentSessionEntity): Long
    @Update suspend fun update(session: PaymentSessionEntity)

    @Query("SELECT * FROM payment_sessions WHERE id = :id")
    suspend fun byId(id: Long): PaymentSessionEntity?

    @Query("SELECT * FROM payment_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<PaymentSessionEntity>>

    @Query(
        """SELECT * FROM payment_sessions
           WHERE packageName = :pkg AND startedAt >= :since
           ORDER BY startedAt DESC LIMIT 1"""
    )
    suspend fun latestFor(pkg: String, since: Long): PaymentSessionEntity?

    @Query("SELECT * FROM payment_sessions WHERE outcome = 'OPEN' ORDER BY startedAt DESC LIMIT 1")
    suspend fun openSession(): PaymentSessionEntity?

    /** Capture rate: how many detected payment moments actually became entries. */
    @Query(
        """SELECT outcome AS outcome, COUNT(*) AS sessionCount FROM payment_sessions
           WHERE startedAt BETWEEN :from AND :to AND outcome != 'TOO_SHORT'
           GROUP BY outcome"""
    )
    suspend fun outcomeCounts(from: Long, to: Long): List<SessionOutcomeCount>

    @Query(
        """SELECT COUNT(*) FROM payment_sessions
           WHERE packageName = :pkg AND outcome = 'NO_PAYMENT' AND startedAt >= :since"""
    )
    suspend fun recentNoPaymentCount(pkg: String, since: Long): Int

    /**
     * Single-column writes. A payment can be saved against a visit at the same
     * moment the visit is being closed; rewriting the whole row from a copy read
     * a moment earlier would put back the outcome the save just replaced.
     */
    @Query("UPDATE payment_sessions SET endedAt = :at WHERE id = :id")
    suspend fun markEnded(id: Long, at: Long)

    @Query("UPDATE payment_sessions SET contextJson = :json WHERE id = :id")
    suspend fun setContext(id: Long, json: String)

    /** Visits that ended without an amount, waiting in "To sort". */
    @Query("SELECT * FROM payment_sessions WHERE outcome = 'TO_SORT' ORDER BY startedAt DESC")
    fun toSortFlow(): Flow<List<PaymentSessionEntity>>

    /**
     * Finished visits a payment notification could still belong to: nothing
     * saved against them, and nobody said "no payment".
     */
    @Query(
        """SELECT * FROM payment_sessions
           WHERE endedAt IS NOT NULL AND endedAt >= :since
             AND linkedTransactionId IS NULL
             AND outcome IN ('OPEN', 'DISMISSED', 'IGNORED', 'TO_SORT')
           ORDER BY endedAt DESC"""
    )
    suspend fun claimable(since: Long): List<PaymentSessionEntity>

    @Query("UPDATE payment_sessions SET outcome = 'IGNORED' WHERE outcome = 'OPEN' AND startedAt < :before")
    suspend fun expireStale(before: Long)

    @Query("DELETE FROM payment_sessions WHERE startedAt < :before")
    suspend fun pruneBefore(before: Long)
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE active = 1")
    fun activeFlow(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE active = 1")
    suspend fun active(): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE categoryId IS NULL AND active = 1 LIMIT 1")
    fun overallFlow(): Flow<BudgetEntity?>

    @Query("SELECT * FROM budgets WHERE categoryId IS NULL AND active = 1 LIMIT 1")
    suspend fun overall(): BudgetEntity?

    @Upsert suspend fun upsert(budget: BudgetEntity): Long
    @Delete suspend fun delete(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE categoryId = :categoryId")
    suspend fun deleteForCategory(categoryId: Long)
}

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring_rules WHERE dismissed = 0 ORDER BY confirmed DESC, nextExpectedAt ASC")
    fun allFlow(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules WHERE confirmed = 1 AND dismissed = 0")
    suspend fun confirmed(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE merchantName = :merchantName LIMIT 1")
    suspend fun byMerchantName(merchantName: String): RecurringRuleEntity?

    @Upsert suspend fun upsert(rule: RecurringRuleEntity): Long
    @Update suspend fun update(rule: RecurringRuleEntity)
    @Delete suspend fun delete(rule: RecurringRuleEntity)
}

@Dao
interface WatchedAppDao {
    @Query("SELECT * FROM watched_apps ORDER BY label ASC")
    fun allFlow(): Flow<List<WatchedAppEntity>>

    @Query("SELECT * FROM watched_apps WHERE enabled = 1")
    suspend fun enabled(): List<WatchedAppEntity>

    @Query("SELECT * FROM watched_apps WHERE packageName = :pkg LIMIT 1")
    suspend fun byPackage(pkg: String): WatchedAppEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(apps: List<WatchedAppEntity>)

    @Upsert suspend fun upsert(app: WatchedAppEntity): Long
    @Update suspend fun update(app: WatchedAppEntity)

    @Query("SELECT COUNT(*) FROM watched_apps")
    suspend fun count(): Int
}

@Dao
interface AiDao {
    @Query("SELECT * FROM ai_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun cached(key: String): AiCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cache(entry: AiCacheEntity)

    @Query("DELETE FROM ai_cache WHERE createdAt < :before")
    suspend fun pruneCache(before: Long)

    @Query("SELECT * FROM insights WHERE dismissed = 0 ORDER BY createdAt DESC LIMIT :limit")
    fun insightsFlow(limit: Int): Flow<List<InsightEntity>>

    @Query("SELECT * FROM insights WHERE kind = :kind ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestOfKind(kind: String): InsightEntity?

    @Insert suspend fun insertInsight(insight: InsightEntity): Long

    @Query("UPDATE insights SET dismissed = 1 WHERE id = :id")
    suspend fun dismissInsight(id: Long)

    @Query("DELETE FROM insights WHERE createdAt < :before")
    suspend fun pruneInsights(before: Long)
}
