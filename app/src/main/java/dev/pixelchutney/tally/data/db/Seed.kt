package dev.pixelchutney.tally.data.db

import dev.pixelchutney.tally.data.model.Necessity

/**
 * Starting categories and the UPI apps worth watching. Both lists are editable in
 * Settings — they are a starting point, not a schema.
 */
object Seed {

    val categories = listOf(
        CategoryEntity(id = 1, name = "Food & Dining", emoji = "🍜", colorIndex = 0, defaultNecessity = Necessity.WANT, sortOrder = 0),
        CategoryEntity(id = 2, name = "Groceries", emoji = "🧺", colorIndex = 1, defaultNecessity = Necessity.NEED, sortOrder = 1),
        CategoryEntity(id = 3, name = "Transport", emoji = "🛺", colorIndex = 2, defaultNecessity = Necessity.NEED, sortOrder = 2),
        CategoryEntity(id = 4, name = "Shopping", emoji = "🛍", colorIndex = 3, defaultNecessity = Necessity.WANT, sortOrder = 3),
        CategoryEntity(id = 5, name = "Bills & Utilities", emoji = "💡", colorIndex = 4, defaultNecessity = Necessity.NEED, sortOrder = 4),
        CategoryEntity(id = 6, name = "Entertainment", emoji = "🎬", colorIndex = 5, defaultNecessity = Necessity.WANT, sortOrder = 5),
        CategoryEntity(id = 7, name = "Health", emoji = "💊", colorIndex = 6, defaultNecessity = Necessity.NEED, sortOrder = 6),
        CategoryEntity(id = 8, name = "Subscriptions", emoji = "🔁", colorIndex = 7, defaultNecessity = Necessity.WANT, sortOrder = 7),
        CategoryEntity(id = 9, name = "Transfers", emoji = "🤝", colorIndex = 8, defaultNecessity = Necessity.UNSORTED, sortOrder = 8),
        CategoryEntity(id = 10, name = "Travel", emoji = "✈️", colorIndex = 9, defaultNecessity = Necessity.WANT, sortOrder = 9),
        CategoryEntity(id = 11, name = "Other", emoji = "📦", colorIndex = 10, defaultNecessity = Necessity.UNSORTED, sortOrder = 10),
    )

    const val FALLBACK_CATEGORY_ID = 11L

    /**
     * Package names verified against a real device before shipping — Settings
     * lists every installed app so a missing one takes seconds to add.
     */
    val watchedApps = listOf(
        WatchedAppEntity(packageName = "com.google.android.apps.nbu.paisa.user", label = "Google Pay"),
        WatchedAppEntity(packageName = "com.phonepe.app", label = "PhonePe"),
        WatchedAppEntity(packageName = "net.one97.paytm", label = "Paytm"),
        WatchedAppEntity(packageName = "com.dreamplug.androidapp", label = "CRED"),
        WatchedAppEntity(packageName = "in.org.npci.upiapp", label = "BHIM"),
        // Verified on the owner's phone (2026-09-25). The earlier guess,
        // com.navi.android, matched nothing, so Navi visits were never seen.
        WatchedAppEntity(packageName = "com.naviapp", label = "Navi"),
        WatchedAppEntity(packageName = "money.super.payments", label = "super.money"),
        WatchedAppEntity(packageName = "com.hdfcbank.payzapp", label = "PayZapp"),
        WatchedAppEntity(packageName = "in.amazon.mShop.android.shopping", label = "Amazon Pay"),
        WatchedAppEntity(packageName = "com.mobikwik_new", label = "MobiKwik"),
        WatchedAppEntity(packageName = "com.freecharge.android", label = "Freecharge"),
        WatchedAppEntity(packageName = "com.whatsapp", label = "WhatsApp Pay", enabled = false),
    )
}
