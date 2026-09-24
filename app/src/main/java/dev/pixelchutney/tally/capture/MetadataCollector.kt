package dev.pixelchutney.tally.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.CalendarContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.SessionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Everything Tally can learn about a payment without asking.
 *
 * Stored now, used later — the point is to have a history worth reasoning over by
 * the time the category-suggestion work happens, rather than starting to collect
 * on the day it is built.
 *
 * Not stored here because it is already derivable from the transaction: time of
 * day, day of week, the payment app, and the gap since the previous payment.
 * Duplicating those would just be a second copy that can disagree with the first.
 */
@Serializable
data class PaymentContext(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyM: Float? = null,
    /** When the fix was taken, which is not always when the payment was saved. */
    val locationAt: Long? = null,
    val placeName: String? = null,
    val placeAddress: String? = null,
    /**
     * How the fix was obtained. Without this a two-hour-old cached position is
     * indistinguishable from a live one, and analysis would silently trust both
     * equally. See [LocationSource].
     */
    val locationSource: String? = null,
    /** Wi-Fi network name, or "Mobile data" / "Offline". A strong home-vs-out signal. */
    val networkName: String? = null,
    /** Carrier name. Works with location switched off, and changes when travelling. */
    val networkOperator: String? = null,
    val roaming: Boolean? = null,
    /** The app in front before the payment app — Zomato before Navi says a lot. */
    val precedingApp: String? = null,
    /** Title of a calendar event covering the payment, if the permission is granted. */
    val calendarEvent: String? = null,
) {
    val hasAnything: Boolean
        get() = latitude != null || networkName != null || calendarEvent != null ||
            precedingApp != null || networkOperator != null
}

/** Where a stored position came from, and therefore how much to trust it. */
object LocationSource {
    /** A fix taken at the moment of payment. */
    const val LIVE = "live"
    /** The system's last known position — recent, but not taken just now. */
    const val LAST_KNOWN = "last_known"
    /** Tally's own remembered fix, used when location is switched off entirely. */
    const val CACHED = "cached"
    /** Location services are off and nothing was remembered. */
    const val UNAVAILABLE = "unavailable"
    /** The permission was never granted. */
    const val DENIED = "denied"
}

@Singleton
class MetadataCollector @Inject constructor(
    private val context: Context,
    private val sessions: SessionDao,
    private val scope: CoroutineScope,
) {
    /**
     * Samples taken when a visit ended, waiting for the payment to be saved.
     *
     * The fix has to be taken at the moment of payment — by the time anything is
     * typed the phone may have moved, and a payment can sit in "To sort" until the
     * evening. Held as a `Deferred` so a payment notification landing while the
     * fix is still being taken waits for it rather than sampling a second time.
     * Also written to the session row, which outlives this process.
     */
    private val pending = mutableMapOf<Long, Deferred<PaymentContext>>()

    fun hasLocationPermission(): Boolean = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts sampling for [sessionId] now and returns at once. The result is held
     * in memory and on the session row until the payment is saved.
     */
    fun startCapture(sessionId: Long, precedingApp: String? = null) {
        val job = scope.async {
            val sampled = runCatching { sample(precedingApp) }
                .getOrDefault(PaymentContext(locationSource = LocationSource.UNAVAILABLE, precedingApp = precedingApp))
            runCatching {
                sessions.setContext(sessionId, json.encodeToString(PaymentContext.serializer(), sampled))
            }
            sampled
        }
        synchronized(pending) { pending[sessionId] = job }
    }

    /**
     * The sample taken when this visit ended, or a fresh one.
     *
     * Memory first, then the session row. A miss on both means the process died
     * before the sample was written. Sampling again is only honest while the
     * payment is recent: an hour later the phone is somewhere else, and a live
     * fix from there would be stored as if it were the till. Past that, location
     * is recorded as unavailable rather than wrong.
     */
    suspend fun consume(sessionId: Long?, precedingApp: String? = null): PaymentContext {
        if (sessionId != null) {
            val held = synchronized(pending) { pending.remove(sessionId) }
            if (held != null) return runCatching { held.await() }.getOrNull() ?: sample(precedingApp)

            val session = sessions.byId(sessionId)
            session?.contextJson?.let { stored ->
                runCatching { json.decodeFromString(PaymentContext.serializer(), stored) }
                    .getOrNull()?.let { return it }
            }
            val endedAt = session?.endedAt ?: session?.startedAt
            if (endedAt != null && Time.now() - endedAt > RESAMPLE_LIMIT_MS) {
                return PaymentContext(locationSource = LocationSource.UNAVAILABLE, precedingApp = precedingApp)
            }
        }
        return sample(precedingApp)
    }

    suspend fun sample(precedingApp: String? = null): PaymentContext = withContext(Dispatchers.IO) {
        val located = if (hasLocationPermission()) currentLocation() else Located(null, LocationSource.DENIED)
        val fix = located.location
        if (fix != null && located.source == LocationSource.LIVE) remember(fix)
        val place = fix?.let { reverseGeocode(it) }

        PaymentContext(
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            locationAccuracyM = fix?.accuracy,
            // The time of the fix, not of the sample. A cached position is honest
            // about being old rather than pretending to be current.
            locationAt = fix?.let { if (it.time > 0) it.time else Time.now() },
            placeName = place?.first,
            placeAddress = place?.second,
            locationSource = located.source,
            networkName = networkName(),
            networkOperator = networkOperator(),
            roaming = roaming(),
            precedingApp = precedingApp,
            calendarEvent = if (hasCalendarPermission()) currentCalendarEvent() else null,
        )
    }

    private data class Located(val location: Location?, val source: String)

    /**
     * A fresh fix, falling back to the last known one.
     *
     * `getCurrentLocation` is the right call here: it returns quickly when a
     * recent fix exists and only powers up the radio when it has to. Ten seconds
     * is the ceiling — past that the person has walked away from wherever they
     * paid, and a stale last-known fix is the more honest answer.
     */
    private suspend fun currentLocation(): Located {
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return Located(null, LocationSource.UNAVAILABLE)

        // Location switched off system-wide. Nothing live is possible, so fall
        // straight through to whatever was last seen.
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return fallback(manager)
        }

        val fresh = runCatching {
            withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { runCatching { signal.cancel() } }
                    manager.getCurrentLocation(
                        provider,
                        signal,
                        LOCATION_EXECUTOR,
                    ) { location -> if (continuation.isActive) continuation.resume(location) }
                }
            }
        }.getOrNull()

        return if (fresh != null) Located(fresh, LocationSource.LIVE) else fallback(manager)
    }

    /**
     * What to record when a live fix is impossible — location switched off, indoors
     * with no signal, or the radio taking longer than anyone will wait.
     *
     * The system's own last known position first, then Tally's remembered one.
     * Both are stamped with the age of the fix rather than the time of the payment,
     * so a stale position can never be mistaken for a current one. Beyond a day old
     * it is dropped: a position from yesterday says nothing about today's lunch.
     */
    private fun fallback(manager: LocationManager): Located {
        val lastKnown = runCatching {
            manager.getProviders(false)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull()
        if (lastKnown != null && isRecent(lastKnown.time)) {
            return Located(lastKnown, LocationSource.LAST_KNOWN)
        }

        val remembered = recall()
        if (remembered != null) return Located(remembered, LocationSource.CACHED)

        return Located(null, LocationSource.UNAVAILABLE)
    }

    private fun isRecent(at: Long) = at > 0 && Time.now() - at < MAX_FIX_AGE_MS

    /**
     * Remembers the last live fix, so a payment made with location off still lands
     * somewhere. Stored as raw bits — a `Float` latitude would quietly round the
     * position, and coordinates are the one thing here worth keeping exact.
     */
    private fun remember(location: Location) {
        runCatching {
            store.edit()
                .putLong(KEY_LAT, location.latitude.toRawBits())
                .putLong(KEY_LON, location.longitude.toRawBits())
                .putFloat(KEY_ACCURACY, location.accuracy)
                .putLong(KEY_AT, if (location.time > 0) location.time else Time.now())
                .apply()
        }
    }

    private fun recall(): Location? = runCatching {
        val at = store.getLong(KEY_AT, 0L)
        if (!isRecent(at)) return null
        Location("tally-cache").apply {
            latitude = Double.fromBits(store.getLong(KEY_LAT, 0L))
            longitude = Double.fromBits(store.getLong(KEY_LON, 0L))
            accuracy = store.getFloat(KEY_ACCURACY, 0f)
            time = at
        }
    }.getOrNull()

    private val store by lazy {
        context.getSharedPreferences("tally_location_cache", Context.MODE_PRIVATE)
    }

    /** Returns name and full address. Offline on most devices, so it can miss. */
    private fun reverseGeocode(location: Location): Pair<String?, String?>? = runCatching {
        if (!Geocoder.isPresent()) return null
        @Suppress("DEPRECATION")
        val results = Geocoder(context, Locale.getDefault())
            .getFromLocation(location.latitude, location.longitude, 1)
        val address = results?.firstOrNull() ?: return null
        val name = address.featureName
            ?: address.subLocality
            ?: address.locality
        name to address.getAddressLine(0)
    }.getOrNull()

    private fun networkName(): String? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            ?: return "Offline"

        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifiName() ?: "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            else -> null
        }
    }.getOrNull()

    /**
     * The SSID is only readable with location permission, and comes back quoted
     * or as the "unknown ssid" placeholder when it is not.
     */
    private fun wifiName(): String? = runCatching {
        if (!hasLocationPermission()) return null
        @Suppress("DEPRECATION")
        val info = (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
        info?.ssid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID }
    }.getOrNull()

    /** The title of an event covering right now — "lunch" is often already written down. */
    private fun currentCalendarEvent(): String? = runCatching {
        val now = Time.now()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.TITLE),
            "${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DTEND} >= ? " +
                "AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(now.toString(), now.toString()),
            "${CalendarContract.Events.DTSTART} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    /**
     * The carrier, and whether the phone is roaming. Both survive location being
     * switched off, and a change in either is a reliable sign of being somewhere
     * unusual — which is exactly when spending stops looking like the baseline.
     */
    private fun networkOperator(): String? = runCatching {
        context.getSystemService(TelephonyManager::class.java)
            ?.networkOperatorName
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun roaming(): Boolean? = runCatching {
        context.getSystemService(TelephonyManager::class.java)?.isNetworkRoaming
    }.getOrNull()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private companion object {
        const val LOCATION_TIMEOUT_MS = 10_000L
        /** Past this, sampling again would record where you are now, not where you paid. */
        const val RESAMPLE_LIMIT_MS = 30 * 60 * 1000L
        /** Past this, a remembered position says nothing useful about today. */
        const val MAX_FIX_AGE_MS = 24 * 60 * 60 * 1000L
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_AT = "at"
        val LOCATION_EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
