package ai.artha.core

import kotlinx.serialization.json.Json
import java.time.LocalDate

/** A small language model running on the phone (Qwen 2.5 1.5B). Takes one user turn, returns the model's reply. */
interface LocalModel {
    fun available(): Boolean
    fun generate(prompt: String): String
    /**
     * Same as generate(prefix + suffix), but lets the model keep the work it did on [prefix] (the long,
     * unchanging instructions) and reuse it for the next message. Big speed-up for imports.
     */
    fun generate(prefix: String, suffix: String): String = generate(prefix + suffix)
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
    private val SPLIT = "<<message>>"

    // ---------- prompts ----------
    private fun categoryLine(categories: List<String>) = "Categories: " + categories.joinToString(", ")
    private fun memoryLine(known: Map<String, String>) =
        if (known.isEmpty()) "" else "Known payees and their categories: " + known.entries.take(15).joinToString("; ") { "${it.key} = ${it.value}" } + "\n"

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
SMS: Rs.120.00 sent from A/c XX9012 to BIGBASKET via UPI on 02/11/26 Ref 22334455
JSON: {"type": "debit", "amount": 120, "party": "Bigbasket", "category": "Groceries"}
SMS: A/c XX5566 credited with Rs.3,000.00 by vpa amit.joshi9@okhdfcbank on 01-11-2026. Avl Bal Rs 12,000
JSON: {"type": "credit", "amount": 3000, "party": "Amit Joshi", "category": "Transfers"}
SMS: Your a/c XX5566 is debited for Rs.75.00 on 01-11-2026 and credited to vpa meera.k12@ybl. Balance INR 9000
JSON: {"type": "debit", "amount": 75, "party": "Meera K", "category": "Transfers"}
SMS: UPI AutoPay mandate for Spotify debit of Rs.119.00 is scheduled on 05-12-2026
JSON: {"type": "upcoming", "amount": 119, "party": "Spotify", "category": "Entertainment"}
SMS: Congratulations! You are eligible for a credit card with limit Rs.2,00,000. Apply now
JSON: {"type": "ignore", "amount": 0, "party": "", "category": "Other"}
$SPLIT
SMS: $sms
JSON:""".trimIndent()

    /** The SMS prompt cut into the part shared by every message and the part that changes. */
    fun smsPromptParts(sms: String, categories: List<String>, known: Map<String, String>): Pair<String, String> {
        val full = smsPrompt(sms, categories, known)
        val i = full.indexOf(SPLIT)
        return full.substring(0, i) to full.substring(i + SPLIT.length).trimStart('\n')
    }

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

    private fun askSplit(prefix: String, suffix: String): Map<String, Any?>? {
        if (!model.available()) throw ModelMissing()
        repeat(2) { model.generate(prefix, suffix).let { r -> parseObject(r)?.let { return it } } }
        return null
    }

    private fun ask(prompt: String): Map<String, Any?>? {
        if (!model.available()) throw ModelMissing()
        // One retry: small models occasionally produce malformed JSON.
        repeat(2) { model.generate(prompt).let { r -> parseObject(r)?.let { return it } } }
        return null
    }

    // ---------- safety check ----------
    // The model does all the reading. These words only veto its answer, because small models
    // can't reliably tell "money moved" from "money will move" or "money didn't move".
    private val NOT_A_PAYMENT = listOf("otp", "one time password", "verification code", "failed", "declined", "unsuccessful",
        "not processed", "could not be processed", "pre-approved", "preapproved", "apply now",
        // IPO bids and mandate set-up only block or authorise money; nothing is spent yet.
        "ipo", "blocked", "unblocked", "mandate request", "mandate registered", "mandate created", "mandate revoked",
        "mandate paused", "mandate cancelled", "collect request", "has requested", "requested money")
    private val MONEY_OUT = listOf("debited", "debit", "paid", "sent", "spent", "withdrawn", "purchase", "transferred", "deducted")
    private val LATER = listOf("scheduled", "will be debited", "to be debited", "is due", "due on", "due date", "will be deducted")

    /** Lower-case words joined by single spaces, so phrases match on word boundaries without patterns. */
    private fun words(text: String): String =
        " " + text.lowercase().map { if (it.isLetterOrDigit() || it == '-') it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ") + " "

    /** Returns (forced type, reason) when the safety check overrides the model, else null. */
    fun safetyCheck(text: String): Pair<String, String>? {
        val w = words(text)
        NOT_A_PAYMENT.firstOrNull { " $it " in w }?.let { return "ignore" to "Mentions \"$it\", so it isn't a completed payment" }
        LATER.firstOrNull { " $it " in w }?.let { return "upcoming" to "Payment is only scheduled" }
        return null
    }

    data class Verdict(val item: Item?, val reason: String?)

    /** True when the message only says money came in. Used to skip received money without running the model. */
    fun onlyMoneyIn(text: String): Boolean { val w = words(text); return " credited " in w && MONEY_OUT.none { " $it " in w } }

    /** True when the message says money went out. Past-SMS import only reads these, to keep the work small. */
    fun mentionsMoneyOut(text: String): Boolean { val w = words(text); return MONEY_OUT.any { " $it " in w } }

    // Answers that name the user's own account, a card or bank, or a code instead of a real payee.
    private val NOT_A_PAYEE = listOf("your account", "your a/c", "account", "a/c", "self", "you", "customer", "cc payment", "cc payments",
        "credit card", "card", "bank", "co-op", "slice", "upi", "upi payment", "upi transfer", "vpa", "neft", "imps", "rtgs", "transfer", "payment", "hdfc", "icici", "sbi", "axis", "kotak", "saraswat", "idfc",
        "indusind", "federal", "rbl", "yes bank", "canara", "union bank", "bank of baroda", "pnb", "unknown")
    fun isNotAPayee(party: String): Boolean {
        val p = party.lowercase().trim()
        if (p.length < 3) return true
        if (p.any { it.isDigit() } && ' ' !in p) return true // codes like D04
        if (p.any { it == '@' || it == '_' } || (p.contains('.') && ' ' !in p)) return true // raw UPI IDs
        return NOT_A_PAYEE.any { p == it || p.startsWith("$it ") || p.endsWith(" $it") || p.contains(" $it ") }
    }

    /** Reads one SMS. The safety check can turn a debit into "ignore" or "upcoming". */
    /**
     * Banks sign every SMS ("... - Saraswat Co-op Bank Ltd."). Small models tend to report that signature as
     * the payee, so it's cut off before the model reads the message.
     */
    fun withoutSignature(sms: String): String {
        val cut = sms.lastIndexOf(" - ")
        if (cut < 0) return sms
        val tail = sms.substring(cut + 3)
        return if (tail.length <= 60 && tail.lowercase().contains("bank")) sms.substring(0, cut).trim() else sms
    }

    // ---------- UPI IDs ----------
    /** The UPI ID in a message ("bhqr.2933317A@sib"), lower-cased, found by plain text cutting. */
    fun upiId(sms: String): String? = sms.split(' ', '(', ')', ',', ';', '\n').map { it.trim().trimEnd('.', ':') }
        .firstOrNull { val at = it.indexOf('@'); at > 1 && at < it.length - 2 && it.count { c -> c == '@' } == 1 }?.lowercase()

    /** A shop QR code or a bare phone number: the SMS can't tell us who it is. */
    fun isAnonymousUpi(id: String): Boolean {
        val user = id.substringBefore('@'); val bank = id.substringAfter('@')
        return user.all { it.isDigit() } || "qr" in user || bank in setOf("ptys", "paytm", "pthdfc", "ptyes", "ptaxis", "ptsbi") ||
            user.startsWith("q") && user.drop(1).all { it.isDigit() } || user.startsWith("gpay-") || bank.startsWith("okbiz")
    }

    /** A readable stand-in until you name the payee: "Paytm QR shop", "UPI 83800 81708". */
    fun labelForUpi(id: String): String {
        val user = id.substringBefore('@'); val bank = id.substringAfter('@')
        return when {
            user.all { it.isDigit() } -> "UPI " + (if (user.length == 10) user.take(5) + " " + user.drop(5) else user)
            "paytm" in user || bank.startsWith("pt") || bank == "paytm" -> "Paytm QR shop"
            "bhqr" in user || "bharatpe" in user -> "BharatPe QR shop"
            user.startsWith("q") && user.drop(1).all { it.isDigit() } -> "PhonePe QR shop"
            user.startsWith("gpay-") || bank.startsWith("okbiz") -> "GPay merchant"
            else -> "QR shop"
        }
    }

    /** True for the stand-in labels above: the payee still needs a name from you. */
    fun isPlaceholder(party: String?): Boolean = party == null || party == "Unknown" || party.startsWith("UPI ") ||
        party.endsWith("QR shop") || party == "GPay merchant"

    /**
     * Reads type and amount without the model, for payees you've already named. Returns null when unsure,
     * and the model reads the message instead.
     */
    fun quickRead(sms: String, trackReceived: Boolean): Pair<String, Double>? {
        if (safetyCheck(sms) != null) return null
        val low = sms.lowercase()
        val at = listOf("rs.", "rs ", "inr", "₹").map { low.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return null
        val amount = numbersIn(sms.substring(at)).firstOrNull()?.takeIf { it > 0 } ?: return null
        return when { mentionsMoneyOut(sms) -> "debit" to amount; trackReceived && onlyMoneyIn(sms) -> "credit" to amount; else -> null }
    }

    /** "shravani.shinde109-1@okicici" -> "Shravani Shinde": the name part of a UPI ID, by plain text cutting. */
    fun nameFromUpiId(sms: String): String? {
        val token = sms.split(' ', '(', ')', ',', ';').firstOrNull { '@' in it && it.indexOf('@') > 1 } ?: return null
        val name = token.substringBefore('@').map { if (it.isLetter()) it else ' ' }.joinToString("").trim()
            .split(' ').filter { it.length > 1 }.joinToString(" ")
        return name.takeIf { it.length >= 3 }?.let { titleCase(it) }
    }

    private fun isBankName(party: String) = party.lowercase().let { "bank" in it || " co-op" in it || "ltd" in it }

    fun fromSms(sms: String, categories: List<String>, known: Map<String, String>, trackReceived: Boolean = true): Verdict {
        val safety = safetyCheck(sms)
        if (safety?.first == "ignore") return Verdict(Item("ignore", 0.0, "", null, "Other", true), safety.second)
        if (!trackReceived && onlyMoneyIn(sms)) return Verdict(Item("ignore", 0.0, "", null, "Other", true), "Money received isn't tracked (Settings)")
        val clean = withoutSignature(sms)
        val (prefix, suffix) = smsPromptParts(clean, categories, known)
        var item = askSplit(prefix, suffix)?.let { toItem(it, clean, categories) } ?: return Verdict(null, "The AI couldn't read this message")
        // A shop QR or a phone number: the SMS doesn't say who it is, so use a clear label and ask you once.
        val upi = upiId(clean)
        if (item.type != "ignore" && upi != null && isAnonymousUpi(upi)) item = item.copy(party = labelForUpi(upi), confident = false)
        // Your account, a bank or card name, a code or a raw UPI ID isn't a payee: use the name inside the UPI ID, else ask.
        else if (item.type != "ignore" && isNotAPayee(item.party)) {
            val fromId = nameFromUpiId(clean)?.takeIf { !isNotAPayee(it) }
            item = if (fromId != null) item.copy(party = fromId) else item.copy(party = "Unknown", confident = false)
        }
        if (item.type == "credit" && !trackReceived) return Verdict(item.copy(type = "ignore"), "Money received isn't tracked (Settings)")
        if (safety?.first == "upcoming" && item.type != "ignore") return Verdict(item.copy(type = "upcoming"), safety.second)
        return Verdict(item, null)
    }

    @Suppress("UNCHECKED_CAST")
    fun fromNote(text: String, categories: List<String>, known: Map<String, String>, today: LocalDate): List<Item> {
        val o = ask(notePrompt(text, categories, known, today)) ?: return emptyList()
        return ((o["items"] as? List<Any?>) ?: emptyList()).mapNotNull { (it as? Map<String, Any?>)?.let { m -> toItem(m, text, categories, "debit") } }
    }

    fun fromRow(header: String, row: String, categories: List<String>, known: Map<String, String>): Verdict {
        val item = ask(rowPrompt(header, row, categories, known))?.let { toItem(it, row, categories) } ?: return Verdict(null, "The AI couldn't read this row")
        return Verdict(item, null)
    }

    /** "lunch with team" + payee -> one of your categories. */
    fun categoryFor(purpose: String, party: String, categories: List<String>): String? {
        val prompt = """
Pick the spending category for a payment. Reply with only one JSON object: {"category": "..."}
category must be exactly one of: ${categories.joinToString(", ")}
Example: paid to Rohan, purpose "dinner" -> {"category": "Food & Dining"}
Example: paid to Shell, purpose "petrol" -> {"category": "Fuel"}
Paid to: $party
Purpose: $purpose
JSON:""".trimIndent()
        val c = ask(prompt)?.get("category") as? String ?: return null
        return categories.firstOrNull { it.equals(c.trim(), true) }
    }

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
