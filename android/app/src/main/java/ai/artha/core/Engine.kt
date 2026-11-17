package ai.artha.core

import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Everything PAISAve knows how to do, running entirely on the phone.
 *
 * A payment flows: captured (SMS / notification / share / voice / receipt / typed) -> redacted -> categorized
 * -> checked for duplicates and subscriptions -> split -> flagged for a question only when unsure -> stored,
 * which updates the daily budget and Savings Pool.
 */
class Engine(val db: Db, val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) }) {

    init { migrate() }

    // ---------------- schema ----------------
    private fun migrate() {
        listOf(
            """CREATE TABLE IF NOT EXISTS transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, amount REAL NOT NULL,
                kind TEXT NOT NULL DEFAULT 'debit', merchant TEXT NOT NULL DEFAULT 'Unknown',
                category TEXT NOT NULL DEFAULT 'Other', note TEXT, source TEXT NOT NULL DEFAULT 'manual',
                raw_hash TEXT UNIQUE, created_at TEXT NOT NULL DEFAULT (datetime('now')),
                my_share REAL, needs_review INTEGER NOT NULL DEFAULT 0, review_reason TEXT,
                duplicate_of INTEGER, is_recurring INTEGER NOT NULL DEFAULT 0)""",
            "CREATE INDEX IF NOT EXISTS idx_tx_date ON transactions(date)",
            "CREATE INDEX IF NOT EXISTS idx_tx_review ON transactions(needs_review)",
            "CREATE TABLE IF NOT EXISTS merchant_rules (merchant TEXT PRIMARY KEY, category TEXT NOT NULL)",
            """CREATE TABLE IF NOT EXISTS rules (id INTEGER PRIMARY KEY AUTOINCREMENT, pattern TEXT NOT NULL,
                match_type TEXT NOT NULL DEFAULT 'contains', category TEXT NOT NULL, min_amount REAL, max_amount REAL,
                priority INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT (datetime('now')))""",
            "CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS allowance_history (start_date TEXT PRIMARY KEY, amount REAL NOT NULL)",
            """CREATE TABLE IF NOT EXISTS subscriptions (id INTEGER PRIMARY KEY AUTOINCREMENT,
                merchant_key TEXT NOT NULL UNIQUE, name TEXT NOT NULL, amount REAL NOT NULL,
                period TEXT NOT NULL DEFAULT 'monthly', category TEXT, last_date TEXT, next_date TEXT,
                status TEXT NOT NULL DEFAULT 'active', source TEXT NOT NULL DEFAULT 'detected')""",
            """CREATE TABLE IF NOT EXISTS detected (id INTEGER PRIMARY KEY AUTOINCREMENT,
                source TEXT NOT NULL, body TEXT NOT NULL, header TEXT, sender TEXT, received_at TEXT NOT NULL,
                raw_hash TEXT UNIQUE, status TEXT NOT NULL DEFAULT 'pending',
                type TEXT, amount REAL, party TEXT, category TEXT, date TEXT, confident INTEGER, reason TEXT,
                split_with TEXT, split_ways INTEGER, tx_id INTEGER, created_at TEXT NOT NULL DEFAULT (datetime('now')))""",
            "CREATE INDEX IF NOT EXISTS idx_detected_status ON detected(status)",
            """CREATE TABLE IF NOT EXISTS splits (id INTEGER PRIMARY KEY AUTOINCREMENT,
                tx_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE, person TEXT NOT NULL,
                amount REAL NOT NULL, settled INTEGER NOT NULL DEFAULT 0, settled_at TEXT)""",
        ).forEach { db.exec(it) }
        // Our own clean-up version lives in its own table. Never use PRAGMA user_version: Android's database
        // helper owns it, and changing it makes Android refuse to open the database.
        db.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        // Payees you've named, remembered by their UPI ID (shop QRs and phone numbers don't say who they are).
        db.exec("CREATE TABLE IF NOT EXISTS payees (upi_id TEXT PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL)")
        for (col in listOf("upi_id TEXT", "gkey TEXT")) try { db.exec("ALTER TABLE detected ADD COLUMN $col") } catch (_: Exception) {}
        val version = (db.scalar("SELECT value FROM meta WHERE key='cleanup_version'") as? String)?.toIntOrNull() ?: 0
        if (version < 3) {
            // Earlier versions sometimes named the bank, the account or a code as the payee: re-read those (not yet logged).
            db.exec("UPDATE detected SET status='pending' WHERE status='analyzed' AND (lower(party) LIKE '%bank%' OR lower(party) LIKE '%co-op%' " +
                "OR lower(party) LIKE '%account%' OR lower(party) LIKE '%slice%' OR lower(party) LIKE '%cc payment%' OR length(party) < 4)")
            // Received money is off by default now.
            db.exec("UPDATE detected SET status='skipped', reason='Money received isn''t tracked (Settings)' WHERE status IN ('analyzed','pending') AND type='credit'")
            db.exec("INSERT INTO meta(key, value) VALUES('cleanup_version', '3') ON CONFLICT(key) DO UPDATE SET value=excluded.value")
        }
        if (version < 4) {
            // Replies used to go only into the note ("Chai (Bhqr)"). Make the reply the name and re-pick the category.
            for (t in db.query("SELECT id, merchant, note, category FROM transactions WHERE note LIKE '% (%)'")) {
                if (!needsName(t.s("merchant"))) continue
                val reply = t.s("note")!!.substringBeforeLast(" (").trim().takeIf { it.isNotEmpty() && it.length <= 60 } ?: continue
                val cat = Categorizer.hint(reply) ?: t.s("category")
                db.exec("UPDATE transactions SET merchant=?, category=?, note=? WHERE id=?", titleCase(reply), cat, reply, t.l("id"))
            }
            db.exec("INSERT INTO meta(key, value) VALUES('cleanup_version', '4') ON CONFLICT(key) DO UPDATE SET value=excluded.value")
        }
        if (version < 5) {
            // Names you gave in a payment's details weren't remembered for its UPI ID. Remember them now.
            try {
                for (r in db.query("SELECT d.upi_id AS upi, t.merchant AS merchant, t.category AS category FROM detected d JOIN transactions t ON t.id=d.tx_id " +
                    "WHERE d.upi_id IS NOT NULL AND d.upi_id NOT IN (SELECT upi_id FROM payees) ORDER BY t.id")) {
                    val name = r.s("merchant") ?: continue
                    if (!needsName(name)) db.exec("INSERT INTO payees(upi_id, name, category) VALUES(?,?,?) ON CONFLICT(upi_id) DO UPDATE SET name=excluded.name, category=excluded.category",
                        r.s("upi"), name.trim().take(60), r.s("category") ?: "Other")
                }
            } catch (_: Exception) {}
            db.exec("INSERT INTO meta(key, value) VALUES('cleanup_version', '5') ON CONFLICT(key) DO UPDATE SET value=excluded.value")
        }
    }

    // ---------------- settings & budget ----------------
    companion object {
        val DEFAULTS: Map<String, Any?> = mapOf(
            "daily_allowance" to 500.0,
            "excluded_categories" to listOf("Rent", "Bills & Utilities", "Investments", "Transfers"),
            "custom_categories" to emptyList<String>(),
            "savings_goal_pct" to 20L,
            "budget_start" to null,
            "auto_capture_sms" to true,
            "notify_logged" to true,
            "app_lock" to false,
            "hide_in_recents" to true,
            "auto_log_confident" to false,
            "auto_log_history" to true,
            "monthly_budget" to 15000.0,
            "theme" to "system",
            "category_limits" to emptyMap<String, Double>(),
            "user_name" to "",
            "reminder_on" to true,
            "reminder_hour" to 21L,
            "track_received" to false,
        )
        val FIXED = setOf("Rent", "Bills & Utilities", "Investments", "Transfers", "Education")
        val REVIEW_QUESTIONS = mapOf(
            "duplicate" to "Looks like the same payment twice. Were you charged twice?",
            "cancelled_subscription" to "You marked this subscription as cancelled, but it charged again.",
            "unknown_merchant" to "Couldn't tell who this payment went to. Who was it?",
            "uncategorized" to "What was this for?",
            "low_confidence" to "Please double-check the amount and merchant.",
        )
        const val SPEND = "COALESCE(my_share, amount)"
    }

    fun settings(): MutableMap<String, Any?> {
        val s = DEFAULTS.toMutableMap()
        for (r in db.query("SELECT key, value FROM settings")) {
            try { s[r.s("key")!!] = fromJson(Json.parseToJsonElement(r.s("value")!!)) } catch (_: Exception) {}
        }
        return s
    }

    fun setSettings(values: Map<String, Any?>): MutableMap<String, Any?> {
        for ((k, v) in values) {
            if (k !in DEFAULTS) continue
            db.exec("INSERT INTO settings(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                k, toJson(v).toString())
            if (k == "daily_allowance") {
                db.exec("INSERT INTO allowance_history(start_date, amount) VALUES(?,?) ON CONFLICT(start_date) DO UPDATE SET amount=excluded.amount",
                    today().toString(), (v as Number).toDouble())
            }
        }
        return settings()
    }

    @Suppress("UNCHECKED_CAST")
    fun allCategories(): List<String> {
        val custom = (settings()["custom_categories"] as? List<String> ?: emptyList()).filter { it !in Categorizer.CATEGORIES }
        return Categorizer.CATEGORIES.dropLast(1) + custom + "Other"
    }

    fun ensureStarted(): LocalDate {
        val s = settings()
        (s["budget_start"] as? String)?.let { return LocalDate.parse(it) }
        val first = db.scalar("SELECT MIN(date) FROM transactions") as? String
        val start = if (first != null) minOf(LocalDate.parse(first), today()) else today()
        setSettings(mapOf("budget_start" to start.toString()))
        if (db.one("SELECT 1 AS x FROM allowance_history") == null) {
            db.exec("INSERT INTO allowance_history(start_date, amount) VALUES(?,?)", start.toString(), (s["daily_allowance"] as Number).toDouble())
        }
        return start
    }

    @Suppress("UNCHECKED_CAST")
    private fun excluded(): List<String> = settings()["excluded_categories"] as? List<String> ?: emptyList()

    private fun inClause(n: Int) = if (n == 0) "''" else List(n) { "?" }.joinToString(",")

    fun budgetStatus(): Map<String, Any?> {
        val s = settings()
        val start = ensureStarted()
        val t = today()
        val history = db.query("SELECT start_date, amount FROM allowance_history ORDER BY start_date").map { it.s("start_date")!! to it.d("amount")!! }
        val defaultAllow = (s["daily_allowance"] as Number).toDouble()
        fun allowanceOn(day: String): Double {
            var a = history.firstOrNull()?.second ?: defaultAllow
            for ((d, v) in history) if (d <= day) a = v else break
            return a
        }
        val ex = excluded()
        val spend = db.query("SELECT date, SUM($SPEND) AS t FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ? " +
            "AND category NOT IN (${inClause(ex.size)}) GROUP BY date", start.toString(), t.toString(), *ex.toTypedArray())
            .associate { it.s("date")!! to (it.d("t") ?: 0.0) }
        var pool = 0.0; var daysOver = 0; var streak = 0
        var d = start
        while (d.isBefore(t)) {
            val diff = allowanceOn(d.toString()) - (spend[d.toString()] ?: 0.0)
            pool += diff
            if (diff < 0) { daysOver++; streak = 0 } else streak++
            d = d.plusDays(1)
        }
        val allowToday = allowanceOn(t.toString())
        val spentToday = spend[t.toString()] ?: 0.0
        return mapOf(
            "daily_allowance" to allowToday, "spent_today" to round2(spentToday), "left_today" to round2(allowToday - spentToday),
            "savings_pool" to round2(pool), "pool_if_day_ends_now" to round2(pool + allowToday - spentToday),
            "since" to start.toString(), "days_over_budget" to daysOver, "under_budget_streak" to streak, "excluded_categories" to ex,
        )
    }

    // ---------------- daily budget ----------------
    /** The daily budget that applied on each day (changes count from the day you made them). */
    private fun allowanceLookup(): (String) -> Double {
        val history = db.query("SELECT start_date, amount FROM allowance_history ORDER BY start_date").map { it.s("start_date")!! to it.d("amount")!! }
        val def = (settings()["daily_allowance"] as Number).toDouble()
        return { day -> var a = history.firstOrNull()?.second ?: def; for ((d, v) in history) if (d <= day) a = v else break; a }
    }

    /**
     * One day's log: its budget, what you spent (excluded categories don't count) and the payments,
     * plus a strip of the last [span] days so you can jump between them.
     */
    fun daily(date: LocalDate, span: Int = 60): Map<String, Any?> {
        ensureStarted()
        val t = today(); val day = clampDate(date)
        val allowance = allowanceLookup(); val ex = excluded()
        val from = minOf(t.minusDays(span.toLong() - 1), day)
        val spend = db.query("SELECT date, SUM($SPEND) AS t FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ? " +
            "AND category NOT IN (${inClause(ex.size)}) GROUP BY date", from.toString(), t.toString(), *ex.toTypedArray())
            .associate { it.s("date")!! to round2(it.d("t") ?: 0.0) }
        val zero = try { db.query("SELECT date FROM zero_days WHERE date >= ?", from.toString()).mapNotNull { it.s("date") }.toSet() } catch (_: Exception) { emptySet() }
        val strip = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(t) }.map { d ->
            val k = d.toString(); val a = allowance(k); val sp = spend[k] ?: 0.0
            mapOf("date" to k, "spent" to sp, "allowance" to a,
                "state" to when { sp > a -> "over"; sp > 0 -> "under"; k in zero -> "zero"; d == t -> "today"; else -> "empty" })
        }.toList()
        val a = allowance(day.toString()); val sp = spend[day.toString()] ?: 0.0
        val txs = db.query("SELECT * FROM transactions WHERE date=? ORDER BY id DESC", day.toString())
        val week = strip.takeLast(7)
        return mapOf("date" to day.toString(), "allowance" to a, "spent" to sp, "left" to round2(a - sp),
            "counted_categories_excluded" to ex, "transactions" to txs, "days" to strip,
            "week_spent" to round2(week.sumOf { it["spent"] as Double }), "week_allowance" to round2(week.sumOf { it["allowance"] as Double }),
            "suggested" to round2((settings()["monthly_budget"] as? Number)?.toDouble()?.div(t.lengthOfMonth()) ?: a))
    }

    // ---------------- ledger ----------------
    private fun learned(): Map<String, String> = db.query("SELECT merchant, category FROM merchant_rules").associate { it.s("merchant")!! to it.s("category")!! }

    fun customRules(): List<Row> = db.query("SELECT * FROM rules ORDER BY priority DESC, id")
    private fun rulesTyped() = customRules().map {
        Categorizer.Rule(it.s("pattern")!!, it.s("match_type")!!, it.s("category")!!, it.d("min_amount"), it.d("max_amount"), it.l("priority") ?: 0)
    }

    fun row(id: Long): Map<String, Any?>? {
        val r = db.one("SELECT * FROM transactions WHERE id=?", id)?.toMutableMap() ?: return null
        r["splits"] = db.query("SELECT * FROM splits WHERE tx_id=? ORDER BY id", id)
        r["question"] = if (r.b("needs_review")) REVIEW_QUESTIONS[r.s("review_reason")] else null
        return r
    }

    private fun findDuplicate(d: LocalDate, amount: Double, merchant: String): Long? {
        val key = Categorizer.merchantKey(merchant)
        if (key.isEmpty() || key == "unknown") return null
        for (x in db.query("SELECT id, merchant FROM transactions WHERE kind='debit' AND amount=? AND date BETWEEN ? AND ? ORDER BY id DESC",
            amount, d.minusDays(1).toString(), d.plusDays(1).toString())) {
            val other = Categorizer.merchantKey(x.s("merchant"))
            if (other.isNotEmpty() && (key == other || key in other || other in key)) return x.l("id")
        }
        return null
    }

    private val AUTO_SOURCES = setOf("sms", "notification")

    /** The same payment usually arrives twice (bank SMS + UPI app notification): merge silently, don't ask. */
    private fun sameAutoCapture(amount: Double, kind: String, source: String): Boolean =
        source in AUTO_SOURCES && db.one(
            "SELECT id FROM transactions WHERE amount=? AND kind=? AND source IN ('sms','notification') AND source != ? " +
                "AND created_at >= datetime('now', '-15 minutes')", amount, kind, source) != null

    fun record(
        d: LocalDate, amount: Double, kind: String = "debit", merchant: String = "Unknown", note: String = "",
        source: String = "manual", category: String? = null, raw: String? = null, splitWith: List<String> = emptyList(),
        splitWays: Int? = null, confident: Boolean = true,
    ): Map<String, Any?>? = db.transaction {
        val cleanNote = Redact.apply(note.ifBlank { merchant })
        val amt = round2(amount)
        val m = merchant.trim().take(60).ifBlank { "Unknown" }
        if (sameAutoCapture(amt, kind, source)) return@transaction null
        val cat = category ?: Categorizer.categorize(cleanNote, m, learned(), rulesTyped(), amt)
            .let { if (it == "Other") Categorizer.hint("$m $cleanNote", allCategories()) ?: it else it }
        var reason: String? = null
        var dup: Long? = null
        if (kind == "debit") {
            // Bank SMS carry unique references, so repeats there are real payments; only typed entries can double up.
            dup = if (source == "text") findDuplicate(d, amt, m) else null
            val sub = db.one("SELECT status FROM subscriptions WHERE merchant_key=?", Categorizer.merchantKey(m))
            reason = when {
                dup != null -> "duplicate"
                sub?.s("status") == "cancelled" -> "cancelled_subscription"
                m == "Unknown" -> "unknown_merchant"
                cat == "Other" -> "uncategorized"
                !confident -> "low_confidence"
                else -> null
            }
        }
        val hash = raw?.trim()?.let { sha256(it) }
        val id = db.insert(
            "INSERT INTO transactions(date, amount, kind, merchant, category, note, source, raw_hash, needs_review, review_reason, duplicate_of) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            d.toString(), amt, kind, m, cat, cleanNote, source, hash, if (reason != null) 1L else 0L, reason, dup)
        if (id < 0) return@transaction null // this exact message was already recorded
        if (splitWith.isNotEmpty() || splitWays != null) applySplit(id, splitWith, splitWays)
        if (kind == "debit") detectRecurring(m) else autoSettleFromCredit(m, amt)
        ensureStarted()
        row(id)
    }.also { if (it != null) onRecorded?.invoke() }

    /** Called after a payment is recorded from anywhere (SMS in the background too). */
    var onRecorded: (() -> Unit)? = null

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun setCategory(id: Long, category: String, learn: Boolean = true) {
        val tx = db.one("SELECT merchant FROM transactions WHERE id=?", id)
        db.exec("UPDATE transactions SET category=? WHERE id=?", category, id)
        val key = Categorizer.merchantKey(tx?.s("merchant"))
        if (learn && key.isNotEmpty() && key != "unknown") {
            db.exec("INSERT INTO merchant_rules(merchant, category) VALUES(?,?) ON CONFLICT(merchant) DO UPDATE SET category=excluded.category", key, category)
            db.exec("UPDATE transactions SET category=?, needs_review=0, review_reason=NULL WHERE lower(merchant)=? AND needs_review=1 AND review_reason='uncategorized'", category, key)
        }
    }

    fun resolveReview(id: Long, action: String, category: String?, merchant: String?): Map<String, Any?>? {
        val tx = db.one("SELECT * FROM transactions WHERE id=?", id) ?: return null
        if (action == "delete") { db.exec("DELETE FROM transactions WHERE id=?", id); return mapOf("deleted" to id) }
        merchant?.takeIf { it.isNotBlank() }?.let { db.exec("UPDATE transactions SET merchant=? WHERE id=?", it.trim().take(60), id) }
        category?.let { setCategory(id, it) }
        db.exec("UPDATE transactions SET needs_review=0, review_reason=NULL WHERE id=?", id)
        if (action == "reactivate_subscription") db.exec("UPDATE subscriptions SET status='active' WHERE merchant_key=?", Categorizer.merchantKey(tx.s("merchant")))
        return row(id)
    }

    // ---------------- splits ----------------
    fun applySplit(txId: Long, people: List<String>, ways: Int?, amounts: Map<String, Double>? = null): Map<String, Any?>? {
        val total = db.one("SELECT amount FROM transactions WHERE id=?", txId)?.d("amount") ?: throw ApiError(404, "Transaction not found")
        db.exec("DELETE FROM splits WHERE tx_id=? AND settled=0", txId)
        val others: Map<String, Double> = if (amounts != null) {
            amounts.filter { it.key.isNotBlank() && it.value > 0 }.map { titleCase(it.key.trim()) to round2(it.value) }.toMap()
        } else {
            val ps = people.map { it.trim() }.filter { it.isNotEmpty() }.map { titleCase(it) }.toMutableList()
            val n = max(ways ?: 0, ps.size + 1)
            while (ps.size < n - 1) ps.add("Friend ${ps.size + 1}")
            val share = round2(total / n)
            ps.associateWith { share }
        }
        if (others.values.sum() > total + 0.01) throw ApiError(400, "Split amounts are more than the payment")
        for ((p, a) in others) db.exec("INSERT INTO splits(tx_id, person, amount) VALUES (?,?,?)", txId, p, a)
        val settled = db.num("SELECT COALESCE(SUM(amount),0) FROM splits WHERE tx_id=? AND settled=1", txId)
        val myShare = if (others.isNotEmpty() || settled > 0) round2(total - others.values.sum() - settled) else null
        db.exec("UPDATE transactions SET my_share=? WHERE id=?", myShare, txId)
        return row(txId)
    }

    fun removeSplit(txId: Long) {
        db.exec("DELETE FROM splits WHERE tx_id=?", txId)
        db.exec("UPDATE transactions SET my_share=NULL WHERE id=?", txId)
    }

    fun balances(): List<Row> = db.query("SELECT person, ROUND(SUM(amount),2) AS owed, COUNT(*) AS items FROM splits WHERE settled=0 GROUP BY lower(person) ORDER BY owed DESC")

    fun settle(person: String, amount: Double?): Double {
        var left = amount ?: Double.MAX_VALUE
        var done = 0.0
        for (s in db.query("SELECT s.id, s.amount FROM splits s JOIN transactions t ON t.id=s.tx_id WHERE s.settled=0 AND lower(s.person)=lower(?) ORDER BY t.date, s.id", person)) {
            val a = s.d("amount")!!
            if (a > left + 0.01) break
            db.exec("UPDATE splits SET settled=1, settled_at=? WHERE id=?", today().toString(), s.l("id"))
            left -= a; done += a
        }
        return round2(done)
    }

    private fun autoSettleFromCredit(merchant: String, amount: Double) {
        val m = Categorizer.merchantKey(merchant)
        for (b in balances()) {
            val name = b.s("person")!!.lowercase()
            if (!name.startsWith("friend ") && Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(m)) { settle(b.s("person")!!, amount); return }
        }
    }

    // ---------------- recurring & subscriptions ----------------
    private val PERIODS = linkedMapOf("weekly" to Triple(6, 8, 7), "monthly" to Triple(26, 35, 30),
        "quarterly" to Triple(85, 97, 91), "yearly" to Triple(350, 380, 365))
    private val KNOWN_SUBS = setOf("netflix", "spotify", "prime video", "amazon prime", "hotstar", "disney", "youtube premium",
        "youtube", "sonyliv", "jiocinema", "zee5", "apple", "icloud", "google one", "google storage", "chatgpt", "openai",
        "claude", "anthropic", "microsoft 365", "office 365", "adobe", "canva", "notion", "linkedin premium", "cult.fit",
        "cultfit", "audible", "kindle unlimited", "swiggy one", "zomato gold", "tata play", "airtel xstream")
    private val MONTHLY_FACTOR = mapOf("weekly" to 52.0 / 12, "monthly" to 1.0, "quarterly" to 1.0 / 3, "yearly" to 1.0 / 12)

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); val n = s.size
        return if (n == 0) 0.0 else if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2 }

    private fun periodFor(gaps: List<Long>): String? {
        if (gaps.isEmpty()) return null
        val g = median(gaps.map { it.toDouble() })
        for ((name, p) in PERIODS) {
            val (lo, hi, _) = p
            if (g in lo.toDouble()..hi.toDouble() && gaps.all { it >= lo * 0.75 && it <= hi * 1.25 }) return name
        }
        return null
    }

    private fun nextDate(last: LocalDate, period: String): LocalDate =
        if (period == "monthly") last.plusMonths(1) else last.plusDays(PERIODS[period]!!.third.toLong())

    fun detectRecurring(merchant: String): Row? {
        val key = Categorizer.merchantKey(merchant)
        if (key.isEmpty() || key == "unknown") return null
        val rows = db.query("SELECT id, date, amount, category FROM transactions WHERE kind='debit' AND lower(merchant)=? ORDER BY date", key)
        if (rows.isEmpty()) return null
        val dates = rows.map { LocalDate.parse(it.s("date")) }
        val amounts = rows.map { it.d("amount")!! }
        val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.filter { it > 0 }
        val p0 = periodFor(gaps)
        val period = if (rows.size >= (if (p0 == "weekly") 3 else 2)) p0 else null
        val recent = amounts.takeLast(4)
        val typical = median(recent)
        val similar = recent.all { abs(it - typical) <= max(0.15 * typical, 5.0) }
        val known = KNOWN_SUBS.any { it in key }
        if (!((period != null && similar) || known)) return null
        val existing = db.one("SELECT * FROM subscriptions WHERE merchant_key=?", key)
        if (existing?.s("status") == "ignored") return null
        val per = period ?: existing?.s("period") ?: "monthly"
        val status = if (period != null && similar && rows.size >= 2) "active" else "suggested"
        val last = dates.last()
        val lastRow = rows.last()
        if (existing != null) {
            val newStatus = if (existing.s("status") in setOf("active", "cancelled")) existing.s("status") else status
            db.exec("UPDATE subscriptions SET amount=?, period=?, last_date=?, next_date=?, status=?, category=? WHERE id=?",
                lastRow.d("amount"), per, last.toString(), nextDate(last, per).toString(), newStatus, lastRow.s("category"), existing.l("id"))
        } else {
            db.exec("INSERT INTO subscriptions(merchant_key, name, amount, period, category, last_date, next_date, status, source) VALUES (?,?,?,?,?,?,?,?, 'detected')",
                key, merchant, lastRow.d("amount"), per, lastRow.s("category"), last.toString(), nextDate(last, per).toString(), status)
        }
        if (status == "active" || existing?.s("status") == "active") db.exec("UPDATE transactions SET is_recurring=1 WHERE kind='debit' AND lower(merchant)=?", key)
        return db.one("SELECT * FROM subscriptions WHERE merchant_key=?", key)
    }

    fun scanRecurring() { db.query("SELECT DISTINCT merchant FROM transactions WHERE kind='debit'").forEach { detectRecurring(it.s("merchant")!!) } }

    fun subscriptions(): Map<String, Any?> {
        val t = today()
        val subs = db.query("SELECT * FROM subscriptions WHERE status != 'ignored' ORDER BY status='cancelled', next_date").map { r ->
            r.toMutableMap().apply {
                this["monthly_cost"] = round2(r.d("amount")!! * (MONTHLY_FACTOR[r.s("period")] ?: 1.0))
                this["days_until"] = r.s("next_date")?.let { ChronoUnit.DAYS.between(t, LocalDate.parse(it)) }
                this["is_subscription"] = r.s("category") !in FIXED
            }
        }
        val active = subs.filter { it["status"] == "active" }
        val real = active.filter { it["is_subscription"] == true }
        val monthly = real.sumOf { it["monthly_cost"] as Double }
        return mapOf("subscriptions" to subs, "monthly_total" to round2(monthly), "yearly_total" to round2(monthly * 12),
            "fixed_monthly_total" to round2(active.filter { it["is_subscription"] != true }.sumOf { it["monthly_cost"] as Double }),
            "upcoming" to active.filter { val d = it["days_until"] as Long?; d != null && d in 0..7 })
    }

    // ---------------- insights ----------------
    fun prevMonth(month: String, n: Int = 1): String {
        val (y, m) = month.split("-").map { it.toInt() }
        val idx = y * 12 + (m - 1) - n
        return "%d-%02d".format(idx / 12, idx % 12 + 1)
    }

    private fun bounds(month: String): Pair<String, String> {
        val start = LocalDate.parse("$month-01")
        return start.toString() to start.plusMonths(1).minusDays(1).toString()
    }

    fun monthStats(month: String, untilDay: Int? = null): Map<String, Any?> {
        var (lo, hi) = bounds(month)
        if (untilDay != null) hi = minOf(hi, "%s-%02d".format(month, untilDay))
        val byCat = db.query("SELECT category, SUM($SPEND) AS t FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ? GROUP BY category", lo, hi)
            .associate { it.s("category")!! to round2(it.d("t") ?: 0.0) }
        val received = db.num("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE kind='credit' AND date BETWEEN ? AND ?", lo, hi)
        val top = db.query("SELECT merchant, SUM($SPEND) AS t, COUNT(*) AS c FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ? GROUP BY lower(merchant) ORDER BY t DESC LIMIT 5", lo, hi)
            .map { mapOf("merchant" to it.s("merchant"), "total" to round2(it.d("t")!!), "count" to it.l("c")) }
        val weekday = db.query("SELECT strftime('%w', date) AS w, SUM($SPEND) AS t FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ? GROUP BY 1", lo, hi)
            .associate { it.s("w")!! to round2(it.d("t")!!) }
        return mapOf("month" to month, "spent" to round2(byCat.values.sum()), "received" to round2(received), "by_category" to byCat,
            "transactions" to db.count("SELECT COUNT(*) FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ?", lo, hi),
            "top_merchants" to top, "by_weekday" to weekday,
            "biggest" to db.one("SELECT merchant, $SPEND AS amount, date FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ? ORDER BY 2 DESC LIMIT 1", lo, hi)
                ?.let { mapOf("merchant" to it.s("merchant"), "amount" to round2(it.d("amount") ?: 0.0), "date" to it.s("date")) },
            "most_visited" to db.query("SELECT merchant, COUNT(*) AS c, SUM($SPEND) AS t FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ? " +
                "GROUP BY lower(merchant) ORDER BY c DESC, t DESC LIMIT 10", lo, hi).firstOrNull { !needsName(it.s("merchant")) }
                ?.let { mapOf("merchant" to it.s("merchant"), "count" to it.l("c"), "total" to round2(it.d("t") ?: 0.0)) },
            "active_days" to db.count("SELECT COUNT(DISTINCT date) FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ?", lo, hi))
    }

    private fun rupees(x: Double): String = "₹" + java.text.NumberFormat.getIntegerInstance(java.util.Locale("en", "IN")).format(x.roundToLong())

    @Suppress("UNCHECKED_CAST")
    fun ruleInsights(month: String): List<String> {
        val t = today()
        val inProgress = month == t.toString().substring(0, 7)
        val cur = monthStats(month)
        val prev = monthStats(prevMonth(month), if (inProgress) t.dayOfMonth else null)
        val period = if (inProgress) "the same ${t.dayOfMonth} days last month" else "last month"
        val curSpent = cur["spent"] as Double
        if (curSpent == 0.0) return listOf("No spending recorded this month yet.")
        val out = mutableListOf<String>()
        val prevSpent = prev["spent"] as Double
        if (prevSpent > 0) {
            val ch = (curSpent - prevSpent) / prevSpent * 100
            out += "You spent ${rupees(curSpent)}, ${abs(ch).roundToLong()}% ${if (ch > 0) "more" else "less"} than $period (${rupees(prevSpent)})."
        } else out += "You spent ${rupees(curSpent)} across ${cur["transactions"]} payments."
        val cc = cur["by_category"] as Map<String, Double>
        val pc = prev["by_category"] as Map<String, Double>
        for ((c, v) in cc.entries.sortedByDescending { it.value }) {
            val p = pc[c] ?: 0.0
            if (p > 0 && v > p * 1.3 && v - p > 500) out += "$c is up ${rupees(v - p)} compared with $period."
        }
        cc.maxByOrNull { it.value }?.let { out += "Biggest category: ${it.key} at ${rupees(it.value)} (${(it.value / curSpent * 100).roundToLong()}% of spending)." }
        (cur["top_merchants"] as List<Map<String, Any?>>).firstOrNull { !needsName(it["merchant"] as? String) }?.let {
            val c = it["count"] as Long
            out += "You paid ${it["merchant"]} $c time${if (c > 1) "s" else ""}, ${rupees(it["total"] as Double)} in total."
        }
        (cur["by_weekday"] as Map<String, Double>).maxByOrNull { it.value }?.let {
            val names = listOf("Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays")
            out += "You spend the most on ${names[it.key.toInt()]}."
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    fun recommendations(month: String): Map<String, Any?> {
        var hist = (1..3).map { monthStats(prevMonth(month, it)) }.filter { (it["spent"] as Double) > 0 }
        if (hist.isEmpty()) hist = listOf(monthStats(month))
        val s = settings()
        val cats = hist.flatMap { (it["by_category"] as Map<String, Double>).keys }.toSortedSet()
        val perCat = cats.mapNotNull { c ->
            val typical = median(hist.map { (it["by_category"] as Map<String, Double>)[c] ?: 0.0 })
            if (typical <= 0) null else {
                val cut = if (c in FIXED) 1.0 else 0.9
                val sug = ((typical * cut / 50).roundToLong() * 50).let { if (it == 0L) 50 else it }
                mapOf("category" to c, "typical" to typical.roundToLong(), "suggested" to sug, "flexible" to (c !in FIXED))
            }
        }
        val income = median(hist.map { it["received"] as Double })
        val fixed = perCat.filter { it["flexible"] == false }.sumOf { (it["typical"] as Long).toDouble() }
        val ex = excluded().toSet()
        val flexTypical = perCat.filter { it["category"] !in ex }.sumOf { (it["typical"] as Long).toDouble() }
        val goal = ((s["savings_goal_pct"] as Number).toDouble()) / 100
        val (basis, daily0) = if (income > 0) "income" to max(0.0, (income * (1 - goal) - fixed) / 30) else "spending" to flexTypical * 0.9 / 30
        val daily = max(50L, (daily0 / 10).roundToLong() * 10)
        val tips = mutableListOf<String>()
        val subs = subscriptions()
        if ((subs["monthly_total"] as Double) > 0) tips += "Subscriptions cost ${rupees(subs["monthly_total"] as Double)}/month (${rupees(subs["yearly_total"] as Double)}/year). Cancel any you haven't used in the last month."
        perCat.sortedByDescending { it["typical"] as Long }.take(2).filter { it["flexible"] == true }.forEach {
            tips += "Keeping ${it["category"]} to ${rupees((it["suggested"] as Long).toDouble())} would save about ${rupees(((it["typical"] as Long) - (it["suggested"] as Long)).toDouble())} a month."
        }
        return mapOf("based_on" to hist.map { it["month"] }, "basis" to basis, "monthly_income" to income.roundToLong(),
            "suggested_daily_allowance" to daily, "current_daily_allowance" to s["daily_allowance"], "categories" to perCat, "tips" to tips)
    }

    fun merchantHistory(name: String): Map<String, Any?> {
        val key = Categorizer.merchantKey(name)
        val rows = db.query("SELECT id, date, amount, my_share, kind, category FROM transactions WHERE lower(merchant)=? ORDER BY date DESC", key)
        val debits = rows.filter { it.s("kind") == "debit" }
        val byMonth = mutableMapOf<String, Double>()
        for (r in debits) byMonth.merge(r.s("date")!!.take(7), r.d("my_share") ?: r.d("amount")!!, Double::plus)
        val cur = today().toString().take(7)
        val months = (11 downTo 0).map { prevMonth(cur, it) }
        val total = byMonth.values.sum()
        return mapOf("merchant" to name, "total" to round2(total), "count" to debits.size,
            "average" to if (debits.isEmpty()) 0.0 else round2(total / debits.size),
            "first" to debits.lastOrNull()?.s("date"), "last" to debits.firstOrNull()?.s("date"),
            "monthly" to months.map { mapOf("month" to it, "total" to round2(byMonth[it] ?: 0.0)) },
            "subscription" to db.one("SELECT * FROM subscriptions WHERE merchant_key=?", key), "transactions" to rows.take(50))
    }

    // ---------------- search / questions ----------------
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    fun localPlan(question: String): MutableMap<String, Any?> {
        val q = question.lowercase()
        val t = today()
        val plan = mutableMapOf<String, Any?>("date_from" to null, "date_to" to null, "categories" to mutableListOf<String>(), "merchant" to null,
            "text" to null, "kind" to "debit", "min_amount" to null, "max_amount" to null, "aggregate" to "list")
        fun has(r: String) = Regex(r).containsMatchIn(q)
        if (has("\\b(how much|total|spent|spend)\\b")) plan["aggregate"] = "sum"
        if (has("\\b(how many|count|times)\\b")) plan["aggregate"] = "count"
        if (has("\\b(by category|breakdown|categories)\\b")) plan["aggregate"] = "by_category"
        if (has("\\b(received|income|credited|salary)\\b")) plan["kind"] = "credit"
        fun range(a: LocalDate, b: LocalDate) { plan["date_from"] = a.toString(); plan["date_to"] = b.toString() }
        val lastN = Regex("last (\\d+) days").find(q)
        when {
            "today" in q -> range(t, t)
            "yesterday" in q -> range(t.minusDays(1), t.minusDays(1))
            "this week" in q -> range(t.minusDays(t.dayOfWeek.value - 1L), t)
            "last week" in q -> { val s = t.minusDays(t.dayOfWeek.value - 1L + 7); range(s, s.plusDays(6)) }
            "last month" in q -> { val (a, b) = bounds(prevMonth(t.toString().take(7))); plan["date_from"] = a; plan["date_to"] = b }
            "this month" in q -> range(t.withDayOfMonth(1), t)
            lastN != null -> range(t.minusDays(lastN.groupValues[1].toLong() - 1), t)
            "this year" in q -> range(t.withDayOfYear(1), t)
            else -> for ((i, name) in MONTHS.withIndex()) {
                if (Regex("\\b(${name}|${name.take(3)})\\b").containsMatchIn(q)) {
                    val y = if (i + 1 <= t.monthValue) t.year else t.year - 1
                    val (a, b) = bounds("%d-%02d".format(y, i + 1)); plan["date_from"] = a; plan["date_to"] = b; break
                }
            }
        }
        Regex("(?:over|above|more than|>)\\s*(?:rs\\.?|₹)?\\s*(\\d+)").find(q)?.let { plan["min_amount"] = it.groupValues[1].toDouble() }
        Regex("(?:under|below|less than|<)\\s*(?:rs\\.?|₹)?\\s*(\\d+)").find(q)?.let { plan["max_amount"] = it.groupValues[1].toDouble() }
        val cats = allCategories()
        @Suppress("UNCHECKED_CAST") val pc = plan["categories"] as MutableList<String>
        val aliases = mapOf("food" to "Food & Dining", "eating out" to "Food & Dining", "dining" to "Food & Dining", "grocer" to "Groceries",
            "travel" to "Travel", "cab" to "Transport", "transport" to "Transport", "petrol" to "Fuel", "fuel" to "Fuel", "shopping" to "Shopping",
            "bill" to "Bills & Utilities", "rent" to "Rent", "movie" to "Entertainment", "entertainment" to "Entertainment", "health" to "Health",
            "medic" to "Health", "subscription" to "Entertainment", "snack" to "Snacks", "transfer" to "Transfers", "invest" to "Investments",
            "education" to "Education", "college" to "Education", "salon" to "Personal Care", "haircut" to "Personal Care", "recharge" to "Bills & Utilities")
        for (c in cats) if (c.lowercase() in q) pc += c
        for ((k, c) in aliases) if (k in q && c !in pc && c in cats) pc += c
        if (pc.isEmpty()) {
            val stop = setOf("how", "much", "did", "i", "spend", "spent", "on", "at", "in", "the", "this", "last", "month", "week", "what", "my",
                "show", "me", "all", "total", "for", "today", "yesterday", "many", "times", "pay", "paid", "to", "year", "days", "over", "under",
                "above", "below", "than", "more", "less", "expenses", "expense", "payments", "transactions", "find", "list", "a", "was", "were",
                "with", "and", "count", "spending", "money", "so", "far", "since", "which", "who", "when", "give", "tell", "about", "is", "are",
                "it", "any", "of", "from", "by", "do", "does", "have", "has", "i've", "ive", "whats", "what's", "every", "each", "weeks", "months",
                "please", "can", "you", "us", "we", "rs", "rupees", "inr", "up", "get", "got", "bought", "buy", "on") + MONTHS + MONTHS.map { it.take(3) }
            val words = Regex("[a-z][a-z0-9.&'-]+").findAll(q).map { it.value }.filter { it !in stop }.take(3).toList()
            if (words.isNotEmpty()) plan["text"] = words.joinToString(" ")
        }
        return plan
    }

    @Suppress("UNCHECKED_CAST")
    fun runPlan(plan: Map<String, Any?>): Map<String, Any?> {
        val sb = StringBuilder(" FROM transactions WHERE 1=1"); val a = mutableListOf<Any?>()
        (plan["kind"] as? String)?.takeIf { it in setOf("debit", "credit") }?.let { sb.append(" AND kind=?"); a += it }
        (plan["date_from"] as? String)?.let { sb.append(" AND date>=?"); a += it }
        (plan["date_to"] as? String)?.let { sb.append(" AND date<=?"); a += it }
        (plan["categories"] as? List<String>)?.takeIf { it.isNotEmpty() }?.let { sb.append(" AND category IN (${inClause(it.size)})"); a.addAll(it) }
        (plan["merchant"] as? String)?.let { sb.append(" AND lower(merchant) LIKE ?"); a += "%${it.lowercase()}%" }
        (plan["text"] as? String)?.split(" ")?.filter { it.isNotBlank() }?.forEach { w ->
            sb.append(" AND (lower(merchant) LIKE ? OR lower(note) LIKE ? OR lower(category) LIKE ?)"); repeat(3) { a += "%${w.lowercase()}%" }
        }
        (plan["min_amount"] as? Number)?.let { sb.append(" AND amount>=?"); a += it.toDouble() }
        (plan["max_amount"] as? Number)?.let { sb.append(" AND amount<=?"); a += it.toDouble() }
        val args = a.toTypedArray()
        val rows = db.query("SELECT *$sb ORDER BY date DESC, id DESC LIMIT 200", *args)
        val tot = db.one("SELECT COALESCE(SUM($SPEND),0) AS t, COUNT(*) AS c$sb", *args)!!
        val byCat = db.query("SELECT category, SUM($SPEND) AS t$sb GROUP BY category ORDER BY t DESC", *args)
            .map { mapOf("category" to it.s("category"), "total" to round2(it.d("t")!!)) }
        return mapOf("total" to round2(tot.d("t")!!), "count" to tot.l("c"), "by_category" to byCat, "transactions" to rows)
    }

    /** True when the quick reader understood nothing specific (no dates, categories, amounts or words to search). */
    @Suppress("UNCHECKED_CAST")
    fun isVague(plan: Map<String, Any?>): Boolean = plan["date_from"] == null && (plan["categories"] as? List<*>).isNullOrEmpty() &&
        plan["merchant"] == null && plan["text"] == null && plan["min_amount"] == null && plan["max_amount"] == null

    /** "today", "yesterday", "in November", "this week", "from 3 Nov to 9 Nov". */
    fun spanLabel(from: String?, to: String?): String {
        if (from == null) return "overall"
        val t = today(); val a = LocalDate.parse(from); val b = to?.let { LocalDate.parse(it) } ?: t
        fun short(d: LocalDate) = "${d.dayOfMonth} ${d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}" + if (d.year != t.year) " ${d.year}" else ""
        val monthName = a.month.name.lowercase().replaceFirstChar { it.uppercase() } + if (a.year != t.year) " ${a.year}" else ""
        return when {
            a == b && a == t -> "today"
            a == b && a == t.minusDays(1) -> "yesterday"
            a == b -> "on ${short(a)}"
            a.dayOfMonth == 1 && (b == a.plusMonths(1).minusDays(1) || (b == t && a.month == t.month && a.year == t.year)) ->
                if (a.month == t.month && a.year == t.year) "this month" else "in $monthName"
            a.dayOfWeek.value == 1 && b == t && t.toEpochDay() - a.toEpochDay() < 7 -> "this week"
            else -> "from ${short(a)} to ${short(b)}"
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun describe(plan: Map<String, Any?>, r: Map<String, Any?>): String {
        val what = (plan["categories"] as? List<String>)?.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: plan["merchant"] as? String ?: (plan["text"] as? String)?.let { "\"$it\"" } ?: "everything"
        val whenS = (plan["date_from"] as? String)?.let { " " + spanLabel(it, plan["date_to"] as? String) } ?: ""
        val n = (r["count"] as Long)
        val verb = if (plan["kind"] == "credit") "received" else "spent"
        if (n == 0L) return "I couldn't find any payments for $what$whenS. Try other words, like the shop's name or a category."
        val rows = (r["transactions"] as List<Map<String, Any?>>)
        fun line(t: Map<String, Any?>) = "• ${spanLabel(t["date"] as String, t["date"] as String).removePrefix("on ").replaceFirstChar { it.uppercase() }}  " +
            "${t["merchant"]}  ${rupees(((t["my_share"] ?: t["amount"]) as Number).toDouble())}"
        val list = rows.take(5).joinToString("\n", prefix = "\n") { line(it) } + if (n > 5) "\n…and ${n - 5} more" else ""
        if (plan["aggregate"] == "count") return "$n payment${if (n != 1L) "s" else ""} for $what$whenS.$list"
        val bc = r["by_category"] as List<Map<String, Any?>>
        if (plan["aggregate"] == "by_category" && bc.isNotEmpty())
            return "Breakdown$whenS:\n" + bc.take(8).joinToString("\n") { "• ${it["category"]}: ${rupees(it["total"] as Double)}" }
        if (plan["aggregate"] == "list") return "$n payment${if (n != 1L) "s" else ""} for $what$whenS, ${rupees(r["total"] as Double)} in total.$list"
        return "You $verb ${rupees(r["total"] as Double)} on $what$whenS ($n payment${if (n != 1L) "s" else ""}).$list"
    }

    // ---------------- deletion ----------------
    val ALL_TABLES = listOf("splits", "transactions", "subscriptions", "rules", "merchant_rules", "settings", "allowance_history", "detected", "payees",
        "zero_days", "freezes", "envelopes", "split_groups")

    fun deleteData(scope: String, from: String?, to: String?): Long {
        db.transaction {
            when (scope) {
                "everything" -> ALL_TABLES.forEach { db.exec("DELETE FROM $it") }
                "transactions" -> listOf("splits", "transactions", "subscriptions", "detected").forEach { db.exec("DELETE FROM $it") }
                "range" -> {
                    if (from == null || to == null) throw ApiError(400, "Pick a start and end date")
                    db.exec("DELETE FROM transactions WHERE date BETWEEN ? AND ?", from, to)
                }
                "learned" -> { db.exec("DELETE FROM merchant_rules"); db.exec("DELETE FROM rules"); db.exec("DELETE FROM payees") }
                else -> throw ApiError(400, "Unknown scope")
            }
        }
        db.vacuum()
        return db.count("SELECT COUNT(*) FROM transactions")
    }

    // ---------------- detected queue (AI findings waiting for your OK) ----------------
    fun learnedMap(): Map<String, String> = db.query("SELECT merchant, category FROM merchant_rules ORDER BY rowid DESC LIMIT 15")
        .associate { titleCase(it.s("merchant")!!) to it.s("category")!! }

    /** Adds a message or statement row to the queue. Returns false if it was already queued before. */
    fun enqueue(body: String, source: String, receivedAt: java.time.LocalDateTime, sender: String? = null, header: String? = null): Boolean {
        val text = body.trim(); if (text.isEmpty()) return false
        return db.insert("INSERT INTO detected(source, body, header, sender, received_at, raw_hash) VALUES (?,?,?,?,?,?)",
            source, text, header, sender, receivedAt.toString(), sha256((header ?: "") + "|" + text)) > 0
    }

    fun pendingCount(): Long = db.count("SELECT COUNT(*) FROM detected WHERE status='pending'")

    /** Runs the AI on the next queued item. Returns false when the queue is empty. */
    fun analyzeNext(ex: Extractor): Boolean {
        // New SMS first, then history newest-first, so recent payments show up quickly.
        val r = db.one("SELECT * FROM detected WHERE status='pending' ORDER BY (source='sms') DESC, received_at DESC, id DESC LIMIT 1") ?: return false
        val id = r.l("id")!!
        val cats = allCategories(); val known = learnedMap()
        val body = r.s("body")!!
        val isRow = r.s("source") == "import" && !r.s("header").isNullOrBlank()
        val upi = if (isRow) null else ex.upiId(body)
        val payee = upi?.let { db.one("SELECT * FROM payees WHERE upi_id=?", it) }
        val trackReceived = settings()["track_received"] == true
        // A payee you've already named: read the amount directly and skip the AI entirely (instant, no load).
        val quick = if (payee != null) ex.quickRead(body, trackReceived) else null
        val v = if (quick != null) Extractor.Verdict(Extractor.Item(quick.first, quick.second, payee!!.s("name")!!, null, payee.s("category")!!, true), null)
        else try {
            if (isRow) ex.fromRow(r.s("header")!!, body, cats, known)
            else ex.fromSms(body, cats, known, trackReceived).let { vv ->
                // Known UPI ID but the quick read wasn't sure: still use the name and category you gave.
                if (payee != null && vv.item != null && vv.item.type != "ignore") vv.copy(item = vv.item.copy(party = payee.s("name")!!, category = payee.s("category")!!, confident = true)) else vv
            }
        } catch (e: ModelMissing) { throw e } catch (e: Exception) { Extractor.Verdict(null, "The AI couldn't read this message") }
        val it = v.item
        // Your rules and corrections take priority over the AI's category guess.
        val cat = it?.let { item -> Categorizer.categorize(r.s("body"), item.party, learned(), rulesTyped(), item.amount).takeIf { c -> c != "Other" } ?: item.category }
        val status = when { it == null -> "skipped"; it.type == "ignore" -> "skipped"; else -> "analyzed" }
        db.exec("UPDATE detected SET status=?, type=?, amount=?, party=?, category=?, date=?, confident=?, reason=?, split_with=?, split_ways=? WHERE id=?",
            status, it?.type, it?.amount, it?.party, cat, it?.date?.toString(), if (it?.confident == true) 1L else 0L, v.reason,
            it?.splitWith?.joinToString(","), it?.splitWays?.toLong(), id)
        // Unnamed QR / phone payments are grouped by their UPI ID (different shops share the same label).
        db.exec("UPDATE detected SET upi_id=?, gkey=? WHERE id=?", upi,
            if (upi != null && ex.isPlaceholder(it?.party)) upi else (it?.party ?: "").lowercase(), id)
        val history = r.s("source") in setOf("history", "import")
        if (status == "analyzed" && history && it!!.type == "upcoming") {
            // An old "scheduled" message is in the past; the real debit SMS (if any) is imported separately.
            db.exec("UPDATE detected SET status='skipped', reason='Old scheduled payment' WHERE id=?", id); return true
        }
        if (status == "analyzed" && it!!.confident && it.type != "upcoming" && v.reason == null && it.party != "Unknown" &&
            ((history && settings()["auto_log_history"] != false && cat != "Other") || (!history && settings()["auto_log_confident"] == true) ||
                (payee != null && it.type == "debit"))) logDetected(id, emptyMap()) // you named this payee before: no need to ask again
        if (status == "analyzed" && !history && it!!.type == "credit" && settings()["track_received"] != true)
            db.exec("UPDATE detected SET status='skipped', reason='Money received isn''t tracked (Settings)' WHERE id=?", id)
        return true
    }

    /**
     * New SMS are listed one by one. Imported history (past SMS, statements) can be hundreds of rows,
     * so it's grouped by payee: you choose one category per payee and log the whole group at once.
     */
    fun detected(): Map<String, Any?> {
        val groups = db.query(
            "SELECT COALESCE(gkey, lower(party)) AS gkey, upi_id, party, type, COUNT(*) AS count, ROUND(SUM(amount),2) AS total, MIN(received_at) AS first, MAX(received_at) AS last, " +
                "SUM(CASE WHEN confident=1 THEN 0 ELSE 1 END) AS unsure, " +
                "(SELECT d2.category FROM detected d2 WHERE d2.status='analyzed' AND d2.source IN ('history','import') AND COALESCE(d2.gkey, lower(d2.party))=COALESCE(d.gkey, lower(d.party)) " +
                " AND d2.type=d.type GROUP BY d2.category ORDER BY COUNT(*) DESC LIMIT 1) AS category " +
                "FROM detected d WHERE status='analyzed' AND source IN ('history','import') GROUP BY COALESCE(gkey, lower(party)), type ORDER BY total DESC")
        val withSamples = groups.map { g ->
            g.toMutableMap().apply {
                this["samples"] = db.query("SELECT body, amount, received_at FROM detected WHERE status='analyzed' AND source IN ('history','import') " +
                    "AND COALESCE(gkey, lower(party))=? AND type=? ORDER BY received_at DESC LIMIT 3", g.s("gkey"), g.s("type"))
            }
        }
        return mapOf(
            "history_logged" to db.count("SELECT COUNT(*) FROM transactions WHERE source IN ('history','import')"),
            "pending" to pendingCount(),
            "pending_history" to db.count("SELECT COUNT(*) FROM detected WHERE status='pending' AND source IN ('history','import')"),
            "items" to db.query("SELECT * FROM detected WHERE status='analyzed' AND source='sms' ORDER BY received_at DESC, id DESC LIMIT 200"),
            "groups" to withSamples,
            "history_count" to groups.sumOf { it.l("count") ?: 0L },
            "skipped" to db.query("SELECT * FROM detected WHERE status='skipped' ORDER BY received_at DESC, id DESC LIMIT 100"),
        )
    }

    /** Removes everything logged from imported history, so you can start over. */
    fun undoHistory(): Int = db.transaction {
        val ids = db.query("SELECT id FROM transactions WHERE source IN ('history','import')").mapNotNull { it.l("id") }
        ids.forEach { db.exec("DELETE FROM transactions WHERE id=?", it) }
        db.exec("UPDATE detected SET status='skipped', reason='Import undone', tx_id=NULL WHERE source IN ('history','import') AND status IN ('logged','analyzed')")
        ids.size
    }

    /** Log or skip every imported item for one payee, optionally setting the category for all of them. */
    fun actOnGroup(key: String, type: String, action: String, category: String?, name: String? = null): Int {
        val party = name?.trim()?.takeIf { it.isNotEmpty() } ?: key
        val ids = db.query("SELECT id FROM detected WHERE status='analyzed' AND source IN ('history','import') AND COALESCE(gkey, lower(party))=lower(?) AND type=?", key, type)
            .mapNotNull { it.l("id") }
        return db.transaction {
            var n = 0
            for (id in ids) {
                val edits = buildMap { if (category != null) put("category", category); if (!name.isNullOrBlank()) put("party", name.trim()) }
                if (action == "skip") skipDetected(id) else try { logDetected(id, edits); n++ } catch (_: ApiError) {}
            }
            // Teach the AI: future SMS to this payee get this category.
            if (category != null && action != "skip") Categorizer.merchantKey(party).takeIf { it.isNotEmpty() && it != "unknown" }?.let {
                db.exec("INSERT INTO merchant_rules(merchant, category) VALUES(?,?) ON CONFLICT(merchant) DO UPDATE SET category=excluded.category", it, category)
            }
            if (action == "skip") ids.size else n
        }
    }

    /** Logs one detected item (with any edits you made). Upcoming payments go to subscriptions, not spending. */
    fun logDetected(id: Long, edits: Map<String, Any?>): Map<String, Any?>? {
        val r = db.one("SELECT * FROM detected WHERE id=? AND status IN ('analyzed','skipped','logged')", id) ?: throw ApiError(404, "Not found")
        if (r.s("status") == "logged") return r.l("tx_id")?.let { row(it) } ?: mapOf("subscription" to r.s("party")) // already done
        val type = (edits["type"] as? String) ?: r.s("type")?.takeIf { it != "ignore" } ?: "debit"
        val amount = (edits["amount"] as? Number)?.toDouble() ?: r.d("amount")?.takeIf { it > 0 } ?: throw ApiError(400, "Enter the amount first")
        val party = (edits["party"] as? String)?.takeIf { it.isNotBlank() } ?: r.s("party") ?: "Unknown"
        val cat = (edits["category"] as? String) ?: r.s("category") ?: "Other"
        val received = java.time.LocalDateTime.parse(r.s("received_at")).toLocalDate()
        // SMS dates come from the phone's own timestamp; statement rows use the date the AI read from the row.
        val d = if (r.s("source") == "import" && !r.s("header").isNullOrBlank()) r.s("date")?.let { try { clampDate(LocalDate.parse(it)) } catch (_: Exception) { null } } ?: received else received
        if (type == "upcoming") {
            val key = Categorizer.merchantKey(party)
            val next = r.s("date")?.let { try { LocalDate.parse(it) } catch (_: Exception) { null } } ?: received
            db.exec("INSERT INTO subscriptions(merchant_key, name, amount, period, category, next_date, status, source) VALUES (?,?,?, 'monthly', ?, ?, 'active', 'mandate') " +
                "ON CONFLICT(merchant_key) DO UPDATE SET amount=excluded.amount, next_date=excluded.next_date, status='active'",
                key, party, amount, cat, next.toString())
            db.exec("UPDATE detected SET status='logged' WHERE id=?", id)
            return mapOf("subscription" to party)
        }
        rememberPayee(r.s("upi_id"), party, cat)
        val split = r.s("split_with")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val tx = record(d, amount, if (type == "credit") "credit" else "debit", party, r.s("body") ?: party,
            r.s("source") ?: "sms", cat.takeIf { it in allCategories() }, raw = null,
            splitWith = split, splitWays = r.l("split_ways")?.toInt(), confident = true)
        if (edits["category"] != null && tx != null) setCategory(tx["id"] as Long, cat) // remember your correction
        db.exec("UPDATE detected SET status='logged', tx_id=? WHERE id=?", tx?.get("id"), id)
        return tx
    }

    private fun isPlaceholder(p: String?) = needsName(p)

    /** Remembers who a UPI ID is, so the next payment to it is named and categorized without the AI. */
    fun rememberPayee(upi: String?, name: String, category: String) {
        if (upi.isNullOrBlank() || isPlaceholder(name)) return
        db.exec("INSERT INTO payees(upi_id, name, category) VALUES(?,?,?) ON CONFLICT(upi_id) DO UPDATE SET name=excluded.name, category=excluded.category",
            upi, name.trim().take(60), category)
        // Anything else still waiting from the same UPI ID gets the name too.
        db.exec("UPDATE detected SET party=?, category=?, gkey=lower(?), confident=1 WHERE upi_id=? AND status='analyzed'", name.trim().take(60), category, name.trim(), upi)
    }

    fun payees(): List<Row> = db.query("SELECT * FROM payees ORDER BY name")

    /** The UPI ID a logged payment came from (if it came from a bank SMS). */
    fun upiOfTx(txId: Long): String? = db.one("SELECT upi_id FROM detected WHERE tx_id=? AND upi_id IS NOT NULL", txId)?.s("upi_id")

    /** Remembers a payment's current name and category for its UPI ID. */
    fun rememberFromTx(txId: Long) {
        val upi = upiOfTx(txId) ?: return
        val t = db.one("SELECT merchant, category FROM transactions WHERE id=?", txId) ?: return
        rememberPayee(upi, t.s("merchant") ?: return, t.s("category") ?: "Other")
    }

    fun isKnownPayee(upi: String?): Boolean = upi != null && db.one("SELECT 1 AS x FROM payees WHERE upi_id=?", upi) != null

    fun skipDetected(id: Long) = db.exec("UPDATE detected SET status='skipped', reason=COALESCE(reason, 'Skipped by you') WHERE id=?", id)

    fun logAllDetected(): Int {
        val ids = db.query("SELECT id FROM detected WHERE status='analyzed' ORDER BY received_at, id").mapNotNull { it.l("id") }
        return db.transaction {
            var n = 0
            for (id in ids) try { logDetected(id, emptyMap()); n++ } catch (_: ApiError) {}
            n
        }
    }

    /** Answer to "What was it for?": the AI turns your words into a category; the payment is logged (or re-categorized). */
    fun logWithPurpose(id: Long, purpose: String, ex: Extractor): Map<String, Any?>? {
        val r = db.one("SELECT * FROM detected WHERE id=?", id) ?: throw ApiError(404, "Not found")
        // For an unnamed QR or number, the answer is "who, what for" (e.g. "Canteen, food"); just "Mom" works too.
        val unnamed = isPlaceholder(r.s("party"))
        val who = if (unnamed) purpose.substringBefore(',').trim().take(60).ifEmpty { null } else null
        val why = if (unnamed && ',' in purpose) purpose.substringAfter(',').trim() else purpose.trim()
        val party = who ?: r.s("party") ?: "Unknown"
        val cat = ex.categoryFor(why.ifEmpty { party }, party, allCategories()) ?: r.s("category") ?: "Other"
        val note = if (why.equals(party, true) || why.isEmpty()) party else "${why.take(120)} ($party)"
        return if (r.s("status") == "logged" && r.l("tx_id") != null) {
            val txId = r.l("tx_id")!!
            setCategory(txId, cat); db.exec("UPDATE transactions SET note=?, merchant=? WHERE id=?", note, party, txId)
            rememberPayee(r.s("upi_id"), party, cat); row(txId)
        } else {
            val tx = logDetected(id, buildMap { put("category", cat); if (who != null) put("party", who) })
            (tx?.get("id") as? Long)?.let { db.exec("UPDATE transactions SET note=? WHERE id=?", note, it) }
            tx?.let { row(it["id"] as Long) }
        }
    }

    /** Replaces everything with a backup made by "Full backup" (export.json). Unknown tables/columns are ignored. */
    @Suppress("UNCHECKED_CAST")
    fun restore(backup: Map<String, Any?>): Long {
        if ((backup["transactions"] as? List<*>) == null) throw ApiError(400, "That isn't an PAISAve backup file")
        db.transaction {
            ALL_TABLES.forEach { db.exec("DELETE FROM $it") }
            for (table in ALL_TABLES.reversed()) { // parents (transactions) before children (splits)
                val cols = db.query("PRAGMA table_info($table)").mapNotNull { it.s("name") }.toSet()
                for (row in (backup[table] as? List<Map<String, Any?>>) ?: emptyList()) {
                    val keys = row.keys.filter { it in cols }
                    if (keys.isEmpty()) continue
                    db.insert("INSERT OR IGNORE INTO $table (${keys.joinToString(",")}) VALUES (${keys.joinToString(",") { "?" }})",
                        *keys.map { row[it] }.toTypedArray())
                }
            }
        }
        return db.count("SELECT COUNT(*) FROM transactions")
    }

    fun clampDate(d: LocalDate): LocalDate = min(d.toEpochDay(), today().toEpochDay()).let { LocalDate.ofEpochDay(it) }
}
