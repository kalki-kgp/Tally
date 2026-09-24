package dev.pixelchutney.tally.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pixelchutney.tally.data.db.AiDao
import dev.pixelchutney.tally.data.db.BudgetDao
import dev.pixelchutney.tally.data.db.CategoryDao
import dev.pixelchutney.tally.data.db.ChatDao
import dev.pixelchutney.tally.data.db.MerchantDao
import dev.pixelchutney.tally.data.db.RecurringDao
import dev.pixelchutney.tally.data.db.SessionDao
import dev.pixelchutney.tally.data.db.TallyDatabase
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.db.WatchedAppDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun context(@ApplicationContext context: Context): Context = context

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): TallyDatabase =
        Room.databaseBuilder(context, TallyDatabase::class.java, TallyDatabase.NAME)
            .addMigrations(
                TallyDatabase.MIGRATION_1_2,
                TallyDatabase.MIGRATION_2_3,
                TallyDatabase.MIGRATION_3_4,
                TallyDatabase.MIGRATION_4_5,
            )
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Seeding happens in SeedInitialiser once Hilt graph is up;
                    // this callback only guarantees a non-empty categories table
                    // for the FK default on `transactions.categoryId`.
                    dev.pixelchutney.tally.data.db.Seed.categories.forEach { category ->
                        db.execSQL(
                            "INSERT OR IGNORE INTO categories " +
                                "(id, name, emoji, colorIndex, defaultNecessity, sortOrder, archived) " +
                                "VALUES (?, ?, ?, ?, ?, ?, 0)",
                            arrayOf(
                                category.id, category.name, category.emoji,
                                category.colorIndex, category.defaultNecessity.name,
                                category.sortOrder,
                            ),
                        )
                    }
                }
            })
            .build()

    @Provides fun transactionDao(db: TallyDatabase): TransactionDao = db.transactions()
    @Provides fun categoryDao(db: TallyDatabase): CategoryDao = db.categories()
    @Provides fun merchantDao(db: TallyDatabase): MerchantDao = db.merchants()
    @Provides fun sessionDao(db: TallyDatabase): SessionDao = db.sessions()
    @Provides fun budgetDao(db: TallyDatabase): BudgetDao = db.budgets()
    @Provides fun recurringDao(db: TallyDatabase): RecurringDao = db.recurring()
    @Provides fun watchedAppDao(db: TallyDatabase): WatchedAppDao = db.watchedApps()
    @Provides fun aiDao(db: TallyDatabase): AiDao = db.ai()
    @Provides fun chatDao(db: TallyDatabase): ChatDao = db.chats()

    @Provides @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
}
