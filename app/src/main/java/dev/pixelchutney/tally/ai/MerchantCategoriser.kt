package dev.pixelchutney.tally.ai

import dev.pixelchutney.tally.core.Time
import dev.pixelchutney.tally.data.db.AiCacheEntity
import dev.pixelchutney.tally.data.db.AiDao
import dev.pixelchutney.tally.data.model.Necessity
import dev.pixelchutney.tally.data.repo.TallyRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

data class Categorisation(
    val canonicalName: String,
    val categoryId: Long,
    val necessity: Necessity,
)

/**
 * Names a merchant and files it, once. Every answer is written back to the
 * merchants table, so a place you have paid before never costs another API call —
 * and a category you picked yourself is never overwritten by a model.
 */
@Singleton
class MerchantCategoriser @Inject constructor(
    private val claude: ClaudeClient,
    private val repository: TallyRepository,
    private val aiDao: AiDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun categorise(
        rawMerchant: String,
        amountPaise: Long,
        timestamp: Long,
        sourceApp: String?,
    ): Categorisation? {
        val known = repository.recall(rawMerchant)
        if (known.merchant != null && known.suggestedCategoryId != null) {
            return Categorisation(
                canonicalName = known.merchant.canonicalName,
                categoryId = known.suggestedCategoryId,
                necessity = known.suggestedNecessity ?: Necessity.UNSORTED,
            )
        }

        val categories = repository.allCategories().filter { !it.archived }
        val cacheKey = "cat:${rawMerchant.lowercase().trim()}"
        aiDao.cached(cacheKey)?.let { cached ->
            parse(cached.response, categories.associate { it.name to it.id })?.let { return it }
        }

        val catalogue = categories.joinToString("\n") { "- ${it.name}" }
        val hour = Time.toLocalDateTime(timestamp).hour
        val prompt = """
            Merchant string from a payment notification: "$rawMerchant"
            Amount: ₹${amountPaise / 100}
            Time of day: ${hour}:00
            Paid via: ${sourceApp ?: "unknown"}

            Categories available:
            $catalogue

            Reply with JSON only, no prose:
            {"name": "<human-readable merchant name>", "category": "<exact category from the list>", "necessity": "NEED" or "WANT"}

            Use NEED for groceries, transport to work, bills, medicine, rent.
            Use WANT for eating out, delivery, entertainment, clothes, gadgets.
        """.trimIndent()

        val result = claude.send(
            model = Models.FAST,
            system = "You classify Indian UPI merchant strings. Answer with a single JSON object and nothing else.",
            messages = listOf(userMessage(prompt)),
            maxTokens = 200,
        )

        val text = (result as? ClaudeResult.Ok)?.response?.text ?: return null
        aiDao.cache(
            AiCacheEntity(
                cacheKey = cacheKey,
                response = text,
                model = Models.FAST,
                createdAt = Time.now(),
            )
        )

        val parsed = parse(text, categories.associate { it.name to it.id }) ?: return null
        repository.learnMerchant(
            canonicalName = parsed.canonicalName,
            alias = rawMerchant,
            categoryId = parsed.categoryId,
            necessity = parsed.necessity,
            fromAi = true,
        )
        return parsed
    }

    private fun parse(raw: String, categoriesByName: Map<String, Long>): Categorisation? {
        val body = raw.substringAfter('{', "").let { if (it.isEmpty()) return null else "{$it" }
            .substringBeforeLast('}') + "}"
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null

        val name = obj["name"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val categoryName = obj["category"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val necessityRaw = obj["necessity"]?.jsonPrimitive?.contentOrNull()?.trim()?.uppercase()

        if (name.isEmpty()) return null
        // Only accept a category that actually exists — a hallucinated name would
        // otherwise silently become "Other" and hide the mistake.
        val categoryId = categoriesByName.entries
            .firstOrNull { it.key.equals(categoryName, ignoreCase = true) }?.value
            ?: return null

        return Categorisation(
            canonicalName = name,
            categoryId = categoryId,
            necessity = runCatching { Necessity.valueOf(necessityRaw ?: "") }
                .getOrDefault(Necessity.UNSORTED),
        )
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()
}
