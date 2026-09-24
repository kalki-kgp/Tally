package dev.pixelchutney.tally.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.pixelchutney.tally.BuildConfig
import dev.pixelchutney.tally.core.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** What `tally-android-update.json` on the latest GitHub release says. */
data class UpdateManifest(
    val versionName: String,
    val versionCode: Long,
    val url: String,
    val sha256: String,
    val releaseNotes: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest) : UpdateState
    /** Handed to Android's installer, which asks before replacing anything. */
    data class Installing(val manifest: UpdateManifest) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Updates Tally from its own GitHub releases — the same scheme as Linkit.
 *
 * Every release carries the APK and a small manifest naming its version and
 * SHA-256. The check is one tiny request; the APK is only downloaded when asked,
 * and it is refused unless its checksum matches before Android ever sees it.
 *
 * Android installs an update only when it is signed by the same key as the app
 * on the phone. Releases are built on the owner's Mac with its debug key for
 * exactly that reason — see `scripts/release.sh`.
 */
@Singleton
class AppUpdater @Inject constructor(
    private val context: Context,
    private val http: OkHttpClient,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastCheckedAt = 0L

    val currentVersionLabel: String
        get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** On opening the app: at most every few hours, and silent unless there is news. */
    suspend fun checkIfDue() {
        if (Time.now() - lastCheckedAt < AUTO_CHECK_INTERVAL_MS) return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        check(quietFailure = true)
    }

    suspend fun check(quietFailure: Boolean = false) {
        _state.value = UpdateState.Checking
        lastCheckedAt = Time.now()
        _state.value = runCatching { fetchManifest() }.fold(
            onSuccess = { manifest ->
                if (manifest.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(manifest)
                else UpdateState.UpToDate
            },
            // An offline phone opening the app is not an event worth a red banner.
            onFailure = { if (quietFailure) UpdateState.Idle else UpdateState.Failed(it.message ?: "Update check failed") },
        )
    }

    suspend fun downloadAndInstall() {
        val manifest = when (val current = _state.value) {
            is UpdateState.Available -> current.manifest
            is UpdateState.Installing -> current.manifest
            else -> return
        }

        // Without this Android refuses the installer outright. The setting is
        // per app and survives; asking once is enough.
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            _state.value = UpdateState.Failed("Allow installs from Tally, then tap Install again.")
            return
        }

        _state.value = UpdateState.Downloading(manifest)
        val apk = runCatching { download(manifest) }.getOrElse {
            _state.value = UpdateState.Failed(it.message ?: "Download failed")
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        _state.value = UpdateState.Installing(manifest)
    }

    private suspend fun fetchManifest(): UpdateManifest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(BuildConfig.UPDATE_MANIFEST_URL).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update check failed (HTTP ${response.code}).")
            parse(response.body?.string() ?: throw IOException("The update manifest was empty."))
        }
    }

    private suspend fun download(manifest: UpdateManifest): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Older downloads are dead weight once a newer one exists.
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "tally-${manifest.versionCode}.apk")

        val request = Request.Builder().url(manifest.url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code}).")
            val body = response.body ?: throw IOException("The download was empty.")
            apk.outputStream().use { out -> body.byteStream().copyTo(out) }
        }

        val actual = sha256(apk)
        if (actual != manifest.sha256) {
            apk.delete()
            throw IOException("The download did not match its checksum, so it was thrown away.")
        }
        apk
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /** Rejects anything that is not an HTTPS Android APK with a real checksum. */
        fun parse(body: String): UpdateManifest {
            val json = JSONObject(body)
            val platform = json.optString("platform", "android")
            require(platform.equals("android", ignoreCase = true)) { "That update is for $platform." }
            val url = json.getString("url")
            require(url.startsWith("https://")) { "The update link is not HTTPS." }
            val sha = json.getString("sha256").trim().lowercase()
            require(Regex("^[0-9a-f]{64}$").matches(sha)) { "The update's checksum is malformed." }
            return UpdateManifest(
                versionName = json.getString("versionName"),
                versionCode = json.getLong("versionCode"),
                url = url,
                sha256 = sha,
                releaseNotes = json.optString("releaseNotes").takeIf { it.isNotBlank() },
            )
        }
    }
}
