package dev.pixelchutney.tally.data.backup

import android.content.Context
import android.net.Uri
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.CategoryDao
import dev.pixelchutney.tally.data.db.TransactionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CSV that opens cleanly in Sheets and Excel, with one row per transaction and
 * nothing summarised away — this is the copy of the data that outlives the app.
 */
@Singleton
class Exporter @Inject constructor(
    private val context: Context,
    private val txns: TransactionDao,
    private val categories: CategoryDao,
) {
    suspend fun buildCsv(): String = withContext(Dispatchers.Default) {
        val categoryNames = categories.all().associate { it.id to it.name }
        buildString {
            appendLine(HEADER)
            txns.allForExport().forEach { txn ->
                val dt = Time.toLocalDateTime(txn.timestamp)
                appendLine(
                    listOf(
                        dt.toLocalDate().toString(),
                        dt.toLocalTime().withNano(0).toString(),
                        Money.toRupees(txn.amountPaise).toString(),
                        categoryNames[txn.categoryId] ?: "Unknown",
                        txn.merchantName.orEmpty(),
                        txn.necessity.name,
                        txn.note.orEmpty(),
                        txn.sourceApp.orEmpty(),
                        txn.entryMethod.name,
                        if (txn.excluded) "yes" else "no",
                    ).joinToString(",") { escape(it) }
                )
            }
        }
    }

    /** Writes to cache and returns a shareable file — the share sheet does the rest. */
    suspend fun writeToCache(): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "tally-${Time.today()}.csv")
        file.writeText(buildCsv())
        file
    }

    suspend fun writeTo(uri: Uri) = withContext(Dispatchers.IO) {
        val csv = buildCsv()
        context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
    }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private companion object {
        const val HEADER =
            "date,time,amount_inr,category,merchant,necessity,note,source_app,entry_method,excluded"
    }
}
