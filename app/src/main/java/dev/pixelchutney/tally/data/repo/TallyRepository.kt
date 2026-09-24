package dev.pixelchutney.tally.data.repo

import dev.pixelchutney.tally.capture.PaymentContext
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.BudgetDao
import dev.pixelchutney.tally.data.db.BudgetEntity
import dev.pixelchutney.tally.data.db.CategoryDao
import dev.pixelchutney.tally.data.db.CategoryEntity
import dev.pixelchutney.tally.data.db.MerchantDao
import dev.pixelchutney.tally.data.db.MerchantEntity
import dev.pixelchutney.tally.data.db.Seed
import dev.pixelchutney.tally.data.db.SessionDao
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.db.TransactionEntity
import dev.pixelchutney.tally.data.db.WatchedAppDao
import dev.pixelchutney.tally.data.model.EntryMethod
import dev.pixelchutney.tally.data.model.Necessity
import dev.pixelchutney.tally.data.model.SessionOutcome
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** What the capture sheet hands back when Save is tapped. */
data class TransactionDraft(
    val amountPaise: Long,
    val categoryId: Long,
    val merchantName: String?,
    val note: String?,
    val timestamp: Long,
    val necessity: Necessity,
    val sourceApp: String? = null,
    val entryMethod: EntryMethod = EntryMethod.FULLY_MANUAL,
    val excluded: Boolean = false,
    val sessionId: Long? = null,
    /** Where and when this happened, sampled at the moment of payment. */
    val context: PaymentContext = PaymentContext(),
    /** False for a payment read from a notification: the category is a guess. */
    val reviewed: Boolean = true,
    val payee: String? = null,
    val upiRef: String? = null,
    val detectedFrom: String? = null,
)

/** A merchant seen before, with everything Tally already learned about it. */
data class MerchantMemory(
    val merchant: MerchantEntity?,
    val suggestedCategoryId: Long?,
    val suggestedNecessity: Necessity?,
)

@Singleton
class TallyRepository @Inject constructor(
    private val txns: TransactionDao,
    private val categories: CategoryDao,
    private val merchants: MerchantDao,
    private val sessions: SessionDao,
    private val budgets: BudgetDao,
    private val watchedApps: WatchedAppDao,
) {
    val activeCategories: Flow<List<CategoryEntity>> = categories.activeFlow()

    fun categoriesByUsage(since: Long = Time.now() - 90L * 86_400_000L) =
        categories.byUsageFlow(since)

    suspend fun ensureSeeded() {
        if (categories.count() == 0) categories.insertAll(Seed.categories)
        // Every launch, not only the first: an app added to the seed list later
        // reaches existing installs too. IGNORE keeps any row already there, so
        // an app switched off in Settings stays off.
        watchedApps.insertAll(Seed.watchedApps)
    }

    // ── Saving ────────────────────────────────────────────────────────────────

    /**
     * Writes the transaction and folds it into what Tally knows about the merchant,
     * so the next payment to the same place needs no category tap at all.
     */
    suspend fun save(draft: TransactionDraft): Long {
        val merchantName = draft.merchantName?.trim()?.takeIf { it.isNotEmpty() }
        // An unsorted payment's category is a guess. Teaching it to the merchant
        // would turn the guess into the default for every later visit; that waits
        // until someone sorts it.
        val merchantId = merchantName?.takeIf { draft.reviewed }?.let { name ->
            val existing = merchants.byName(name)
            val id = if (existing != null) {
                existing.id
            } else {
                merchants.upsert(
                    MerchantEntity(
                        canonicalName = name,
                        defaultCategoryId = draft.categoryId,
                        defaultNecessity = draft.necessity.takeIf { it != Necessity.UNSORTED },
                        lastSeenAt = draft.timestamp,
                        userConfirmed = true,
                    )
                )
            }
            merchants.recordVisit(id, draft.amountPaise, draft.timestamp)
            // A hand-picked category always wins over whatever was learned before.
            existing?.let {
                merchants.update(
                    it.copy(
                        defaultCategoryId = draft.categoryId,
                        defaultNecessity = draft.necessity.takeIf { n -> n != Necessity.UNSORTED }
                            ?: it.defaultNecessity,
                        userConfirmed = true,
                    )
                )
            }
            id
        }

        val id = txns.insert(
            TransactionEntity(
                amountPaise = draft.amountPaise,
                timestamp = draft.timestamp,
                loggedAt = Time.now(),
                categoryId = draft.categoryId,
                merchantId = merchantId,
                merchantName = merchantName,
                note = draft.note?.trim()?.takeIf { it.isNotEmpty() },
                sourceApp = draft.sourceApp,
                entryMethod = draft.entryMethod,
                necessity = draft.necessity,
                excluded = draft.excluded,
                sessionId = draft.sessionId,
                latitude = draft.context.latitude,
                longitude = draft.context.longitude,
                locationAccuracyM = draft.context.locationAccuracyM,
                locationAt = draft.context.locationAt,
                placeName = draft.context.placeName,
                placeAddress = draft.context.placeAddress,
                networkName = draft.context.networkName,
                locationSource = draft.context.locationSource,
                networkOperator = draft.context.networkOperator,
                roaming = draft.context.roaming,
                precedingApp = draft.context.precedingApp,
                calendarEvent = draft.context.calendarEvent,
                reviewed = draft.reviewed,
                payee = draft.payee,
                upiRef = draft.upiRef,
                detectedFrom = draft.detectedFrom,
            )
        )

        draft.sessionId?.let { sessionId ->
            sessions.byId(sessionId)?.let {
                sessions.update(
                    it.copy(outcome = SessionOutcome.LOGGED, linkedTransactionId = id)
                )
            }
        }
        return id
    }

    /**
     * Files a payment that was waiting in "To sort", and teaches the merchant what
     * it was — the same learning a hand-entered payment gets at save.
     */
    suspend fun sort(
        id: Long,
        categoryId: Long,
        note: String?,
        merchantName: String? = null,
        necessity: Necessity? = null,
    ): TransactionEntity? {
        val txn = txns.byId(id) ?: return null
        val category = categories.byId(categoryId)
        val name = merchantName?.trim()?.takeIf { it.isNotEmpty() } ?: txn.merchantName
        val chosenNecessity = necessity?.takeIf { it != Necessity.UNSORTED }
            ?: category?.defaultNecessity
            ?: txn.necessity

        var merchantId = txn.merchantId
        if (name != null) {
            learnMerchant(
                canonicalName = name,
                alias = txn.payee,
                categoryId = categoryId,
                necessity = chosenNecessity.takeIf { it != Necessity.UNSORTED },
                fromAi = false,
            )
            merchants.byName(name)?.let { merchant ->
                if (txn.merchantId != merchant.id) {
                    merchants.recordVisit(merchant.id, txn.amountPaise, txn.timestamp)
                }
                merchantId = merchant.id
            }
        }

        val sorted = txn.copy(
            categoryId = categoryId,
            note = note?.trim()?.takeIf { it.isNotEmpty() } ?: txn.note,
            merchantName = name,
            merchantId = merchantId,
            necessity = chosenNecessity,
            reviewed = true,
        )
        txns.update(sorted)
        return sorted
    }

    suspend fun update(txn: TransactionEntity) = txns.update(txn)
    suspend fun delete(txn: TransactionEntity) = txns.delete(txn)
    suspend fun deleteByIds(ids: List<Long>) = txns.deleteByIds(ids)
    suspend fun recategorise(ids: List<Long>, categoryId: Long) = txns.recategorise(ids, categoryId)
    suspend fun byId(id: Long) = txns.byId(id)

    /** True when an identical amount to the same place landed in the last few minutes. */
    suspend fun looksLikeDuplicate(amountPaise: Long, merchantName: String?, at: Long): Boolean =
        txns.duplicateCandidates(
            amountPaise = amountPaise,
            merchantName = merchantName,
            from = at - DUPLICATE_WINDOW_MS,
            to = at + DUPLICATE_WINDOW_MS,
        ) > 0

    // ── Merchant memory ───────────────────────────────────────────────────────

    suspend fun recall(rawMerchant: String?): MerchantMemory {
        val name = rawMerchant?.trim()?.takeIf { it.isNotEmpty() }
            ?: return MerchantMemory(null, null, null)
        val merchant = merchants.byName(name) ?: merchants.byAlias(name)
        return MerchantMemory(
            merchant = merchant,
            suggestedCategoryId = merchant?.defaultCategoryId,
            suggestedNecessity = merchant?.defaultNecessity,
        )
    }

    suspend fun suggestMerchants(prefix: String, limit: Int = 6): List<MerchantEntity> =
        if (prefix.isBlank()) emptyList() else merchants.suggest(prefix.trim(), limit)

    suspend fun learnMerchant(
        canonicalName: String,
        alias: String?,
        categoryId: Long?,
        necessity: Necessity?,
        fromAi: Boolean,
    ) {
        val existing = merchants.byName(canonicalName)
        if (existing != null) {
            // Never let a model overwrite a choice a human already made.
            if (fromAi && existing.userConfirmed) return
            val aliases = (existing.aliases.split('\n') + listOfNotNull(alias))
                .filter { it.isNotBlank() }.distinct().joinToString("\n")
            merchants.update(
                existing.copy(
                    aliases = aliases,
                    defaultCategoryId = categoryId ?: existing.defaultCategoryId,
                    defaultNecessity = necessity ?: existing.defaultNecessity,
                    userConfirmed = existing.userConfirmed || !fromAi,
                )
            )
        } else {
            merchants.upsert(
                MerchantEntity(
                    canonicalName = canonicalName,
                    aliases = alias.orEmpty(),
                    defaultCategoryId = categoryId,
                    defaultNecessity = necessity,
                    userConfirmed = !fromAi,
                )
            )
        }
    }

    suspend fun uncategorisedMerchants(limit: Int = 20) = merchants.uncategorised(limit)

    // ── Categories & budgets ──────────────────────────────────────────────────

    suspend fun allCategories() = categories.all()
    suspend fun categoryById(id: Long) = categories.byId(id)
    suspend fun upsertCategory(category: CategoryEntity) = categories.upsert(category)

    suspend fun archiveCategory(category: CategoryEntity) {
        txns.reassignCategory(category.id, Seed.FALLBACK_CATEGORY_ID)
        budgets.deleteForCategory(category.id)
        categories.update(category.copy(archived = true))
    }

    fun budgetsFlow(): Flow<List<BudgetEntity>> = budgets.activeFlow()
    fun overallBudgetFlow(): Flow<BudgetEntity?> = budgets.overallFlow()
    suspend fun overallBudget() = budgets.overall()
    suspend fun setBudget(categoryId: Long?, monthlyLimitPaise: Long) {
        if (monthlyLimitPaise <= 0) {
            if (categoryId != null) budgets.deleteForCategory(categoryId)
            else budgets.overall()?.let { budgets.delete(it) }
            return
        }
        val existing = budgets.active().firstOrNull { it.categoryId == categoryId }
        budgets.upsert(
            existing?.copy(monthlyLimitPaise = monthlyLimitPaise)
                ?: BudgetEntity(categoryId = categoryId, monthlyLimitPaise = monthlyLimitPaise)
        )
    }

    private companion object {
        const val DUPLICATE_WINDOW_MS = 3 * 60 * 1000L
    }
}
