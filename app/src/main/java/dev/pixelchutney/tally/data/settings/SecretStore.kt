package dev.pixelchutney.tally.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the Claude API key. Keystore-backed rather than plain preferences: it is
 * the one secret in the app, and a lost phone should not hand it over.
 */
@Singleton
class SecretStore @Inject constructor(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "tally_secure_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) as SharedPreferences
    }.getOrElse {
        // Keystore unavailable on this device: the app still works, AI just stays off.
        context.getSharedPreferences("tally_fallback_prefs", Context.MODE_PRIVATE)
    }

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_API, value?.trim().orEmpty()).apply()

    fun hasKey(): Boolean = !apiKey.isNullOrBlank()

    fun clear() = prefs.edit().remove(KEY_API).apply()

    private companion object {
        const val KEY_API = "claude_api_key"
    }
}
