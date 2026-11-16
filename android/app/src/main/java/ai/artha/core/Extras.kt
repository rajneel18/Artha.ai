package ai.artha.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * Streaks, envelopes, pace forecasts and split groups. Everything stays on the phone.
 */
class Extras(private val e: Engine) {
    private val db get() = e.db
    private fun today() = e.today()

    init {
        listOf(
            "CREATE TABLE IF NOT EXISTS zero_days (date TEXT PRIMARY KEY)",
            "CREATE TABLE IF NOT EXISTS freezes (date TEXT PRIMARY KEY)",
            """CREATE TABLE IF NOT EXISTS envelopes (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, budget REAL NOT NULL,
                start_date TEXT NOT NULL, end_date TEXT, archived INTEGER NOT NULL DEFAULT 0)""",
            """CREATE TABLE IF NOT EXISTS split_groups (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, members TEXT NOT NULL)""",
        ).forEach { db.exec(it) }
        try { db.exec("ALTER TABLE transactions ADD COLUMN envelope_id INTEGER") } catch (_: Exception) {}
    }

    // ---------------- monthly budget ----------------
    fun monthlyBudget(): Double = (e.settings()["monthly_budget"] as? Number)?.toDouble() ?: 15000.0

    // ---------------- streaks ----------------
    /**
     * A day counts if you logged anything or confirmed a zero-spend day. Miss a day and a freeze (one per month)
     * keeps the streak alive without adding to it. Today only breaks the streak once it's over.
     */
    fun streak(): Map<String, Any?> {
        val t = today()
        val logged = db.query("SELECT DISTINCT date FROM transactions WHERE date >= ?", t.minusDays(400).toString()).mapNotNull { it.s("date") }.toSet() +
            db.query("SELECT date FROM zero_days").mapNotNull { it.s("date") }.toSet()
        val frozen = db.query("SELECT date FROM freezes").mapNotNull { it.s("date") }.toMutableSet()
        // Walk back from yesterday; auto-use a freeze for a single missed day if this month's freeze is unused.
        var d = t.minusDays(1); var current = 0
        while (true) {
            val k = d.toString()
            if (k in logged) { current++; d = d.minusDays(1); continue }
            if (k in frozen) { d = d.minusDays(1); continue }
            val prevLogged = d.minusDays(1).toString() in logged
            val monthUsed = frozen.any { it.startsWith(k.take(7)) }
            if (current > 0 && prevLogged && !monthUsed) { db.exec("INSERT OR IGNORE INTO freezes(date) VALUES(?)", k); frozen += k; d = d.minusDays(1); continue }
            break
        }
        val todayDone = t.toString() in logged
        if (todayDone) current++
        // Longest ever
        val days = (logged + frozen).sorted()
        var best = 0; var run = 0; var prev: LocalDate? = null
        for (s in days) {
            val x = LocalDate.parse(s)
            run = if (prev != null && ChronoUnit.DAYS.between(prev, x) == 1L) run + 1 else 1
            if (s in logged) best = maxOf(best, run)
            prev = x
        }
        best = maxOf(best, current)
        val milestones = listOf(3 to "A good start", 7 to "A full week", 30 to "A month of tracking", 50 to "50 days. It's a habit now", 100 to "100 days", 365 to "A full year")
        val next = milestones.firstOrNull { it.first > current }
        val days35 = (34 downTo 0).map { i -> val k = t.minusDays(i.toLong()).toString()
            mapOf("date" to k, "state" to when {
                k in db.query("SELECT date FROM zero_days").mapNotNull { it.s("date") } -> "zero"
                k in logged -> "logged"; k in frozen -> "frozen"; i == 0 -> "today"; else -> "missed" }) }
        return mapOf("days" to days35, "current" to current, "best" to best, "today_done" to todayDone,
            "at_risk" to (!todayDone && current > 0), "freeze_available" to frozen.none { it.startsWith(t.toString().take(7)) },
            "freezes_used" to frozen.size, "milestone" to milestones.lastOrNull { it.first <= current }?.second,
            "next_milestone" to next?.first, "zero_today" to (db.one("SELECT 1 AS x FROM zero_days WHERE date=?", t.toString()) != null))
    }

    fun markZeroDay(): Map<String, Any?> {
        val t = today().toString()
        if (db.count("SELECT COUNT(*) FROM transactions WHERE date=? AND kind='debit'", t) > 0) throw ApiError(400, "You already logged spending today")
        db.exec("INSERT OR IGNORE INTO zero_days(date) VALUES(?)", t)
        return streak()
    }

    // ---------------- envelopes ----------------
    /** Separate pots of money ("Goa trip ₹15,000"). Spending in them doesn't count toward category limits. */
    fun envelopes(includeArchived: Boolean = false): List<Map<String, Any?>> {
        val t = today().toString()
        db.exec("UPDATE envelopes SET archived=1 WHERE archived=0 AND end_date IS NOT NULL AND end_date < ?", t) // expired -> archive
        return db.query("SELECT * FROM envelopes ${if (includeArchived) "" else "WHERE archived=0"} ORDER BY archived, id DESC").map { r ->
            val spent = db.num("SELECT COALESCE(SUM(COALESCE(my_share, amount)),0) FROM transactions WHERE envelope_id=? AND kind='debit'", r.l("id"))
            val budget = r.d("budget") ?: 0.0
            r.toMutableMap().apply {
                this["spent"] = round2(spent); this["left"] = round2(budget - spent)
                this["pct"] = if (budget > 0) (spent / budget * 100).roundToLong() else 0L
                this["days_left"] = r.s("end_date")?.let { ChronoUnit.DAYS.between(today(), LocalDate.parse(it)) }
                this["count"] = db.count("SELECT COUNT(*) FROM transactions WHERE envelope_id=?", r.l("id"))
            }
        }
    }

    fun createEnvelope(name: String, budget: Double, end: String?): Map<String, Any?> {
        if (name.isBlank() || budget <= 0) throw ApiError(400, "Name and amount are needed")
        db.insert("INSERT INTO envelopes(name, budget, start_date, end_date) VALUES (?,?,?,?)", name.trim().take(40), budget, today().toString(),
            end?.let { LocalDate.parse(it).toString() })
        return mapOf("envelopes" to envelopes())
    }

    fun deleteEnvelope(id: Long) { db.exec("UPDATE transactions SET envelope_id=NULL WHERE envelope_id=?", id); db.exec("DELETE FROM envelopes WHERE id=?", id) }
    fun linkEnvelope(txId: Long, envId: Long?) = db.exec("UPDATE transactions SET envelope_id=? WHERE id=?", envId, txId)

    // ---------------- pace forecast ----------------
    private val ONE_OFF = setOf("Rent", "Bills & Utilities", "Investments", "Transfers", "Education")

    /**
     * Where the month is heading. One-off costs (rent, bills, investments, fees, and any single expense of 20%+
     * of the budget) happen once, so they're added as-is and not projected forward.
     * "Your normal" is the average day over the 3 months before this one.
     */
    fun pace(): Map<String, Any?> {
        val t = today(); val start = t.withDayOfMonth(1); val days = t.lengthOfMonth(); val elapsed = t.dayOfMonth
        val budget = monthlyBudget()
        val rows = db.query("SELECT category, COALESCE(my_share, amount) AS a, envelope_id FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ?",
            start.toString(), t.toString())
        var flexible = 0.0; var oneOff = 0.0
        for (r in rows) {
            if (r["envelope_id"] != null) continue
            val a = r.d("a") ?: 0.0
            if (r.s("category") in ONE_OFF || a >= 0.2 * budget) oneOff += a else flexible += a
        }
        val spent = flexible + oneOff
        val projected = oneOff + flexible / elapsed * days
        val normalFrom = start.minusMonths(3)
        val normalTotal = db.num("SELECT COALESCE(SUM(COALESCE(my_share, amount)),0) FROM transactions WHERE kind='debit' AND envelope_id IS NULL " +
            "AND category NOT IN ('Rent','Bills & Utilities','Investments','Transfers','Education') AND date >= ? AND date < ?", normalFrom.toString(), start.toString())
        val firstTx = db.scalar("SELECT MIN(date) FROM transactions") as? String
        val normalDays = if (firstTx == null) 0L else ChronoUnit.DAYS.between(maxOf(normalFrom, LocalDate.parse(firstTx)), start).coerceAtLeast(0)
        val normalDay = if (normalDays >= 14) normalTotal / normalDays else null
        val perDay = flexible / elapsed
        val runsOut = if (perDay > 0 && budget > oneOff) start.plusDays(((budget - oneOff) / perDay).toLong()).takeIf { it.month == t.month } else null
        val headline = when {
            rows.size < 3 -> "Give me a few days of logging. Forecasts need a little data."
            projected > budget && runsOut != null -> "At this pace, your budget runs out by ${runsOut.dayOfMonth} ${runsOut.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}."
            projected > budget -> "At this pace you'll overshoot by ${rupees(projected - budget)}."
            normalDay != null && perDay < normalDay * 0.85 -> "A lighter month. ${rupees(perDay)}/day vs your normal ${rupees(normalDay)}."
            else -> "On pace for ${rupees(projected)} this month. ${days - elapsed} days to go."
        }
        return mapOf("spent" to round2(spent), "budget" to budget, "remaining" to round2(budget - spent), "projected" to projected.roundToLong(),
            "per_day" to round2(perDay), "normal_day" to normalDay?.let { round2(it) }, "runs_out" to runsOut?.toString(),
            "days_left" to (days - elapsed), "headline" to headline, "safe_per_day" to round2(((budget - spent) / (days - elapsed + 1)).coerceAtLeast(0.0)))
    }

    private fun rupees(x: Double) = "₹" + java.text.NumberFormat.getIntegerInstance(java.util.Locale("en", "IN")).format(x.roundToLong())

    // ---------------- split groups (on the phone) ----------------
    fun groups(): List<Map<String, Any?>> = db.query("SELECT * FROM split_groups ORDER BY name").map { g ->
        val members = (g.s("members") ?: "").split(",").filter { it.isNotBlank() }
        g.toMutableMap().apply { this["members"] = members }
    }

    fun createGroup(name: String, members: List<String>): List<Map<String, Any?>> {
        if (name.isBlank() || members.isEmpty()) throw ApiError(400, "Name the group and add people")
        db.insert("INSERT INTO split_groups(name, members) VALUES (?,?)", name.trim().take(40), members.map { titleCase(it.trim()) }.filter { it.isNotEmpty() }.joinToString(","))
        return groups()
    }

    fun deleteGroup(id: Long) = db.exec("DELETE FROM split_groups WHERE id=?", id)

    /** People you've split with, plus net balance (positive = they owe you). */
    fun friends(): Map<String, Any?> {
        val people = db.query("SELECT s.person AS person, ROUND(SUM(CASE WHEN s.settled=0 THEN s.amount ELSE 0 END),2) AS owed, COUNT(*) AS items, MAX(t.date) AS last " +
            "FROM splits s JOIN transactions t ON t.id=s.tx_id GROUP BY lower(s.person) ORDER BY owed DESC")
        return mapOf("net" to round2(people.sumOf { it.d("owed") ?: 0.0 }), "friends" to people)
    }

    /** New split without an SMS: you paid [amount] for [what] and share it with [people]. */
    fun newSplit(amount: Double, what: String, people: List<String>, category: String?): Map<String, Any?>? {
        if (amount <= 0 || people.isEmpty()) throw ApiError(400, "Add the amount and who you split with")
        val tx = e.record(today(), amount, "debit", what.ifBlank { "Split" }, what, "manual", category ?: "Food & Dining") ?: return null
        return e.applySplit(tx["id"] as Long, people, null)
    }
}
