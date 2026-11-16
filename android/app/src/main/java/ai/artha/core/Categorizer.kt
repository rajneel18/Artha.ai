package ai.artha.core

/** Applies your own rules and the merchants you corrected. Everything else is categorized by the on-device AI. */
object Categorizer {
    val CATEGORIES = listOf(
        "Food & Dining", "Snacks", "Groceries", "Transport", "Fuel", "Shopping", "Bills & Utilities", "Rent", "Entertainment",
        "Health", "Education", "Travel", "Investments", "Transfers", "Personal Care", "Other",
    )

    fun norm(text: String?): String = (text ?: "").lowercase().replace(Regex("\\s+"), " ").trim()
    fun merchantKey(merchant: String?): String = norm(merchant)

    data class Rule(val pattern: String, val matchType: String, val category: String,
                    val minAmount: Double? = null, val maxAmount: Double? = null, val priority: Long = 0)

    fun ruleMatches(r: Rule, text: String, merchant: String, amount: Double?): Boolean {
        if (r.minAmount != null && (amount == null || amount < r.minAmount)) return false
        if (r.maxAmount != null && (amount == null || amount > r.maxAmount)) return false
        return when (r.matchType) {
            "merchant" -> norm(r.pattern) == norm(merchant)
            "regex" -> try { Regex(r.pattern, RegexOption.IGNORE_CASE).containsMatchIn("$merchant $text") } catch (_: Exception) { false }
            else -> norm(r.pattern) in " ${norm(merchant)} ${norm(text)} "
        }
    }

    fun categorize(text: String?, merchant: String?, learned: Map<String, String> = emptyMap(),
                   rules: List<Rule> = emptyList(), amount: Double? = null): String {
        for (r in rules.sortedByDescending { it.priority }) {
            if (ruleMatches(r, text ?: "", merchant ?: "", amount)) return r.category
        }
        val m = norm(merchant)
        if (m.isNotEmpty()) {
            learned[m]?.let { return it }
            for ((k, c) in learned) if (k.isNotEmpty() && (k in m || m in k)) return c
        }
        return "Other" // the on-device AI picks categories; this only applies your rules and corrections
    }

    /** Everyday words -> category, checked before the AI ("chai" -> Snacks). Whole words only. */
    private val HINTS: List<Pair<String, List<String>>> = listOf(
        "Snacks" to listOf("chai", "tea", "coffee", "cofee", "samosa", "vada pav", "vadapav", "pav", "snack", "snacks", "biscuit", "biscuits",
            "chips", "juice", "cold drink", "coke", "pepsi", "ice cream", "icecream", "kulfi", "lassi", "bhel", "panipuri", "pani puri",
            "chaat", "maggi", "sandwich", "puff", "cutting", "nimbu pani", "sugarcane", "golgappa", "momos", "chocolate", "pastry", "donut"),
        "Food & Dining" to listOf("lunch", "dinner", "breakfast", "food", "meal", "thali", "biryani", "pizza", "burger", "restaurant", "hotel",
            "canteen", "mess", "tiffin", "dhaba", "zomato", "swiggy", "dosa", "idli", "party", "cafe"),
        "Groceries" to listOf("grocery", "groceries", "sabzi", "sabji", "vegetables", "veggies", "fruits", "fruit", "milk", "doodh", "kirana",
            "ration", "dmart", "d mart", "blinkit", "zepto", "bigbasket", "eggs", "bread", "atta", "rice", "dal", "oil"),
        "Fuel" to listOf("petrol", "diesel", "fuel", "cng", "pump", "hp petrol", "indian oil", "bharat petroleum"),
        "Transport" to listOf("auto", "rickshaw", "cab", "uber", "ola", "rapido", "taxi", "bus", "metro", "train ticket", "local train",
            "parking", "toll", "fastag"),
        "Travel" to listOf("flight", "trip", "hotel stay", "irctc", "makemytrip", "goibibo", "holiday", "tour"),
        "Shopping" to listOf("clothes", "shirt", "jeans", "shoes", "amazon", "flipkart", "myntra", "meesho", "shopping", "gift", "watch", "bag"),
        "Bills & Utilities" to listOf("recharge", "electricity", "light bill", "wifi", "broadband", "internet", "bill", "gas cylinder",
            "dth", "postpaid", "water bill", "jio", "airtel"),
        "Rent" to listOf("rent", "pg", "hostel", "deposit"),
        "Entertainment" to listOf("movie", "movies", "netflix", "spotify", "hotstar", "prime", "game", "games", "concert", "bookmyshow", "pvr", "inox"),
        "Health" to listOf("medicine", "medicines", "medical", "pharmacy", "chemist", "doctor", "hospital", "clinic", "tablets", "gym", "lab test"),
        "Education" to listOf("fees", "fee", "college", "school", "tuition", "course", "books", "book", "stationery", "xerox", "exam", "notes"),
        "Personal Care" to listOf("haircut", "salon", "parlour", "parlor", "barber", "shampoo", "soap", "cosmetics", "spa"),
        "Investments" to listOf("sip", "mutual fund", "stocks", "shares", "fd", "gold"),
        "Transfers" to listOf("mom", "mummy", "mum", "mother", "aai", "maa", "dad", "papa", "father", "baba", "bhai", "brother", "sister",
            "didi", "tai", "friend", "returned", "return", "loan", "lent", "borrowed", "pocket money", "family", "transfer", "self"),
    )

    /** First category whose word appears in [text]; null when nothing matches (then the AI decides). */
    fun hint(text: String?, categories: List<String> = CATEGORIES): String? {
        val t = " " + norm(text).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ") + " "
        if (t.isBlank()) return null
        for ((cat, words) in HINTS) if (cat in categories && words.any { " $it " in t }) return cat
        return null
    }
}

/** True when a payee "name" isn't a real name: stand-in labels, "UPI", "Unknown", or letter soup like "Bhqr". */
fun needsName(party: String?): Boolean {
    val p = party?.trim() ?: return true
    val l = p.lowercase()
    if (l.isEmpty() || l == "unknown" || l == "upi" || l.startsWith("upi ") || l.endsWith("qr shop") || l == "gpay merchant" || l == "qr") return true
    if ('@' in l || l.all { it.isDigit() || it == ' ' }) return true
    // One short word with no vowels is a code from the SMS, not a name (bhqr, pytm, ...).
    if (' ' !in l && l.length <= 6 && l.all { it.isLetter() } && l.none { it in "aeiouy" }) return true
    return false
}

/** Masks account/card numbers and drops balances/OTPs before anything is stored or sent anywhere. */
object Redact {
    private val LONG_NUM = Regex("(?<![\\d.,])(\\d{4,})(\\d{4})(?![\\d.,])")
    // Drops "Avl Bal Rs 1,234.56", "Your Current Balance is INR 84413.27", "Avl Lmt: ..." up to the end of that sentence.
    private val BALANCE = Regex("(?i)\\b(avl\\.?\\s*bal|(?:available|current|closing|clear)\\s+bal(?:ance)?|balance|avl\\.?\\s*lmt|available\\s+limit|bal)\\b.*?(?=\\.\\s|;|\\n|$)")
    private val OTP = Regex("(?i)\\b(otp|one[- ]time password|verification code)\\b[^.;\\n]*")

    fun apply(text: String?): String {
        if (text.isNullOrEmpty()) return text ?: ""
        var t = LONG_NUM.replace(text) { m -> "X".repeat(minOf(m.groupValues[1].length, 4)) + m.groupValues[2] }
        t = BALANCE.replace(t, "[balance hidden]")
        t = OTP.replace(t, "[code hidden]")
        return t
    }
}
