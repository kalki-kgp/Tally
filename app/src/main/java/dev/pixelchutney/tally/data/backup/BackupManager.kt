package dev.pixelchutney.tally.data.backup

import android.content.Context
import android.net.Uri
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.BudgetDao
import dev.pixelchutney.tally.data.db.BudgetEntity
import dev.pixelchutney.tally.data.db.CategoryDao
import dev.pixelchutney.tally.data.db.CategoryEntity
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.db.TransactionEntity
import dev.pixelchutney.tally.data.model.EntryMethod
import dev.pixelchutney.tally.data.model.Necessity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupCategory(
    val id: Long, val name: String, val emoji: String, val colorIndex: Int,
    val defaultNecessity: String, val sortOrder: Int, val archived: Boolean,
)

@Serializable
data class BackupTransaction(
    val id: Long, val amountPaise: Long, val timestamp: Long, val loggedAt: Long,
    val categoryId: Long, val merchantName: String?, val note: String?,
    val sourceApp: String?, val entryMethod: String, val necessity: String,
    val excluded: Boolean,
    /** Absent in backups from before v5, all of which were sorted by hand. */
    val reviewed: Boolean = true,
)

@Serializable
data class BackupBudget(val categoryId: Long?, val monthlyLimitPaise: Long)

@Serializable
data class TallyBackup(
    val version: Int = 1,
    val createdAt: Long,
    val categories: List<BackupCategory>,
    val transactions: List<BackupTransaction>,
    val budgets: List<BackupBudget>,
)

/**
 * A nightly JSON snapshot in app storage, fourteen deep.
 *
 * The database is the only copy of this data — there is no server behind it — so
 * the app that writes it is also responsible for making it recoverable.
 */
@Singleton
class BackupManager @Inject constructor(
    private val context: Context,
    private val txns: TransactionDao,
    private val categories: CategoryDao,
    private val budgets: BudgetDao,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val backupDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")
            .apply { mkdirs() }

    suspend fun snapshot(): TallyBackup = withContext(Dispatchers.Default) {
        TallyBackup(
            createdAt = Time.now(),
            categories = categories.all().map {
                BackupCategory(
                    it.id, it.name, it.emoji, it.colorIndex,
                    it.defaultNecessity.name, it.sortOrder, it.archived,
                )
            },
            transactions = txns.allForExport().map {
                BackupTransaction(
                    it.id, it.amountPaise, it.timestamp, it.loggedAt, it.categoryId,
                    it.merchantName, it.note, it.sourceApp, it.entryMethod.name,
                    it.necessity.name, it.excluded, it.reviewed,
                )
            },
            budgets = budgets.active().map { BackupBudget(it.categoryId, it.monthlyLimitPaise) },
        )
    }

    suspend fun writeAutoBackup(): File = withContext(Dispatchers.IO) {
        val file = File(backupDir, "tally-backup-${Time.today()}.json")
        file.writeText(json.encodeToString(TallyBackup.serializer(), snapshot()))
        prune()
        file
    }

    suspend fun writeTo(uri: Uri) = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(TallyBackup.serializer(), snapshot())
        context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
    }

    fun listBackups(): List<File> =
        backupDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun prune() {
        listBackups().drop(KEEP).forEach { it.delete() }
    }

    /**
     * Restores by merging, never by wiping: transactions already present (same
     * timestamp and amount) are skipped, so restoring twice is harmless.
     */
    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            ?: return@withContext 0
        val backup = json.decodeFromString(TallyBackup.serializer(), text)

        categories.insertAll(
            backup.categories.map {
                CategoryEntity(
                    id = it.id, name = it.name, emoji = it.emoji, colorIndex = it.colorIndex,
                    defaultNecessity = runCatching { Necessity.valueOf(it.defaultNecessity) }
                        .getOrDefault(Necessity.UNSORTED),
                    sortOrder = it.sortOrder, archived = it.archived,
                )
            }
        )

        val existing = txns.allForExport()
            .map { it.timestamp to it.amountPaise }
            .toHashSet()

        val incoming = backup.transactions
            .filterNot { (it.timestamp to it.amountPaise) in existing }
            .map {
                TransactionEntity(
                    amountPaise = it.amountPaise,
                    timestamp = it.timestamp,
                    loggedAt = it.loggedAt,
                    categoryId = it.categoryId,
                    merchantName = it.merchantName,
                    note = it.note,
                    sourceApp = it.sourceApp,
                    entryMethod = runCatching { EntryMethod.valueOf(it.entryMethod) }
                        .getOrDefault(EntryMethod.IMPORTED),
                    necessity = runCatching { Necessity.valueOf(it.necessity) }
                        .getOrDefault(Necessity.UNSORTED),
                    excluded = it.excluded,
                    reviewed = it.reviewed,
                )
            }

        txns.insertAll(incoming)
        backup.budgets.forEach { budgets.upsert(BudgetEntity(categoryId = it.categoryId, monthlyLimitPaise = it.monthlyLimitPaise)) }
        incoming.size
    }

    private companion object { const val KEEP = 14 }
}
