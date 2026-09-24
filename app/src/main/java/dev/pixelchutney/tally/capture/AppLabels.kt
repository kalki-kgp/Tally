package dev.pixelchutney.tally.capture

import android.content.Context
import android.content.pm.PackageManager
import javax.inject.Inject
import javax.inject.Singleton

/** "com.application.zomato" → "Zomato", remembered, because lists ask repeatedly. */
@Singleton
class AppLabels @Inject constructor(
    private val context: Context,
) {
    private val cache = mutableMapOf<String, String>()

    fun of(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        synchronized(cache) { cache[packageName]?.let { return it } }
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA))
                .toString()
        }.getOrNull() ?: packageName.substringAfterLast('.')
        synchronized(cache) { cache[packageName] = label }
        return label
    }
}
