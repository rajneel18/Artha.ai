package ai.artha.core

import java.time.LocalDate
import java.util.Base64

/** Optional AI features. Implemented with Claude on the phone; faked in tests. */
interface Ai {
    data class Expense(val amount: Double, val merchant: String, val category: String, val kind: String, val date: String,
                       val splitWith: List<String>, val splitWays: Int, val confident: Boolean)
    data class Receipt(val merchant: String, val total: Double, val date: String, val category: String,
                       val items: List<String>, val confident: Boolean)

    class Unavailable(message: String) : Exception(message)

    fun available(): Boolean
    fun parseExpenses(text: String, categories: List<String>, today: LocalDate): List<Expense>
    fun scanReceipt(data: ByteArray, mediaType: String, categories: List<String>, today: LocalDate): Receipt
    fun planQuery(question: String, categories: List<String>, today: LocalDate): Map<String, Any?>
    fun monthlyInsights(statsJson: String): Map<String, Any?>
}

/**
 * The app's API, called by the UI through the WebView bridge and by the background SMS / notification capture.
 * Same routes and JSON shapes as the original web server, so the UI code barely changed.
 */
class Api(val engine: Engine, val ai: Ai) {
    private val db get() = engine.db
    private fun today() = engine.today()

    data class Response(val status: Int, val body: Any?)

    fun handle(method: String, fullPath: String, body: String?): Response = try {
        val path = fullPath.substringBefore("?")
        val query = fullPath.substringAfter("?", "").split("&").filter { "=" in it }
            .associate { java.net.URLDecoder.decode(it.substringBefore("="), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        Response(200, route(method.uppercase(), path, query, parseBody(body)))
    } catch (e: ApiError) {
        Response(e.status, mapOf("detail" to e.message))
    } catch (e: Ai.Unavailable) {
        Response(503, mapOf("detail" to e.message))
    } catch (e: Exception) {
        Response(500, mapOf("detail" to (e.message ?: "Something went wrong")))
    }

    private fun aiOn() = ai.available() && engine.settings()["ai_enabled"] == true
    private fun month(q: Map<String, String>) = q["month"]?.also {
        if (!Regex("\\d{4}-\\d{2}").matches(it)) throw ApiError(400, "month must be YYYY-MM")
    } ?: today().toString().take(7)
    private fun id(seg: String) = seg.toLongOrNull() ?: throw ApiError(404, "Not found")
    private fun checkCategory(c: String?) { if (c != null && c !in engine.allCategories()) throw ApiError(400, "Unknown category") }

    private fun route(m: String, path: String, q: Map<String, String>, b: Map<String, Any?>): Any? {
        val p = path.trim('/').removePrefix("api/").split("/")
        return when {
            // ---- capture ----
            m == "POST" && p == listOf("ingest") -> ingestText(b.str("text") ?: "", null, b.bool("smart") == true)
            m == "POST" && p == listOf("receipt") -> scanReceipt(b)
            m == "POST" && p == listOf("transactions") -> addTx(b)
            m == "POST" && p == listOf("import", "csv") -> importCsv(b.str("text") ?: "")

            // ---- ledger ----
            m == "GET" && p == listOf("transactions") -> listTx(q)
            m == "GET" && p.size == 2 && p[0] == "transactions" -> engine.row(id(p[1])) ?: throw ApiError(404, "Not found")
            m == "PATCH" && p.size == 2 && p[0] == "transactions" -> updateTx(id(p[1]), b)
            m == "DELETE" && p.size == 2 && p[0] == "transactions" -> { db.exec("DELETE FROM transactions WHERE id=?", id(p[1])); mapOf("ok" to true) }

            // ---- clarification inbox ----
            m == "GET" && p == listOf("review") -> db.query("SELECT id FROM transactions WHERE needs_review=1 ORDER BY date DESC, id DESC").map { engine.row(it.l("id")!!) }
            m == "POST" && p.size == 2 && p[0] == "review" -> {
                val action = b.str("action") ?: "confirm"
                if (action !in setOf("confirm", "delete", "reactivate_subscription")) throw ApiError(400, "Unknown action")
                checkCategory(b.str("category"))
                engine.resolveReview(id(p[1]), action, b.str("category"), b.str("merchant")) ?: throw ApiError(404, "Not found")
            }

            // ---- splits ----
            m == "POST" && p.size == 3 && p[0] == "transactions" && p[2] == "split" -> {
                val people = b.strList("people") ?: emptyList()
                val ways = b.dbl("ways")?.toInt()
                @Suppress("UNCHECKED_CAST") val amounts = (b["amounts"] as? Map<String, Any?>)?.mapValues { (it.value as? Number)?.toDouble() ?: 0.0 }
                if (people.isEmpty() && ways == null && amounts == null) throw ApiError(400, "Say who you split with or how many ways")
                if (ways != null && ways !in 2..50) throw ApiError(400, "Split between 2 and 50 people")
                engine.applySplit(id(p[1]), people.take(50), ways, amounts)
            }
            m == "DELETE" && p.size == 3 && p[0] == "transactions" && p[2] == "split" -> { engine.removeSplit(id(p[1])); engine.row(id(p[1])) }
            m == "GET" && p == listOf("splits") -> engine.balances()
            m == "POST" && p == listOf("splits", "settle") ->
                mapOf("settled" to engine.settle(b.str("person") ?: throw ApiError(400, "Who settled?"), b.dbl("amount")), "balances" to engine.balances())

            // ---- budget & settings ----
            m == "GET" && p == listOf("categories") -> engine.allCategories()
            m == "GET" && p == listOf("settings") -> engine.settings().apply { this["ai_available"] = ai.available() }
            m == "PUT" && p == listOf("settings") -> putSettings(b)
            m == "GET" && p == listOf("budget") -> engine.budgetStatus()
            m == "GET" && p == listOf("summary") -> summary(month(q))

            // ---- rules ----
            m == "GET" && p == listOf("rules") -> mapOf("rules" to engine.customRules(), "learned" to db.query("SELECT * FROM merchant_rules ORDER BY merchant"))
            m == "POST" && p == listOf("rules") -> addRule(b)
            m == "DELETE" && p.size == 2 && p[0] == "rules" -> { db.exec("DELETE FROM rules WHERE id=?", id(p[1])); mapOf("ok" to true) }
            m == "DELETE" && p.size == 2 && p[0] == "learned" -> { db.exec("DELETE FROM merchant_rules WHERE merchant=?", Categorizer.merchantKey(p[1])); mapOf("ok" to true) }

            // ---- subscriptions ----
            m == "GET" && p == listOf("subscriptions") -> engine.subscriptions()
            m == "POST" && p == listOf("subscriptions", "scan") -> { engine.scanRecurring(); engine.subscriptions() }
            m == "POST" && p == listOf("subscriptions") -> addSubscription(b)
            m == "PATCH" && p.size == 2 && p[0] == "subscriptions" -> updateSubscription(id(p[1]), b)

            // ---- merchants, questions, insights ----
            m == "GET" && p == listOf("merchants") -> db.query(
                "SELECT merchant, ROUND(SUM(COALESCE(my_share, amount)),2) AS total, COUNT(*) AS count, MAX(date) AS last FROM transactions " +
                    "WHERE kind='debit' AND lower(merchant) LIKE ? GROUP BY lower(merchant) ORDER BY total DESC LIMIT 50", "%${(q["q"] ?: "").lowercase()}%")
            m == "GET" && p == listOf("merchants", "history") -> engine.merchantHistory(q["name"] ?: throw ApiError(400, "name required"))
            m == "POST" && p == listOf("ask") -> ask(b.str("question") ?: throw ApiError(400, "Ask a question"))
            m == "GET" && p == listOf("insights") -> month(q).let { mo ->
                mapOf("month" to mo, "insights" to engine.ruleInsights(mo), "stats" to engine.monthStats(mo),
                    "recommendations" to engine.recommendations(mo), "ai_ready" to aiOn())
            }
            m == "POST" && p == listOf("insights", "ai") -> aiInsights(month(q))
            m == "GET" && p == listOf("recommendations") -> engine.recommendations(month(q))

            // ---- your data ----
            m == "GET" && p == listOf("export.csv") -> mapOf("filename" to "artha-transactions.csv", "mime" to "text/csv", "content" to exportCsv())
            m == "GET" && p == listOf("export.json") -> mapOf("filename" to "artha-backup.json", "mime" to "application/json",
                "content" to toJson(engine.ALL_TABLES.associateWith { db.query("SELECT * FROM $it") }).toString())
            m == "POST" && p == listOf("data", "delete") -> {
                if (b.str("confirm") != "DELETE") throw ApiError(400, "Type DELETE to confirm")
                mapOf("ok" to true, "remaining_transactions" to engine.deleteData(b.str("scope") ?: "", b.str("date_from"), b.str("date_to")))
            }
            m == "POST" && p == listOf("data", "restore") -> {
                if (b.str("confirm") != "RESTORE") throw ApiError(400, "Type RESTORE to confirm")
                val backup = try { parseBody(b.str("content")) } catch (e: Exception) { throw ApiError(400, "That isn't an Artha backup file") }
                mapOf("ok" to true, "transactions" to engine.restore(backup))
            }
            else -> throw ApiError(404, "Not found")
        }
    }

    // ---------------- capture ----------------
    private fun splitMessages(text: String): List<String> {
        val t = text.trim()
        return if (Regex("\\n\\s*\\n").containsMatchIn(t)) t.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        else t.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun recordAi(e: Ai.Expense, source: String, cats: List<String>): Map<String, Any?>? {
        val d = try { engine.clampDate(LocalDate.parse(e.date)) } catch (_: Exception) { today() }
        return engine.record(d, kotlin.math.abs(e.amount), if (e.kind == "credit") "credit" else "debit", e.merchant.ifBlank { "Unknown" },
            e.merchant, source, e.category.takeIf { it in cats }, splitWith = e.splitWith, splitWays = e.splitWays.takeIf { it > 1 },
            confident = e.confident)
    }

    fun ingestText(text: String, sourceOverride: String?, smart: Boolean): Map<String, Any?> {
        if (text.length > 50_000) throw ApiError(413, "Too much text at once")
        val added = mutableListOf<Map<String, Any?>>(); val skipped = mutableListOf<String>(); val notes = mutableListOf<String>()
        var ignored = 0; var duplicates = 0
        val useAi = aiOn()
        val cats = engine.allCategories()
        if (smart && useAi) {
            try {
                ai.parseExpenses(Redact.apply(text), cats, today()).forEach { e -> recordAi(e, sourceOverride ?: "voice", cats)?.let { added += it } }
                return mapOf("added" to added, "skipped" to skipped, "ignored" to 0, "duplicates" to 0, "notes" to notes)
            } catch (e: Ai.Unavailable) { notes += e.message ?: "AI unavailable" }
        }
        for (msg in splitMessages(text)) {
            if (Parser.isNonTransaction(msg)) { ignored++; continue }
            val p = Parser.parse(msg, today())
            if (p == null) {
                if (useAi) {
                    try { ai.parseExpenses(Redact.apply(msg), cats, today()).forEach { e -> recordAi(e, sourceOverride ?: "text", cats)?.let { added += it } }; continue }
                    catch (e: Ai.Unavailable) { notes += e.message ?: "AI unavailable" }
                }
                skipped += msg; continue
            }
            val r = engine.record(p.date, p.amount, p.kind, p.merchant, p.note, sourceOverride ?: p.source,
                raw = if (p.source == "sms" || sourceOverride != null) msg else null, splitWith = p.splitWith, splitWays = p.splitWays, confident = p.confident)
            if (r == null) duplicates++ else added += r
        }
        return mapOf("added" to added, "skipped" to skipped, "ignored" to ignored, "duplicates" to duplicates, "notes" to notes)
    }

    /** Background capture from a bank SMS or a payment-app notification. Strict, so personal messages are never logged. */
    fun captureAutomatic(text: String, source: String): Map<String, Any?>? {
        val one = text.split(Regex("\\s+")).joinToString(" ")
        if (!Parser.looksLikePayment(one)) return null
        val p = Parser.parse(one, today()) ?: return null
        return engine.record(p.date, p.amount, p.kind, p.merchant, p.note, source, raw = one, confident = p.confident)
    }

    private fun scanReceipt(b: Map<String, Any?>): Map<String, Any?> {
        val media = (b.str("mime") ?: "").lowercase().let { if (it == "image/jpg") "image/jpeg" else it }
        if (media !in setOf("image/jpeg", "image/png", "image/webp", "image/gif", "application/pdf")) throw ApiError(400, "Use a JPG, PNG, WEBP photo or a PDF")
        val data = Base64.getDecoder().decode(b.str("data") ?: throw ApiError(400, "No file"))
        if (data.size > 10 * 1024 * 1024) throw ApiError(413, "File is larger than 10 MB")
        if (!aiOn()) throw ApiError(400, "Turn on AI features in Settings to scan receipts")
        val cats = engine.allCategories()
        val rc = ai.scanReceipt(data, media, cats, today())
        val d = try { engine.clampDate(LocalDate.parse(rc.date)) } catch (_: Exception) { today() }
        val note = rc.merchant + if (rc.items.isNotEmpty()) ": " + rc.items.take(8).joinToString(", ") else ""
        val tx = engine.record(d, rc.total, "debit", rc.merchant.ifBlank { "Unknown" }, note, "receipt", rc.category.takeIf { it in cats }, confident = rc.confident)
        return mapOf("transaction" to tx, "items" to rc.items)
    }

    private fun addTx(b: Map<String, Any?>): Any? {
        val amount = b.dbl("amount") ?: 0.0
        val kind = b.str("kind") ?: "debit"
        if (amount <= 0 || kind !in setOf("debit", "credit")) throw ApiError(400, "Amount must be positive and kind debit/credit")
        checkCategory(b.str("category"))
        val merchant = b.str("merchant") ?: "Unknown"
        return engine.record(b.str("date")?.let { LocalDate.parse(it) } ?: today(), amount, kind, merchant,
            b.str("note") ?: merchant, "manual", b.str("category"))
    }

    private fun listTx(q: Map<String, String>): List<Map<String, Any?>> {
        val sb = StringBuilder("SELECT * FROM transactions WHERE 1=1"); val a = mutableListOf<Any?>()
        q["month"]?.let { sb.append(" AND substr(date,1,7)=?"); a += it }
        q["category"]?.let { sb.append(" AND category=?"); a += it }
        q["merchant"]?.let { sb.append(" AND lower(merchant)=?"); a += Categorizer.merchantKey(it) }
        sb.append(" ORDER BY date DESC, id DESC LIMIT ?"); a += minOf(q["limit"]?.toLongOrNull() ?: 500L, 2000L)
        val splitIds = db.query("SELECT DISTINCT tx_id FROM splits").mapNotNull { it.l("tx_id") }.toSet()
        return db.query(sb.toString(), *a.toTypedArray()).map { r -> r.toMutableMap().apply { this["is_split"] = r.l("id") in splitIds } }
    }

    private fun updateTx(id: Long, b: Map<String, Any?>): Any? {
        if (db.one("SELECT 1 AS x FROM transactions WHERE id=?", id) == null) throw ApiError(404, "Not found")
        checkCategory(b.str("category"))
        b.dbl("amount")?.let { if (it <= 0) throw ApiError(400, "Amount must be positive"); db.exec("UPDATE transactions SET amount=? WHERE id=?", round2(it), id) }
        b.str("merchant")?.let { db.exec("UPDATE transactions SET merchant=? WHERE id=?", it.trim().take(60), id) }
        b.str("date")?.let { db.exec("UPDATE transactions SET date=? WHERE id=?", LocalDate.parse(it).toString(), id) }
        b.str("note")?.let { db.exec("UPDATE transactions SET note=? WHERE id=?", Redact.apply(it), id) }
        b.str("kind")?.let { if (it !in setOf("debit", "credit")) throw ApiError(400, "kind must be debit or credit"); db.exec("UPDATE transactions SET kind=? WHERE id=?", it, id) }
        b.str("category")?.let {
            engine.setCategory(id, it)
            db.exec("UPDATE transactions SET needs_review=0, review_reason=NULL WHERE id=? AND review_reason IN ('uncategorized','low_confidence')", id)
        }
        return engine.row(id)
    }

    private fun putSettings(b: Map<String, Any?>): Any? {
        val clean = mutableMapOf<String, Any?>()
        b.dbl("daily_allowance")?.let { if (it !in 0.0..1_000_000.0) throw ApiError(400, "Daily allowance must be between 0 and 10,00,000"); clean["daily_allowance"] = round2(it) }
        b.strList("excluded_categories")?.let { clean["excluded_categories"] = it.take(50) }
        b.strList("custom_categories")?.let { l -> clean["custom_categories"] = l.map { it.trim().take(30) }.filter { it.isNotEmpty() }.toSortedSet().take(30) }
        b.dbl("savings_goal_pct")?.let { clean["savings_goal_pct"] = it.toLong().coerceIn(0, 90) }
        b.str("budget_start")?.let { clean["budget_start"] = LocalDate.parse(it).toString() }
        for (k in listOf("ai_enabled", "auto_capture_sms", "auto_capture_notifications", "notify_logged", "app_lock", "hide_in_recents"))
            b.bool(k)?.let { clean[k] = it }
        engine.ensureStarted()
        return engine.setSettings(clean).apply { this["ai_available"] = ai.available() }
    }

    private fun summary(month: String): Map<String, Any?> {
        val st = engine.monthStats(month)
        @Suppress("UNCHECKED_CAST") val bc = (st["by_category"] as Map<String, Double>).entries.sortedByDescending { it.value }.map { mapOf("category" to it.key, "total" to it.value) }
        return mapOf("month" to month, "spent" to st["spent"], "received" to st["received"],
            "today" to round2(db.num("SELECT COALESCE(SUM(COALESCE(my_share, amount)),0) FROM transactions WHERE kind='debit' AND date=?", today().toString())),
            "by_category" to bc, "budget" to engine.budgetStatus(), "review_count" to db.count("SELECT COUNT(*) FROM transactions WHERE needs_review=1"))
    }

    private fun addRule(b: Map<String, Any?>): Any? {
        val pattern = b.str("pattern")?.trim() ?: throw ApiError(400, "Pattern required")
        val type = b.str("match_type") ?: "contains"
        if (type !in setOf("contains", "regex", "merchant")) throw ApiError(400, "match_type must be contains, regex or merchant")
        if (type == "regex") try { Regex(pattern) } catch (e: Exception) { throw ApiError(400, "Invalid regex: ${e.message}") }
        val cat = b.str("category"); checkCategory(cat); if (cat == null) throw ApiError(400, "Category required")
        val id = db.insert("INSERT INTO rules(pattern, match_type, category, min_amount, max_amount, priority) VALUES (?,?,?,?,?,?)",
            pattern.take(200), type, cat, b.dbl("min_amount"), b.dbl("max_amount"), b.dbl("priority")?.toLong() ?: 0L)
        var changed = 0
        if (b.bool("apply_to_existing") != false) {
            val rule = Categorizer.Rule(pattern, type, cat, b.dbl("min_amount"), b.dbl("max_amount"))
            for (t in db.query("SELECT id, merchant, note, amount FROM transactions")) {
                if (Categorizer.ruleMatches(rule, t.s("note") ?: "", t.s("merchant") ?: "", t.d("amount"))) {
                    db.exec("UPDATE transactions SET category=?, needs_review=CASE WHEN review_reason='uncategorized' THEN 0 ELSE needs_review END, " +
                        "review_reason=CASE WHEN review_reason='uncategorized' THEN NULL ELSE review_reason END WHERE id=?", cat, t.l("id"))
                    changed++
                }
            }
        }
        return mapOf("id" to id, "updated_transactions" to changed)
    }

    private val PERIODS = setOf("weekly", "monthly", "quarterly", "yearly")

    private fun addSubscription(b: Map<String, Any?>): Any? {
        val name = b.str("name") ?: throw ApiError(400, "Name and amount are required")
        val amount = b.dbl("amount")?.takeIf { it > 0 } ?: throw ApiError(400, "Name and amount are required")
        val period = b.str("period")?.takeIf { it in PERIODS } ?: "monthly"
        db.exec("INSERT INTO subscriptions(merchant_key, name, amount, period, next_date, status, source) VALUES (?,?,?,?,?, 'active', 'manual') " +
            "ON CONFLICT(merchant_key) DO UPDATE SET amount=excluded.amount, period=excluded.period, status='active'",
            Categorizer.merchantKey(name), name.trim().take(60), amount, period, b.str("next_date") ?: today().toString())
        return engine.subscriptions()
    }

    private fun updateSubscription(id: Long, b: Map<String, Any?>): Any? {
        b.str("status")?.let { if (it !in setOf("active", "suggested", "cancelled", "ignored")) throw ApiError(400, "Unknown status"); db.exec("UPDATE subscriptions SET status=? WHERE id=?", it, id) }
        b.str("period")?.let { if (it !in PERIODS) throw ApiError(400, "Unknown period"); db.exec("UPDATE subscriptions SET period=? WHERE id=?", it, id) }
        b.dbl("amount")?.let { db.exec("UPDATE subscriptions SET amount=? WHERE id=?", it, id) }
        b.str("name")?.let { db.exec("UPDATE subscriptions SET name=? WHERE id=?", it.take(60), id) }
        b.str("next_date")?.let { db.exec("UPDATE subscriptions SET next_date=? WHERE id=?", LocalDate.parse(it).toString(), id) }
        return engine.subscriptions()
    }

    private fun ask(question: String): Map<String, Any?> {
        val q = question.trim().take(500)
        var usedAi = false
        var plan: Map<String, Any?>? = null
        if (aiOn()) try { plan = ai.planQuery(q, engine.allCategories(), today()); usedAi = true } catch (_: Ai.Unavailable) {}
        val pl = plan ?: engine.localPlan(q)
        val r = engine.runPlan(pl)
        return mapOf("answer" to engine.describe(pl, r), "plan" to pl, "used_ai" to usedAi) + r
    }

    private fun aiInsights(month: String): Map<String, Any?> {
        if (!aiOn()) throw ApiError(400, "Turn on AI features in Settings first")
        val payload = mapOf( // aggregated numbers only - no individual transactions or notes
            "month" to engine.monthStats(month), "previous_month" to engine.monthStats(engine.prevMonth(month)),
            "daily_budget" to engine.budgetStatus(), "subscriptions_monthly" to engine.subscriptions()["monthly_total"],
            "suggested_budgets" to engine.recommendations(month))
        return ai.monthlyInsights(toJson(payload).toString())
    }

    // ---------------- CSV ----------------
    private fun csvRow(line: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString(); return out
    }

    private fun importCsv(text: String): Map<String, Any?> {
        if (text.length > 10 * 1024 * 1024) throw ApiError(413, "File is larger than 10 MB")
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) throw ApiError(400, "Empty file")
        val header = csvRow(lines[0]).map { it.trim().lowercase() }
        fun pick(vararg names: String): Int? { for (n in names) header.indexOfFirst { n in it }.takeIf { it >= 0 }?.let { return it }; return null }
        val cDate = pick("date"); val cDesc = pick("narration", "description", "particular", "remark", "details", "merchant")
        val cAmt = pick("amount"); val cDr = pick("debit", "withdrawal"); val cCr = pick("credit", "deposit")
        if (cDate == null || (cAmt == null && cDr == null)) throw ApiError(400, "Could not find date/amount columns in $header")
        val fmts = listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "dd/MM/yy", "dd-MM-yy", "dd-MMM-yyyy", "dd MMM yyyy").map {
            java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(java.util.Locale.ENGLISH)
        }
        fun whenOf(v: String?): LocalDate? { val s = v?.trim() ?: return null; for (f in fmts) try { return LocalDate.parse(s, f) } catch (_: Exception) {}; return null }
        fun num(v: String?) = v?.replace(",", "")?.trim()?.toDoubleOrNull() ?: 0.0
        var added = 0; var skipped = 0
        for (line in lines.drop(1)) {
            val r = csvRow(line)
            val d = whenOf(r.getOrNull(cDate))
            val desc = cDesc?.let { r.getOrNull(it)?.trim() } ?: ""
            val (amount, kind) = if (cDr != null || cCr != null) {
                val dr = cDr?.let { num(r.getOrNull(it)) } ?: 0.0; val cr = cCr?.let { num(r.getOrNull(it)) } ?: 0.0
                if (dr != 0.0) dr to "debit" else cr to "credit"
            } else kotlin.math.abs(num(r.getOrNull(cAmt!!))) to "debit"
            if (d == null || amount == 0.0) { skipped++; continue }
            if (engine.record(d, amount, kind, merchantFromNarration(desc), desc, "csv", raw = "csv|$d|$amount|$desc") != null) added++ else skipped++
        }
        return mapOf("added" to added, "skipped" to skipped)
    }

    private fun merchantFromNarration(desc: String): String {
        val skip = setOf("UPI", "POS", "NEFT", "IMPS", "ACH", "ECOM", "PUR", "TXN", "DR", "CR")
        val parts = desc.split(Regex("[-/*]")).map { it.trim() }.filter { it.isNotEmpty() && !Regex("\\d{3,}|@").containsMatchIn(it) && it.uppercase() !in skip }
        return parts.firstOrNull()?.let { Parser.titleCase(it).take(40) } ?: desc.take(40).ifBlank { "Unknown" }
    }

    private fun exportCsv(): String {
        fun q(v: Any?) = (v?.toString() ?: "").let { if (Regex("[\",\\n]").containsMatchIn(it)) "\"" + it.replace("\"", "\"\"") + "\"" else it }
        val sb = StringBuilder("date,amount,my_share,kind,merchant,category,note,source\n")
        for (r in db.query("SELECT * FROM transactions ORDER BY date"))
            sb.append(listOf("date", "amount", "my_share", "kind", "merchant", "category", "note", "source").joinToString(",") { q(r[it]) }).append('\n')
        return sb.toString()
    }
}
