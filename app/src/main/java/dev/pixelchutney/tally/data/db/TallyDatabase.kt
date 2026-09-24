package dev.pixelchutney.tally.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        CategoryEntity::class,
        MerchantEntity::class,
        TransactionEntity::class,
        PaymentSessionEntity::class,
        BudgetEntity::class,
        RecurringRuleEntity::class,
        WatchedAppEntity::class,
        AiCacheEntity::class,
        InsightEntity::class,
        ChatEntity::class,
        ChatMessageEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TallyDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun categories(): CategoryDao
    abstract fun merchants(): MerchantDao
    abstract fun sessions(): SessionDao
    abstract fun budgets(): BudgetDao
    abstract fun recurring(): RecurringDao
    abstract fun watchedApps(): WatchedAppDao
    abstract fun ai(): AiDao
    abstract fun chats(): ChatDao

    companion object {
        const val NAME = "tally.db"

        /** v2 removed notification parsing, and with it the raw-notification log. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS notification_log")
            }
        }

        /** v3 records where and when a payment happened, for later analysis. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "latitude REAL",
                    "longitude REAL",
                    "locationAccuracyM REAL",
                    "locationAt INTEGER",
                    "placeName TEXT",
                    "placeAddress TEXT",
                    "networkName TEXT",
                    "calendarEvent TEXT",
                ).forEach { column ->
                    db.execSQL("ALTER TABLE transactions ADD COLUMN $column")
                }
            }
        }

        /**
         * v4 adds saved chats, and the payment-context fields that only became
         * necessary once location turned out to be unavailable sometimes.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "locationSource TEXT",
                    "networkOperator TEXT",
                    "roaming INTEGER",
                    "precedingApp TEXT",
                ).forEach { column ->
                    db.execSQL("ALTER TABLE transactions ADD COLUMN $column")
                }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chats (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_updatedAt ON chats (updatedAt)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        chatId INTEGER NOT NULL,
                        fromUser INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        toolsUsed TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(chatId) REFERENCES chats(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_messages_chatId ON chat_messages (chatId)"
                )
            }
        }

        /**
         * v5 reads amounts from payment notifications and lets them wait to be
         * sorted. Every existing row was entered by hand, so it is already sorted.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN reviewed INTEGER NOT NULL DEFAULT 1")
                listOf("payee TEXT", "upiRef TEXT", "detectedFrom TEXT").forEach { column ->
                    db.execSQL("ALTER TABLE transactions ADD COLUMN $column")
                }
                db.execSQL("ALTER TABLE payment_sessions ADD COLUMN contextJson TEXT")
            }
        }
    }
}
