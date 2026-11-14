package ai.artha.app

import ai.artha.core.Ai
import ai.artha.core.fromJson
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaRequestDocumentBlock
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDate
import java.util.Base64

/**
 * Optional AI features, calling Claude directly from the phone with the user's own API key.
 * Privacy: only what each feature needs is sent (the redacted sentence, a receipt you chose to scan,
 * a question, or aggregated monthly totals). Never the ledger, never account numbers.
 */
class ClaudeAi(private val keyProvider: () -> String?) : Ai {
    private val model = "claude-opus-5-5"
    @Volatile private var client: AnthropicClient? = null
    @Volatile private var clientKey: String? = null

    override fun available(): Boolean = !keyProvider().isNullOrBlank()

    private fun client(): AnthropicClient {
        val key = keyProvider()?.trim().takeUnless { it.isNullOrEmpty() } ?: throw Ai.Unavailable("Add your Claude API key in Settings")
        if (client == null || clientKey != key) {
            client = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(90)).build()
            clientKey = key
        }
        return client!!
    }

    private fun obj(props: Map<String, Any>, required: List<String> = props.keys.toList()) =
        mapOf("type" to "object", "properties" to props, "required" to required, "additionalProperties" to false)
    private val str = mapOf("type" to "string")
    private val numT = mapOf("type" to "number")
    private val boolT = mapOf("type" to "boolean")
    private fun arr(items: Any) = mapOf("type" to "array", "items" to items)

    /** One structured-output call. Declined requests fall back to another model server-side. */
    @Suppress("UNCHECKED_CAST")
    private fun call(system: String, content: List<BetaContentBlockParam>, schema: Map<String, Any>, effort: BetaOutputConfig.Effort,
                     maxTokens: Long = 4000): Map<String, Any?> {
        val schemaObj = BetaJsonOutputFormat.Schema.builder().apply { schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .system(system)
            .outputConfig(BetaOutputConfig.builder().effort(effort).format(BetaJsonOutputFormat.builder().schema(schemaObj).build()).build())
            .addUserMessageOfContentBlockParams(content)
            .build()
        val resp = try {
            client().beta().messages().create(params)
        } catch (e: UnauthorizedException) { throw Ai.Unavailable("Your Claude API key was rejected. Check it in Settings")
        } catch (e: RateLimitException) { throw Ai.Unavailable("AI is busy right now, try again in a minute")
        } catch (e: AnthropicServiceException) { throw Ai.Unavailable("AI request failed (${e.statusCode()})")
        } catch (e: AnthropicIoException) { throw Ai.Unavailable("No internet connection for AI")
        }
        if (resp.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw Ai.Unavailable("AI couldn't process that")
        val text = resp.content().firstNotNullOfOrNull { it.text().orElse(null)?.text() } ?: throw Ai.Unavailable("AI returned nothing")
        return try { fromJson(Json.parseToJsonElement(text)) as Map<String, Any?> } catch (e: Exception) { throw Ai.Unavailable("AI reply was incomplete") }
    }

    private fun textBlock(t: String) = BetaContentBlockParam.ofText(t)

    @Suppress("UNCHECKED_CAST")
    override fun parseExpenses(text: String, categories: List<String>, today: LocalDate): List<Ai.Expense> {
        val expense = obj(mapOf("amount" to numT, "merchant" to str, "category" to mapOf("type" to "string", "enum" to categories),
            "kind" to mapOf("type" to "string", "enum" to listOf("debit", "credit")), "date" to str, "split_with" to arr(str),
            "split_ways" to mapOf("type" to "integer"), "confident" to boolT))
        val system = "You turn a person's spoken or typed note about money into structured expenses for an Indian personal finance app. " +
            "Currency is INR. Today is $today (${today.dayOfWeek}). Resolve relative dates and give dates as YYYY-MM-DD. " +
            "merchant is a short proper name of who was paid. split_with lists other people sharing the cost (empty if none); " +
            "split_ways is the total number of people including the user, or 0 if not split. confident is false if amount or merchant was a guess. " +
            "Return one entry per distinct payment, or an empty list if there is no payment."
        val out = call(system, listOf(textBlock(text)), obj(mapOf("expenses" to arr(expense))), BetaOutputConfig.Effort.LOW)
        return (out["expenses"] as? List<Map<String, Any?>> ?: emptyList()).map {
            Ai.Expense((it["amount"] as Number).toDouble(), it["merchant"] as String, it["category"] as String, it["kind"] as String,
                it["date"] as String, (it["split_with"] as? List<String>) ?: emptyList(), (it["split_ways"] as? Number)?.toInt() ?: 0,
                it["confident"] as? Boolean ?: true)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun scanReceipt(data: ByteArray, mediaType: String, categories: List<String>, today: LocalDate): Ai.Receipt {
        val b64 = Base64.getEncoder().encodeToString(data)
        val block = if (mediaType == "application/pdf") BetaContentBlockParam.ofDocument(BetaRequestDocumentBlock.builder().base64Source(b64).build())
        else BetaContentBlockParam.ofImage(BetaBase64ImageSource.builder().data(b64).mediaType(BetaBase64ImageSource.MediaType.of(mediaType)).build())
        val schema = obj(mapOf("merchant" to str, "total" to numT, "date" to str, "category" to mapOf("type" to "string", "enum" to categories),
            "items" to arr(str), "confident" to boolT))
        val system = "You read receipts and invoices for an Indian expense tracker. Extract the merchant, the final total actually paid in INR " +
            "(including taxes and tip), the date as YYYY-MM-DD (empty string if not visible), a category, and up to 8 short line items. " +
            "confident is false if the total or merchant is hard to read. Today is $today."
        val r = call(system, listOf(block, textBlock("Extract this receipt.")), schema, BetaOutputConfig.Effort.MEDIUM)
        return Ai.Receipt(r["merchant"] as String, (r["total"] as Number).toDouble(), r["date"] as? String ?: "", r["category"] as String,
            (r["items"] as? List<String>) ?: emptyList(), r["confident"] as? Boolean ?: true)
    }

    override fun planQuery(question: String, categories: List<String>, today: LocalDate): Map<String, Any?> {
        val schema = obj(mapOf("date_from" to str, "date_to" to str, "categories" to arr(mapOf("type" to "string", "enum" to categories)),
            "merchant" to str, "text" to str, "kind" to mapOf("type" to "string", "enum" to listOf("debit", "credit", "any")),
            "min_amount" to numT, "max_amount" to numT, "aggregate" to mapOf("type" to "string", "enum" to listOf("sum", "count", "list", "by_category"))))
        val system = "You convert questions about a person's spending into a database filter for an Indian expense tracker. " +
            "Today is $today (${today.dayOfWeek}); weeks start Monday. Dates are YYYY-MM-DD or empty string for no bound. " +
            "Use empty string / 0 for unused filters. Use 'sum' for 'how much', 'count' for 'how many', 'by_category' for breakdowns, " +
            "'list' for show/find. Map loose words to categories (e.g. 'eating out' -> Food & Dining)."
        val p = call(system, listOf(textBlock(question)), schema, BetaOutputConfig.Effort.LOW, 2000)
        fun s(k: String) = (p[k] as? String)?.takeIf { it.isNotBlank() }
        fun n(k: String) = (p[k] as? Number)?.toDouble()?.takeIf { it > 0 }
        return mapOf("date_from" to s("date_from"), "date_to" to s("date_to"), "categories" to (p["categories"] ?: emptyList<String>()),
            "merchant" to s("merchant"), "text" to s("text"), "kind" to s("kind")?.takeIf { it != "any" },
            "min_amount" to n("min_amount"), "max_amount" to n("max_amount"), "aggregate" to (s("aggregate") ?: "list"))
    }

    override fun monthlyInsights(statsJson: String): Map<String, Any?> {
        val schema = obj(mapOf("headline" to str, "insights" to arr(str), "recommendations" to arr(str)))
        val system = "You are a friendly, practical personal finance coach for someone in India. You get aggregated spending numbers for one " +
            "month (INR) plus the previous month, their daily budget status, subscriptions and suggested budgets. Write a one-sentence headline, " +
            "3-5 specific observations and 2-4 concrete personalised recommendations. Use the numbers, ₹ and Indian digit grouping, keep each " +
            "point to one sentence, and never invent data that is not provided."
        return call(system, listOf(textBlock(statsJson)), schema, BetaOutputConfig.Effort.MEDIUM, 8000)
    }
}
