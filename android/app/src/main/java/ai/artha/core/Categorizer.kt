package ai.artha.core

/** Applies your own rules and the merchants you corrected. Everything else is categorized by the on-device AI. */
object Categorizer {
    val CATEGORIES = listOf(
        "Food & Dining", "Groceries", "Transport", "Fuel", "Shopping", "Bills & Utilities", "Rent", "Entertainment",
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
