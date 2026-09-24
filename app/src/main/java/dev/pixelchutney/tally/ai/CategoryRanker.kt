package dev.pixelchutney.tally.ai

import dev.pixelchutney.tally.capture.AppLabels
import dev.pixelchutney.tally.capture.LocationSource
import dev.pixelchutney.tally.capture.PaymentContext
import dev.pixelchutney.tally.core.Money
import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.AiCacheEntity
import dev.pixelchutney.tally.data.db.AiDao
import dev.pixelchutney.tally.data.db.CategoryEntity
import dev.pixelchutney.tally.data.db.Seed
import dev.pixelchutney.tally.data.db.TransactionDao
import dev.pixelchutney.tally.data.db.TransactionEntity
import dev.pixelchutney.tally.data.model.Necessity
import dev.pixelchutney.tally.data.repo.TallyRepository
import dev.pixelchutney.tally.data.settings.SettingsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One payment, as much as is known about it when a category is wanted. */
data class RankInput(
    val amountPaise: Long,
    val timestamp: Long,
    val sourceApp: String?,
    val payee: String?,
    val merchantName: String?,
    /** The owner's own words. Always sent when present — see CLAUDE.md. */
    val note: String?,
    val context: PaymentContext,
    /** The payment being ranked, kept out of its own evidence. */
    val excludeTxnId: Long = -1,
)

data class RankedCategory(val category: CategoryEntity, val confidence: Float)

data class Ranking(
    /** Every active category, most plausible first. Never empty. */
    val ranked: List<RankedCategory>,
    val merchant: String?,
    val necessity: Necessity?,
    /** True when Haiku ordered this; false when it is the on-device ordering. */
    val fromModel: Boolean,
) {
    val top: CategoryEntity get() = ranked.first().category
    val categories: List<CategoryEntity> get() = ranked.map { it.category }
}

/**
 * Chooses which categories are plausible for *this* payment.
 *
 * The notification has room for two category buttons. Chosen by overall
 * frequency they are usually wrong for the payment in hand; this picks them per
 * payment instead, from what the phone knew at the time and from how similar
 * payments were filed before.
 *
 * Two layers. The on-device ordering is instant, free and always available: it
 * scores every category by the past payments that look like this one. Haiku then
 * gets the same evidence plus the note — which carries more signal than every
 * automatic field together — and reorders it. When AI is off, over budget or
 * offline, the on-device ordering is the answer and nothing looks different.
 *
 * Coordinates never leave the phone. The model is told the place name and how
 * far each similar payment was from this one, which is all it needs.
 */
@Singleton
class CategoryRanker @Inject constructor(
    private val claude: ClaudeClient,
    private val repository: TallyRepository,
    private val txns: TransactionDao,
    private val aiDao: AiDao,
    private val settings: SettingsStore,
    private val labels: AppLabels,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun rank(input: RankInput, allowModel: Boolean = true): Ranking {
        val categories = repository.allCategories().filter { !it.archived }.ifEmpty { Seed.categories }
        val history = txns.sortedHistory(
            since = Time.now() - HISTORY_DAYS * DAY_MS,
            excludeId = input.excludeTxnId,
            limit = HISTORY_LIMIT,
        )
        val memory = repository.recall(input.payee ?: input.merchantName)
        val local = LocalRanking.score(
            input = input,
            categories = categories,
            history = history,
            rememberedCategoryId = memory.suggestedCategoryId,
            rememberedByPerson = memory.merchant?.userConfirmed == true,
        )
        val localRanking = Ranking(
            ranked = local,
            merchant = memory.merchant?.canonicalName,
            necessity = memory.suggestedNecessity,
            fromModel = false,
        )

        if (!allowModel) return localRanking
        val config = settings.current()
        if (!config.aiEnabled || !config.autoCategorise || !claude.keyPresent()) return localRanking

        val prompt = buildPrompt(input, categories, history, local, memory.merchant?.canonicalName)
        val cacheKey = "rank:" + sha1(prompt)
        val cached = aiDao.cached(cacheKey)?.response
        val reply = cached ?: run {
            val result = claude.send(
                model = Models.FAST,
                system = SYSTEM,
                messages = listOf(userMessage(prompt)),
                maxTokens = 600,
                jsonSchema = schemaFor(categories),
            )
            val ok = result as? ClaudeResult.Ok ?: return localRanking
            if (ok.response.stop_reason != "end_turn") return localRanking
            ok.response.text.also { text ->
                aiDao.cache(
                    AiCacheEntity(
                        cacheKey = cacheKey,
                        response = text,
                        model = Models.FAST,
                        createdAt = Time.now(),
                    )
                )
            }
        }
        return merge(reply, categories, local) ?: localRanking
    }

    /**
     * The model's order first, then whatever it left out in the on-device order,
     * so the "Other…" list stays complete and still useful past the top few.
     */
    private fun merge(
        reply: String,
        categories: List<CategoryEntity>,
        local: List<RankedCategory>,
    ): Ranking? {
        val obj = runCatching { json.parseToJsonElement(reply).jsonObject }.getOrNull() ?: return null
        val byName = categories.associateBy { it.name.lowercase() }

        val fromModel = (obj["ranking"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val name = item["category"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            val category = name?.let(byName::get) ?: return@mapNotNull null
            val confidence = item["confidence"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f
            RankedCategory(category, confidence.coerceIn(0f, 1f))
        }.distinctBy { it.category.id }
        if (fromModel.isEmpty()) return null

        val seen = fromModel.map { it.category.id }.toSet()
        val rest = local.filter { it.category.id !in seen }.map { it.copy(confidence = 0f) }

        val merchant = obj["merchant"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) && !it.equals("unknown", ignoreCase = true) }
        val necessity = obj["necessity"]?.jsonPrimitive?.contentOrNull?.uppercase()
            ?.let { runCatching { Necessity.valueOf(it) }.getOrNull() }

        return Ranking(
            ranked = fromModel + rest,
            merchant = merchant,
            necessity = necessity?.takeIf { it != Necessity.UNSORTED },
            fromModel = true,
        )
    }

    private fun buildPrompt(
        input: RankInput,
        categories: List<CategoryEntity>,
        history: List<TransactionEntity>,
        local: List<RankedCategory>,
        knownMerchant: String?,
    ): String {
        val names = categories.associate { it.id to it.name }
        val when_ = Time.toLocalDateTime(input.timestamp)
        val ctx = input.context

        val similar = history
            .map { it to LocalRanking.similarity(input, it) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(SIMILAR_SHOWN)

        return buildString {
            appendLine("The payment to sort:")
            appendLine("- Amount: ${Money.format(input.amountPaise)}")
            appendLine("- When: ${when_.format(WHEN)}")
            labels.of(input.sourceApp)?.let { appendLine("- Paid with: $it") }
            input.payee?.let { appendLine("- Payee, as the payment notification wrote it: $it") }
            (knownMerchant ?: input.merchantName)?.let { appendLine("- Merchant: $it") }
            val note = input.note?.trim().orEmpty()
            appendLine(
                if (note.isNotEmpty()) "- Owner's note, in their own words: \"$note\""
                else "- Owner's note: none yet"
            )
            labels.of(ctx.precedingApp)?.let { appendLine("- App they were using just before paying: $it") }
            appendLine("- Place: ${describePlace(ctx)}")
            ctx.networkName?.let { appendLine("- Network: $it") }
            if (ctx.roaming == true) appendLine("- Roaming: yes (likely travelling)")
            ctx.calendarEvent?.let { appendLine("- Calendar event at the time: $it") }
            appendLine()

            appendLine("Their categories:")
            categories.forEach { c ->
                val lean = when (c.defaultNecessity) {
                    Necessity.NEED -> " (usually a need)"
                    Necessity.WANT -> " (usually a want)"
                    Necessity.UNSORTED -> ""
                }
                appendLine("- ${c.name}$lean")
            }
            appendLine()

            if (similar.isNotEmpty()) {
                appendLine("Their most similar past payments, most similar first:")
                similar.forEach { (t, _) ->
                    appendLine("- " + describePast(t, names[t.categoryId] ?: "Other", input))
                }
                appendLine()
            }

            val counts = history.groupingBy { it.categoryId }.eachCount()
            if (counts.isNotEmpty()) {
                appendLine("How often they used each category in the last year:")
                appendLine(
                    counts.entries.sortedByDescending { it.value }
                        .joinToString(", ") { "${names[it.key] ?: "Other"} ${it.value}" }
                )
                appendLine()
            }

            appendLine("On-device guess from history alone: " + local.take(3).joinToString(", ") { it.category.name })
            appendLine()
            appendLine(
                "Rank the categories that plausibly fit this payment, most likely first, " +
                    "with confidences that sum to about 1. Leave out ones that clearly do not fit. " +
                    "Give a clean merchant name if the payee or note makes it clear, otherwise an " +
                    "empty string. Say NEED or WANT if you can tell, otherwise UNKNOWN."
            )
        }
    }

    private fun describePlace(ctx: PaymentContext): String {
        val source = ctx.locationSource
        if (source == null || source == LocationSource.DENIED || source == LocationSource.UNAVAILABLE ||
            ctx.latitude == null
        ) return "unknown"
        val where = ctx.placeName ?: ctx.placeAddress ?: "a known spot"
        val trust = when (source) {
            LocationSource.LIVE -> "fix taken at the time"
            LocationSource.LAST_KNOWN -> "last known position, may be a little old"
            else -> "remembered position, may be hours old"
        }
        return "$where ($trust)"
    }

    private fun describePast(t: TransactionEntity, category: String, now: RankInput): String {
        val at = Time.toLocalDateTime(t.timestamp)
        return buildString {
            append(Money.format(t.amountPaise))
            append(" · ").append(category)
            (t.merchantName ?: t.payee)?.let { append(" · ").append(it) }
            t.note?.takeIf { it.isNotBlank() }?.let { append(" · \"").append(it).append('"') }
            append(" · ").append(at.format(PAST_WHEN))
            labels.of(t.precedingApp)?.let { append(" · came from ").append(it) }
            LocalRanking.distanceMetres(now.context, t)?.let { d ->
                append(" · ").append(if (d < 1000) "${d.toInt()} m away" else "${"%.1f".format(d / 1000)} km away")
            }
        }
    }

    /** Category names as an enum, so a reply can only ever name one that exists. */
    private fun schemaFor(categories: List<CategoryEntity>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("ranking") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("category") {
                            put("type", "string")
                            putJsonArray("enum") { categories.forEach { add(JsonPrimitive(it.name)) } }
                        }
                        putJsonObject("confidence") { put("type", "number") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("category")); add(JsonPrimitive("confidence")) }
                    put("additionalProperties", false)
                }
            }
            putJsonObject("merchant") { put("type", "string") }
            putJsonObject("necessity") {
                put("type", "string")
                putJsonArray("enum") { listOf("NEED", "WANT", "UNKNOWN").forEach { add(JsonPrimitive(it)) } }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("ranking")); add(JsonPrimitive("merchant")); add(JsonPrimitive("necessity"))
        }
        put("additionalProperties", false)
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SYSTEM =
            "You sort one person's payments into their own spending categories. You are given " +
                "the payment, what their phone knew at the time, and how they filed similar " +
                "payments before. When they have written a note, it is the strongest evidence " +
                "there is — it says, in their words, what the money was for. Their past habits " +
                "outrank general assumptions about merchants. Answer only with the JSON asked for."
        const val HISTORY_DAYS = 365L
        const val DAY_MS = 86_400_000L
        const val HISTORY_LIMIT = 600
        const val SIMILAR_SHOWN = 12
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM, h:mm a", Locale.ENGLISH)
        val PAST_WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH)
    }
}

/**
 * The on-device half: scores categories by how much past payments filed under
 * them resemble this one. Pure, so it can be tested without a phone.
 */
object LocalRanking {

    fun score(
        input: RankInput,
        categories: List<CategoryEntity>,
        history: List<TransactionEntity>,
        rememberedCategoryId: Long?,
        rememberedByPerson: Boolean,
    ): List<RankedCategory> {
        val scores = categories.associate { it.id to 0.0 }.toMutableMap()

        // A little weight for plain frequency, so an empty signal still orders
        // categories by how often they are actually used.
        history.forEach { t -> scores.computeIfPresent(t.categoryId) { _, v -> v + 0.05 } }
        // Per category, the closest match counts in full and each further one for
        // half the last. Otherwise a category used often at lunchtime would outvote
        // the one payment to this exact payee just by being common.
        history.map { it.categoryId to similarity(input, it) }
            .filter { it.second > 0 }
            .groupBy({ it.first }, { it.second })
            .forEach { (categoryId, matches) ->
                val evidence = matches.sortedDescending()
                    .foldIndexed(0.0) { i, acc, s -> acc + s * 0.5.pow(i) }
                scores.computeIfPresent(categoryId) { _, v -> v + evidence }
            }

        rememberedCategoryId?.let { id ->
            scores.computeIfPresent(id) { _, v -> v + if (rememberedByPerson) 8.0 else 3.0 }
        }

        // "groceries for the week" names its own category.
        val noteWords = words(input.note)
        if (noteWords.isNotEmpty()) {
            categories.forEach { c ->
                if (words(c.name).any { it in noteWords || noteWords.any { w -> w.startsWith(it) } }) {
                    scores.computeIfPresent(c.id) { _, v -> v + 4.0 }
                }
            }
        }

        val total = scores.values.sum()
        return categories
            .sortedWith(compareByDescending<CategoryEntity> { scores[it.id] ?: 0.0 }.thenBy { it.sortOrder })
            .map { c ->
                val s = scores[c.id] ?: 0.0
                RankedCategory(c, if (total > 0) (s / total).toFloat() else 0f)
            }
    }

    /** How much a past payment looks like this one. Zero means not at all. */
    fun similarity(input: RankInput, past: TransactionEntity): Double {
        var s = 0.0

        val payee = input.payee?.lowercase()?.trim()
        val merchant = input.merchantName?.lowercase()?.trim()
        val pastPayee = past.payee?.lowercase()?.trim()
        val pastMerchant = past.merchantName?.lowercase()?.trim()
        if ((payee != null && (payee == pastPayee || payee == pastMerchant)) ||
            (merchant != null && (merchant == pastMerchant || merchant == pastPayee))
        ) s += 4.0

        val noteWords = words(input.note)
        if (noteWords.isNotEmpty()) {
            val pastWords = words(past.note) + words(past.merchantName)
            val shared = noteWords.count { it in pastWords }
            if (shared > 0) s += 2.5 * shared / noteWords.size
        }

        distanceMetres(input.context, past)?.let { d ->
            s += when {
                d < 150 -> 2.5
                d < 500 -> 1.2
                d < 1500 -> 0.3
                else -> 0.0
            }
        }

        val before = input.context.precedingApp
        if (before != null && before == past.precedingApp) s += 1.5

        val wifi = input.context.networkName
        if (wifi != null && wifi !in GENERIC_NETWORKS && wifi == past.networkName) s += 1.2

        if (input.sourceApp != null && input.sourceApp == past.sourceApp) s += 0.3

        val hour = Time.toLocalDateTime(input.timestamp).let { it.hour + it.minute / 60.0 }
        val pastHour = Time.toLocalDateTime(past.timestamp).let { it.hour + it.minute / 60.0 }
        val gap = abs(hour - pastHour).let { min(it, 24 - it) }
        s += when {
            gap <= 1.0 -> 0.6
            gap <= 3.0 -> 0.3
            else -> 0.0
        }

        if (input.amountPaise > 0 && past.amountPaise > 0) {
            val ratio = input.amountPaise.toDouble() / past.amountPaise
            if (input.amountPaise == past.amountPaise) s += 1.2
            else if (ratio in 0.75..1.33) s += 0.6
        }
        return s
    }

    /** Only between two positions worth trusting; a day-old cache proves nothing. */
    fun distanceMetres(now: PaymentContext, past: TransactionEntity): Double? {
        if (now.locationSource !in TRUSTED || past.locationSource !in TRUSTED) return null
        val lat1 = now.latitude ?: return null
        val lon1 = now.longitude ?: return null
        val lat2 = past.latitude ?: return null
        val lon2 = past.longitude ?: return null
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * 6_371_000.0 * asin(sqrt(a))
    }

    private fun words(text: String?): Set<String> =
        text.orEmpty().lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOPWORDS }
            .toSet()

    private val TRUSTED = setOf(LocationSource.LIVE, LocationSource.LAST_KNOWN)
    private val GENERIC_NETWORKS = setOf("Mobile data", "Offline", "Wi-Fi")
    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "from", "was", "this", "that", "some", "got", "had",
        "bought", "paid", "pay", "payment", "money",
    )
}
