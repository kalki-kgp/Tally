package dev.pixelchutney.tally.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "tally_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * What happens after a payment.
 *
 * Asking at the till was the original design, and it is what made Tally tiring
 * enough to stop using after a few days. With amounts read from notifications
 * the question at the till is no longer "how much", only "what for" — and that
 * can wait until there is time for it.
 */
enum class CaptureMode {
    /** Payments are logged quietly and wait in "To sort". Nothing interrupts. */
    SORT_LATER,
    /** A heads-up notification after every payment, as before. */
    ASK_NOW,
}

data class TallySettings(
    val promptsEnabled: Boolean = true,
    val debounceSeconds: Int = 2,
    val promptExpiryMinutes: Int = 30,
    val smallLeakThresholdPaise: Long = 20_000L,   // ₹200
    val aiEnabled: Boolean = false,
    val aiMonthlyCapPaise: Long = 10_000L,         // ₹100 of API spend
    val aiSpentThisMonthPaise: Long = 0L,
    val aiSpendMonthKey: String = "",
    val autoCategorise: Boolean = true,
    val weeklyDigestEnabled: Boolean = true,
    val autoBackupEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val lastDigestAt: Long = 0L,
    val lastBackupAt: Long = 0L,
    val onboardingDone: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.SORT_LATER,
    /** Read amounts from UPI app notifications and bank texts. */
    val readPaymentNotifications: Boolean = true,
)

@Singleton
class SettingsStore @Inject constructor(
    private val context: Context,
) {
    private object Keys {
        val PROMPTS = booleanPreferencesKey("prompts_enabled")
        val DEBOUNCE = intPreferencesKey("debounce_seconds")
        val EXPIRY = intPreferencesKey("prompt_expiry_minutes")
        val SMALL_LEAK = longPreferencesKey("small_leak_threshold")
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val AI_CAP = longPreferencesKey("ai_monthly_cap")
        val AI_SPENT = longPreferencesKey("ai_spent_this_month")
        val AI_MONTH = stringPreferencesKey("ai_spend_month")
        val AUTO_CATEGORISE = booleanPreferencesKey("auto_categorise")
        val DIGEST = booleanPreferencesKey("weekly_digest")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val THEME = stringPreferencesKey("theme_mode")
        val LAST_DIGEST = longPreferencesKey("last_digest_at")
        val LAST_BACKUP = longPreferencesKey("last_backup_at")
        val ONBOARDED = booleanPreferencesKey("onboarding_done")
        val CAPTURE_MODE = stringPreferencesKey("capture_mode")
        val READ_NOTIFICATIONS = booleanPreferencesKey("read_payment_notifications")
    }

    val settings: Flow<TallySettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = TallySettings(
        promptsEnabled = this[Keys.PROMPTS] ?: true,
        debounceSeconds = this[Keys.DEBOUNCE] ?: 2,
        promptExpiryMinutes = this[Keys.EXPIRY] ?: 30,
        smallLeakThresholdPaise = this[Keys.SMALL_LEAK] ?: 20_000L,
        aiEnabled = this[Keys.AI_ENABLED] ?: false,
        aiMonthlyCapPaise = this[Keys.AI_CAP] ?: 10_000L,
        aiSpentThisMonthPaise = this[Keys.AI_SPENT] ?: 0L,
        aiSpendMonthKey = this[Keys.AI_MONTH] ?: "",
        autoCategorise = this[Keys.AUTO_CATEGORISE] ?: true,
        weeklyDigestEnabled = this[Keys.DIGEST] ?: true,
        autoBackupEnabled = this[Keys.AUTO_BACKUP] ?: true,
        themeMode = runCatching { ThemeMode.valueOf(this[Keys.THEME] ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM),
        lastDigestAt = this[Keys.LAST_DIGEST] ?: 0L,
        lastBackupAt = this[Keys.LAST_BACKUP] ?: 0L,
        onboardingDone = this[Keys.ONBOARDED] ?: false,
        captureMode = runCatching { CaptureMode.valueOf(this[Keys.CAPTURE_MODE] ?: "SORT_LATER") }
            .getOrDefault(CaptureMode.SORT_LATER),
        readPaymentNotifications = this[Keys.READ_NOTIFICATIONS] ?: true,
    )

    suspend fun current(): TallySettings = settings.first()

    suspend fun setPromptsEnabled(value: Boolean) = edit { it[Keys.PROMPTS] = value }
    suspend fun setSmallLeakThreshold(paise: Long) = edit { it[Keys.SMALL_LEAK] = paise }
    suspend fun setAiEnabled(value: Boolean) = edit { it[Keys.AI_ENABLED] = value }
    suspend fun setAiMonthlyCap(paise: Long) = edit { it[Keys.AI_CAP] = paise }
    suspend fun setAutoCategorise(value: Boolean) = edit { it[Keys.AUTO_CATEGORISE] = value }
    suspend fun setWeeklyDigest(value: Boolean) = edit { it[Keys.DIGEST] = value }
    suspend fun setAutoBackup(value: Boolean) = edit { it[Keys.AUTO_BACKUP] = value }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setLastDigestAt(at: Long) = edit { it[Keys.LAST_DIGEST] = at }
    suspend fun setLastBackupAt(at: Long) = edit { it[Keys.LAST_BACKUP] = at }
    suspend fun setOnboardingDone(value: Boolean) = edit { it[Keys.ONBOARDED] = value }
    suspend fun setCaptureMode(mode: CaptureMode) = edit { it[Keys.CAPTURE_MODE] = mode.name }
    suspend fun setReadPaymentNotifications(value: Boolean) = edit { it[Keys.READ_NOTIFICATIONS] = value }

    /** Adds to this month's API spend, resetting when the month rolls over. */
    suspend fun addAiSpend(paise: Long, monthKey: String) = edit { prefs ->
        val sameMonth = prefs[Keys.AI_MONTH] == monthKey
        prefs[Keys.AI_MONTH] = monthKey
        prefs[Keys.AI_SPENT] = (if (sameMonth) prefs[Keys.AI_SPENT] ?: 0L else 0L) + paise
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
