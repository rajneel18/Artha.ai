package ai.artha.core

import kotlinx.serialization.json.Json
import java.time.LocalDate

/** A small language model running on the phone (Gemma 3 1B). Takes one user turn, returns the model's reply. */
interface LocalModel {
    fun available(): Boolean
    fun generate(prompt: String): String
}

class ModelMissing : Exception("Set up the on-device AI model in Settings first")

/**
 * Understands bank SMS, typed or spoken notes, and statement rows using the on-device model.
 * No hand-written patterns: the model reads the text, and every answer is double-checked
 * (the amount must actually appear in the message) before it reaches the ledger.
 */
class Extractor(private val model: LocalModel) {

    data class Item(
        val type: String,          // debit | credit | upcoming | ignore
        val amount: Double,
        val party: String,         // who was paid / who paid / which service
        val date: LocalDate?,      // null = use the message's own date
        val category: String,
        val confident: Boolean,
        val splitWith: List<String> = emptyList(),
        val splitWays: Int? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------- prompts ----------
    private fun categoryLine(categories: List<String>) = "Categories: " + categories.joinToString(", ")
    private fun memoryLine(known: Map<String, String>) =
        if (known.isEmpty()) "" else "Known payees and their categories: " + known.entries.take(25).joinToString("; ") { "${it.key} = ${it.value}" } + "\n"

    fun smsPrompt(sms: String, categories: List<String>, known: Map<String, String>): String = """
Read one bank SMS and reply with only one JSON object.
Fields:
type: "debit" if money left the user's account (debited, sent, paid, spent). "credit" if money came in (credited to your account, received, refund). "upcoming" if a payment or autopay is only scheduled or due. "ignore" for OTP, failed payment, offers, loans, promotions.
amount: the money moved, as a number. Never the balance or limit.
party: the shop, service or person on the other side. Never the bank. From a UPI ID like priya.nair07@okaxis write Priya Nair.
category: one word-for-word choice from: ${categories.joinToString(", ")}. A payment to a person is Transfers.
${memoryLine(known)}
SMS: INR 640.00 spent on HDFC Bank Card XX4321 at UBER INDIA on 03-Nov-26. Avl Lmt: INR 90,000
JSON: {"type": "debit", "amount": 640, "party": "Uber", "category": "Transport"}
SMS: Rs.120.00 sent from A/c XX9012 to BIGBASKET via UPI on 02/11/26 Ref 22334455 - Kotak Bank
JSON: {"type": "debit", "amount": 120, "party": "Bigbasket", "category": "Groceries"}
SMS: A/c XX5566 credited with Rs.3,000.00 by vpa amit.joshi9@okhdfcbank on 01-11-2026. Avl Bal Rs 12,000 - SBI
JSON: {"type": "credit", "amount": 3000, "party": "Amit Joshi", "category": "Transfers"}
SMS: Your a/c XX5566 is debited for Rs.75.00 on 01-11-2026 and credited to vpa meera.k12@ybl. Balance INR 9000 - Axis Bank
JSON: {"type": "debit", "amount": 75, "party": "Meera K", "category": "Transfers"}
SMS: UPI AutoPay mandate for Spotify debit of Rs.119.00 is scheduled on 05-12-2026 - Canara Bank
JSON: {"type": "upcoming", "amount": 119, "party": "Spotify", "category": "Entertainment"}
SMS: Congratulations! You are eligible for a credit card with limit Rs.2,00,000. Apply now - HDFC Bank
JSON: {"type": "ignore", "amount": 0, "party": "", "category": "Other"}
SMS: $sms
JSON:""".trimIndent()

    fun notePrompt(text: String, categories: List<String>, known: Map<String, String>, today: LocalDate): String = """
You turn a person's typed or spoken note about money into JSON for an Indian expense tracker. Today is $today.
Reply with only one JSON object: {"items": [{"type": "debit", "amount": 0, "party": "...", "date": "YYYY-MM-DD", "category": "...", "split_with": [], "split_ways": 0, "confidence": "high"}]}
One item per payment. type is "debit" for spending or "credit" for money received. date: resolve words like yesterday; use today if no date is said.
party: who was paid, in Title Case. split_with: names of other people sharing the cost (empty if not shared). split_ways: total people sharing including the user, 0 if not shared.
category: exactly one item from the categories list. If there is no payment in the note, reply {"items": []}.
${categoryLine(categories)}
${memoryLine(known)}
Example note: 450 coffee with asha, split it
Example JSON: {"items": [{"type": "debit", "amount": 450, "party": "Coffee", "date": "$today", "category": "Food & Dining", "split_with": ["Asha"], "split_ways": 2, "confidence": "high"}]}

Note: $text
JSON:""".trimIndent()

    fun rowPrompt(header: String, row: String, categories: List<String>, known: Map<String, String>): String = """
You read one row of a bank statement or transaction list and turn it into JSON for an Indian expense tracker.
Reply with only one JSON object: {"type": "...", "amount": 0, "party": "...", "date": "YYYY-MM-DD", "category": "...", "confidence": "high"}
type: "debit" (withdrawal / money spent), "credit" (deposit / money received), or "ignore" (headers, totals, balances, empty rows).
amount: the transaction amount as a positive number, never the running balance. date: the row's date as YYYY-MM-DD.
party: the merchant or person in Title Case, cleaned of codes like UPI/POS/reference numbers.
category: exactly one item from the categories list.
${categoryLine(categories)}
${memoryLine(known)}
Columns: $header
Row: $row
JSON:""".trimIndent()

    fun questionPrompt(question: String, categories: List<String>, today: LocalDate): String = """
Turn a question about the user's spending into a JSON search filter. Today is $today (${today.dayOfWeek}); weeks start Monday.
Reply with only one JSON object: {"date_from": "", "date_to": "", "categories": [], "party": "", "kind": "debit", "min_amount": 0, "max_amount": 0, "aggregate": "sum"}
Dates are YYYY-MM-DD or "" for no limit. categories only from the list. kind is "debit", "credit" or "any". aggregate is "sum" (how much), "count" (how many), "list" (show/find) or "by_category" (breakdown). Use "" or 0 for unused filters.
${categoryLine(categories)}
Question: $question
JSON:""".trimIndent()

    // ---------- reading the model's answer ----------
    @Suppress("UNCHECKED_CAST")
    fun parseObject(reply: String): Map<String, Any?>? {
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { fromJson(json.parseToJsonElement(reply.substring(start, end + 1))) as? Map<String, Any?> } catch (_: Exception) { null }
    }

    private fun num(v: Any?): Double? = when (v) { is Number -> v.toDouble(); is String -> v.replace(",", "").trim().toDoubleOrNull(); else -> null }

    /** Every number written in the text (handles 1,234.50 and Rs.499.00), found by scanning characters. */
    fun numbersIn(text: String): List<Double> {
        val out = mutableListOf<Double>(); val cur = StringBuilder()
        fun flush() { cur.toString().trimEnd('.', ',').replace(",", "").toDoubleOrNull()?.let { out += it }; cur.clear() }
        for (c in text) if (c.isDigit() || ((c == ',' || c == '.') && cur.isNotEmpty())) cur.append(c) else flush()
        flush(); return out
    }

    /** Guard against the model inventing a number: the amount must be written somewhere in the source text. */
    fun amountIsInText(amount: Double, text: String): Boolean = numbersIn(text).any { kotlin.math.abs(it - amount) < 0.005 }

    private fun toItem(o: Map<String, Any?>, source: String, categories: List<String>, defaultType: String? = null): Item? {
        val type = (o["type"] as? String)?.lowercase()?.trim() ?: defaultType ?: return null
        if (type !in setOf("debit", "credit", "upcoming", "ignore")) return null
        if (type == "ignore") return Item("ignore", 0.0, "", null, "Other", true)
        val amount = num(o["amount"])?.let { kotlin.math.abs(it) } ?: return null
        if (amount <= 0) return null
        val inText = amountIsInText(amount, source)
        val party = (o["party"] as? String)?.trim()?.take(60).orEmpty().ifBlank { "Unknown" }
        val cat = (o["category"] as? String)?.trim()?.let { c -> categories.firstOrNull { it.equals(c, true) } } ?: "Other"
        val date = (o["date"] as? String)?.let { try { LocalDate.parse(it.trim()) } catch (_: Exception) { null } }
        @Suppress("UNCHECKED_CAST")
        val split = (o["split_with"] as? List<Any?>)?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } } ?: emptyList()
        val ways = num(o["split_ways"])?.toInt()?.takeIf { it >= 2 }
        val confident = (o["confidence"] as? String)?.lowercase() != "low" && inText && party != "Unknown"
        return Item(type, amount, party, date, cat, confident, split, ways)
    }

    private fun ask(prompt: String): Map<String, Any?>? {
        if (!model.available()) throw ModelMissing()
        // One retry: small models occasionally produce malformed JSON.
        repeat(2) { model.generate(prompt).let { r -> parseObject(r)?.let { return it } } }
        return null
    }

    fun fromSms(sms: String, categories: List<String>, known: Map<String, String>): Item? =
        ask(smsPrompt(sms, categories, known))?.let { toItem(it, sms, categories) }

    @Suppress("UNCHECKED_CAST")
    fun fromNote(text: String, categories: List<String>, known: Map<String, String>, today: LocalDate): List<Item> {
        val o = ask(notePrompt(text, categories, known, today)) ?: return emptyList()
        return ((o["items"] as? List<Any?>) ?: emptyList()).mapNotNull { (it as? Map<String, Any?>)?.let { m -> toItem(m, text, categories, "debit") } }
    }

    fun fromRow(header: String, row: String, categories: List<String>, known: Map<String, String>): Item? =
        ask(rowPrompt(header, row, categories, known))?.let { toItem(it, row, categories) }

    fun plan(question: String, categories: List<String>, today: LocalDate): Map<String, Any?>? {
        val p = ask(questionPrompt(question, categories, today)) ?: return null
        fun s(k: String) = (p[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        fun n(k: String) = num(p[k])?.takeIf { it > 0 }
        @Suppress("UNCHECKED_CAST")
        val cats = ((p["categories"] as? List<Any?>) ?: emptyList()).mapNotNull { c -> categories.firstOrNull { it.equals(c as? String, true) } }
        return mapOf("date_from" to s("date_from"), "date_to" to s("date_to"), "categories" to cats, "merchant" to s("party"),
            "text" to null, "kind" to s("kind")?.takeIf { it != "any" }, "min_amount" to n("min_amount"), "max_amount" to n("max_amount"),
            "aggregate" to (s("aggregate")?.takeIf { it in setOf("sum", "count", "list", "by_category") } ?: "list"))
    }
}
