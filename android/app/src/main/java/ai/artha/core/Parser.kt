package ai.artha.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** Turns free text or bank / UPI SMS and notifications into structured transactions. */
object Parser {
    private const val I = "(?i)"
    private const val NUM = "([0-9][0-9,]*(?:\\.\\d{1,2})?)"
    val AMOUNT_RE = Regex("$I(?:rs\\.?|inr|₹)\\s*$NUM|$NUM\\s*(?:rs\\.?|inr|₹|rupees|/-)")
    private val BARE_AMOUNT_RE = Regex("(?<![\\w.])([0-9][0-9,]*(?:\\.\\d{1,2})?)(?![\\w.])")
    val DEBIT_WORDS = Regex("$I\\b(debited|spent|paid|sent|withdrawn|purchase|dr\\b|debit)")
    val CREDIT_WORDS = Regex("$I\\b(credited|received|refund|cashback|deposited|cr\\b)")
    private val SMS_HINT = Regex("$I\\b(a/c|acct|account|upi|vpa|ref|avl bal|card|xx\\d+|\\*\\d{3,})")

    private const val STOP = "(?=\\s+(?:on|via|ref|upi|using|from|for|txn|dated|avl|\\d{1,2}[-/])\\b|[.,;]|$)"
    private const val STOP_FROM = "(?=\\s+(?:on|via|ref|upi|using|for|txn|dated|avl|\\d{1,2}[-/])\\b|[.,;]|$)"
    private val MERCHANT_PATTERNS = listOf(
        Regex("$I\\b(?:to|towards)\\s+VPA\\s+([\\w.\\-]+@[\\w.\\-]+)"),
        Regex("$I\\binfo:?\\s*(?:upi/)?(?:\\w+/)?([A-Za-z][\\w .&'-]{2,40})"),
        Regex("$I\\bat\\s+([A-Za-z][\\w .&'@-]{1,40}?)$STOP"),
        Regex("$I\\bto\\s+([A-Za-z][\\w .&'@-]{1,40}?)$STOP"),
        Regex("$I\\bon\\s+([A-Za-z][\\w .&'@-]{1,40}?)$STOP"),
        Regex("$I;\\s*([A-Za-z][\\w .&'-]{2,40}?)\\s+credited"),
        Regex("$I\\b(?:from|by)\\s+([A-Za-z][\\w .&'@-]{1,40}?)$STOP_FROM"),
    )
    private fun fmt(p: String): DateTimeFormatter =
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH)
    private val DATE_PATTERNS = listOf(
        "d-M-yy" to Regex("\\b(\\d{1,2}-\\d{1,2}-\\d{2})\\b"),
        "d-M-yyyy" to Regex("\\b(\\d{1,2}-\\d{1,2}-\\d{4})\\b"),
        "d/M/yy" to Regex("\\b(\\d{1,2}/\\d{1,2}/\\d{2})\\b"),
        "d/M/yyyy" to Regex("\\b(\\d{1,2}/\\d{1,2}/\\d{4})\\b"),
        "d-MMM-yy" to Regex("\\b(\\d{1,2}-[A-Za-z]{3}-\\d{2})\\b"),
        "d-MMM-yyyy" to Regex("\\b(\\d{1,2}-[A-Za-z]{3}-\\d{4})\\b"),
        "dMMMyy" to Regex("\\b(\\d{1,2}[A-Za-z]{3}\\d{2})\\b"),
        "yyyy-MM-dd" to Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b"),
    ).map { (p, r) -> fmt(p) to r }

    /** Money mentioned but not a completed payment: OTPs, failures, reminders, requests. */
    private val NON_TXN = Regex(
        "$I\\b(otp|one[- ]time password|verification code|failed|declined|unsuccessful|could not be processed|" +
            "will be debited|to be debited|is due|due on|due date|payment due|min(?:imum)? amt|minimum amount due|" +
            "collect request|has requested|requested (?:rs|inr|money)|statement (?:for|generated)|pre-?approved|loan of)\\b"
    )
    private val SPLIT_RE = Regex(
        "$I\\b(?:split|shared?|divide[d]?)\\b(?:\\s+(?:it|this|bill))?" +
            "(?:\\s+(?:with|between|among)\\s+(?<people>[a-z ,&]+?))?(?:\\s+(?<ways>\\d+|two|three|four|five|six)\\s+ways?)?" +
            "(?=$|[.;]|\\s+(?:on|for|at|today|yesterday)\\b)"
    )
    private val WORD_NUM = mapOf("two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6)
    private val STOPWORDS = setOf("on", "for", "at", "to", "spent", "paid", "rs", "inr", "rupees", "the", "a", "my", "today", "yesterday")

    data class Parsed(
        val amount: Double, val kind: String, val merchant: String, val note: String, val date: LocalDate,
        val source: String, val confident: Boolean = true, val splitWith: List<String> = emptyList(), val splitWays: Int? = null,
    )

    private fun toDouble(s: String) = s.replace(",", "").toDouble()

    fun findAmount(text: String): Double? {
        AMOUNT_RE.find(text)?.let { m -> return toDouble(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
        for (m in BARE_AMOUNT_RE.findAll(text)) {
            val v = m.groupValues[1]
            if (v.replace(",", "").substringBefore(".").length <= 7) return toDouble(v)
        }
        return null
    }

    private fun findDate(text: String, today: LocalDate): LocalDate {
        for ((f, rx) in DATE_PATTERNS) {
            val m = rx.find(text) ?: continue
            try {
                val d = LocalDate.parse(m.groupValues[1], f)
                if (!d.isAfter(today)) return d
            } catch (_: Exception) {
            }
        }
        if (text.contains("yesterday", ignoreCase = true)) return today.minusDays(1)
        return today
    }

    private fun cleanMerchant(raw: String): String {
        var r = raw.trim(' ', '.', ',', '-', ';', ':')
        if ("@" in r) r = r.substringBefore("@").replace(".", " ").replace("_", " ")
        r = r.replace(Regex("\\s+"), " ")
        return titleCase(r).take(40)
    }

    fun titleCase(s: String): String = s.lowercase().split(" ").joinToString(" ") { w ->
        w.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
    }

    private val REJECT_START = Regex("(?i)(your|a/c|ac|acct|account|card).*")
    private val REJECT_ANY = Regex("(?i)\\b(bank|card|a/c|xx\\d+)\\b")

    private fun findMerchant(text: String, isSms: Boolean): String {
        if (isSms) {
            for (rx in MERCHANT_PATTERNS) {
                val m = rx.find(text) ?: continue
                val cand = cleanMerchant(m.groupValues[1])
                if (cand.isNotEmpty() && !REJECT_START.matches(cand) && !REJECT_ANY.containsMatchIn(cand)) return cand
            }
            return "Unknown"
        }
        val words = AMOUNT_RE.replace(text, " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
            .filter { !BARE_AMOUNT_RE.matches(it) && it.lowercase() !in STOPWORDS }
        return if (words.isEmpty()) "Unknown" else cleanMerchant(words.take(3).joinToString(" "))
    }

    fun isNonTransaction(text: String?): Boolean = NON_TXN.containsMatchIn(text ?: "")

    private fun findSplit(text: String): Triple<String, List<String>, Int?> {
        val m = SPLIT_RE.find(text) ?: return Triple(text, emptyList(), null)
        val people = m.groups["people"]?.value?.split(Regex(",|&|\\band\\b"))?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.lowercase() !in setOf("me", "i", "myself") }?.map { titleCase(it) } ?: emptyList()
        val w = m.groups["ways"]?.value
        val ways = w?.toIntOrNull() ?: WORD_NUM[w?.lowercase()]
        return Triple((text.substring(0, m.range.first) + " " + text.substring(m.range.last + 1)).trim(), people, ways)
    }

    /** Parse one line / one SMS. Returns null if no amount can be found or it isn't a real payment. */
    fun parse(input: String?, today: LocalDate): Parsed? {
        val text = (input ?: "").trim()
        if (text.isEmpty() || isNonTransaction(text)) return null
        val amount = findAmount(text) ?: return null
        if (amount <= 0) return null
        val isSms = SMS_HINT.containsMatchIn(text) || (DEBIT_WORDS.containsMatchIn(text) && text.length > 60)
        val kind = if (CREDIT_WORDS.containsMatchIn(text) && !DEBIT_WORDS.containsMatchIn(text)) "credit" else "debit"
        val (body, splitWith, splitWays) = if (isSms) Triple(text, emptyList(), null) else findSplit(text)
        val merchant = findMerchant(body, isSms)
        val explicit = AMOUNT_RE.containsMatchIn(text)
        return Parsed(amount, kind, merchant, text, findDate(text, today), if (isSms) "sms" else "text",
            merchant != "Unknown" && (explicit || !isSms), splitWith, splitWays)
    }

    /**
     * Stricter check for messages captured automatically in the background (SMS, notifications):
     * must name a currency amount AND say money moved, so personal messages with numbers are never logged.
     */
    fun looksLikePayment(text: String): Boolean =
        AMOUNT_RE.containsMatchIn(text) && (DEBIT_WORDS.containsMatchIn(text) || CREDIT_WORDS.containsMatchIn(text)) &&
            !isNonTransaction(text)
}
