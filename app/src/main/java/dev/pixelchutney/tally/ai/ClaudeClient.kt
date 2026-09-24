package dev.pixelchutney.tally.ai

import dev.pixelchutney.tally.data.settings.SecretStore
import dev.pixelchutney.tally.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

object Models {
    /** Cheap and frequent: one call per never-seen-before merchant. */
    const val FAST = "claude-haiku-4-5-20251001"
    /**
     * Weekly digests, monthly reviews, and Ask. Haiku by choice: the questions are
     * narrow, the tools do the arithmetic, and the cost difference matters when the
     * whole thing runs against a monthly rupee cap.
     */
    const val SMART = "claude-haiku-4-5-20251001"
}

@Serializable
data class Usage(val input_tokens: Int = 0, val output_tokens: Int = 0)

@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
)

@Serializable
data class ClaudeResponse(
    val id: String? = null,
    val model: String? = null,
    val content: List<ContentBlock> = emptyList(),
    val stop_reason: String? = null,
    val usage: Usage = Usage(),
) {
    val text: String get() = content.filter { it.type == "text" }.mapNotNull { it.text }
        .joinToString("\n").trim()

    val toolCalls: List<ContentBlock> get() = content.filter { it.type == "tool_use" }
}

sealed interface ClaudeResult {
    /**
     * [rawContent] is the assistant's content array exactly as it arrived. Tool
     * round-trips must echo it back verbatim — rebuilding it field by field drops
     * block types this client does not know about (thinking blocks and their
     * signatures, for one) and the next request is rejected.
     */
    data class Ok(val response: ClaudeResponse, val rawContent: JsonArray) : ClaudeResult
    data class Failed(val message: String) : ClaudeResult
    data object NoKey : ClaudeResult
    data object Disabled : ClaudeResult
    data class OverBudget(val spentPaise: Long, val capPaise: Long) : ClaudeResult
}

/**
 * Talks to the Messages API directly — no SDK, one file, easy to reason about.
 *
 * Two rules make this safe to leave switched on: nothing is sent unless the user
 * turned AI on and entered a key, and every call is metered against a monthly
 * rupee cap that stops the app rather than surprising them with a bill.
 */
@Singleton
class ClaudeClient @Inject constructor(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
    private val settings: SettingsStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun send(
        model: String,
        system: String?,
        messages: List<JsonObject>,
        tools: JsonElement? = null,
        maxTokens: Int = 1400,
        /**
         * A JSON schema the reply must follow (`output_config.format`). The answer
         * then parses every time instead of mostly.
         */
        jsonSchema: JsonObject? = null,
    ): ClaudeResult = withContext(Dispatchers.IO) {
        val config = settings.current()
        if (!config.aiEnabled) return@withContext ClaudeResult.Disabled
        val key = secrets.apiKey ?: return@withContext ClaudeResult.NoKey

        val monthKey = YearMonth.now().toString()
        val spent = if (config.aiSpendMonthKey == monthKey) config.aiSpentThisMonthPaise else 0L
        if (spent >= config.aiMonthlyCapPaise) {
            return@withContext ClaudeResult.OverBudget(spent, config.aiMonthlyCapPaise)
        }

        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (system != null) put("system", system)
            put("messages", buildJsonArray { messages.forEach { add(it) } })
            if (tools != null) put("tools", tools)
            if (jsonSchema != null) {
                put("output_config", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "json_schema")
                        put("schema", jsonSchema)
                    })
                })
            }
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use ClaudeResult.Failed(errorMessage(response.code, body))
                }
                val root = json.parseToJsonElement(body).jsonObject
                val parsed = json.decodeFromJsonElement(ClaudeResponse.serializer(), root)
                settings.addAiSpend(estimateCostPaise(model, parsed.usage), monthKey)
                ClaudeResult.Ok(
                    response = parsed,
                    rawContent = root["content"] as? JsonArray ?: JsonArray(emptyList()),
                )
            }
        }.getOrElse { error ->
            ClaudeResult.Failed(error.message ?: "Could not reach the API")
        }
    }

    private fun errorMessage(code: Int, body: String): String = when (code) {
        401 -> "That API key was rejected. Check it in Settings."
        429 -> "Rate limited by the API. Try again in a minute."
        in 500..599 -> "The API is having trouble right now."
        else -> "Request failed ($code). ${body.take(160)}"
    }

    /**
     * Estimated, not billed — good enough to keep the monthly cap honest and to
     * show a running figure in Settings.
     */
    private fun estimateCostPaise(model: String, usage: Usage): Long {
        val (inputPerM, outputPerM) = when (model) {
            Models.FAST -> 1.0 to 5.0
            else -> 3.0 to 15.0
        }
        val usd = usage.input_tokens / 1_000_000.0 * inputPerM +
            usage.output_tokens / 1_000_000.0 * outputPerM
        return (usd * USD_TO_INR * 100).roundToLong()
    }

    fun keyPresent(): Boolean = secrets.hasKey()

    private companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val USD_TO_INR = 88.0
        val JSON_MEDIA = "application/json".toMediaType()
    }
}

fun userMessage(text: String): JsonObject = buildJsonObject {
    put("role", "user")
    put("content", text)
}

fun assistantMessage(text: String): JsonObject = buildJsonObject {
    put("role", "assistant")
    put("content", text)
}
