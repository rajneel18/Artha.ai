package ai.artha.core

/** Rule-based categorizer: your rules -> merchants you corrected -> built-in keywords. */
object Categorizer {
    val CATEGORIES = listOf(
        "Food & Dining", "Groceries", "Transport", "Fuel", "Shopping", "Bills & Utilities", "Rent", "Entertainment",
        "Health", "Education", "Travel", "Investments", "Transfers", "Personal Care", "Other",
    )

    private val KEYWORDS: Map<String, List<String>> = mapOf(
        "Food & Dining" to listOf("swiggy", "zomato", "restaurant", "cafe", "coffee", "starbucks", "dominos", "domino",
            "pizza", "mcdonald", "kfc", "burger", "biryani", "dinner", "lunch", "breakfast", "snacks", "tea", "chai",
            "eatfit", "dunzo food", "barbeque", "hotel food", "canteen"),
        "Groceries" to listOf("blinkit", "zepto", "bigbasket", "big basket", "instamart", "dmart", "d-mart", "grocery",
            "groceries", "reliance fresh", "more supermarket", "jiomart", "vegetables", "milk", "kirana", "supermarket", "fruits"),
        "Transport" to listOf("uber", "ola", "rapido", "metro", "auto", "cab", "taxi", "bus", "irctc", "train",
            "local train", "parking", "toll", "fastag", "namma yatri"),
        "Fuel" to listOf("petrol", "diesel", "fuel", "hpcl", "bpcl", "indian oil", "iocl", "shell", "cng"),
        "Shopping" to listOf("amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "croma", "decathlon", "shopping",
            "clothes", "shoes", "lenskart", "ikea", "tata cliq"),
        "Bills & Utilities" to listOf("electricity", "bescom", "msedcl", "tata power", "adani electricity", "water bill",
            "gas bill", "broadband", "wifi", "airtel", "jio", "vodafone", "vi recharge", "bsnl", "recharge", "mobile bill",
            "postpaid", "dth", "tata play", "act fibernet", "bill", "insurance", "lic premium"),
        "Rent" to listOf("rent", "landlord", "nobroker", "maintenance", "society"),
        "Entertainment" to listOf("netflix", "prime video", "hotstar", "spotify", "youtube premium", "bookmyshow", "pvr",
            "inox", "movie", "concert", "gaming", "steam", "playstation", "sonyliv", "jiocinema"),
        "Health" to listOf("pharmacy", "medical", "medicine", "apollo", "pharmeasy", "1mg", "netmeds", "hospital", "clinic",
            "doctor", "lab test", "practo", "gym", "cult.fit", "cultfit"),
        "Education" to listOf("course", "udemy", "coursera", "books", "tuition", "fees", "college", "school", "exam"),
        "Travel" to listOf("makemytrip", "goibibo", "cleartrip", "indigo", "air india", "vistara", "akasa", "spicejet",
            "flight", "hotel", "oyo", "airbnb", "booking.com", "ixigo", "redbus"),
        "Investments" to listOf("zerodha", "groww", "upstox", "sip", "mutual fund", "kuvera", "coin", "ppf", "nps",
            "smallcase", "angel one", "fd ", "fixed deposit"),
        "Transfers" to listOf("transfer", "neft", "imps", "sent to", "self transfer", "credit card payment", "cc payment"),
        "Personal Care" to listOf("salon", "haircut", "spa", "urban company", "parlour", "barber"),
    )
    private val COMPILED = KEYWORDS.mapValues { (_, ws) ->
        ws.map { w -> w to Regex("(?<![a-z0-9])" + Regex.escape(w) + "(?![a-z0-9])") }
    }

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
        val hay = " ${norm(merchant)} ${norm(text)} "
        var best = "Other"
        var bestLen = 0
        for ((cat, ws) in COMPILED) for ((w, rx) in ws) {
            if (w.length > bestLen && rx.containsMatchIn(hay)) { best = cat; bestLen = w.length }
        }
        return best
    }
}

/** Masks account/card numbers and drops balances/OTPs before anything is stored or sent anywhere. */
object Redact {
    private val LONG_NUM = Regex("(?<![\\d.,])(\\d{4,})(\\d{4})(?![\\d.,])")
    private val BALANCE = Regex("(?i)\\b(avl\\.?\\s*bal|available\\s+balance|avl\\.?\\s*lmt|available\\s+limit|bal)\\b[^.;\\n]*")
    private val OTP = Regex("(?i)\\b(otp|one[- ]time password|verification code)\\b[^.;\\n]*")

    fun apply(text: String?): String {
        if (text.isNullOrEmpty()) return text ?: ""
        var t = LONG_NUM.replace(text) { m -> "X".repeat(minOf(m.groupValues[1].length, 4)) + m.groupValues[2] }
        t = BALANCE.replace(t, "[balance hidden]")
        t = OTP.replace(t, "[code hidden]")
        return t
    }
}
