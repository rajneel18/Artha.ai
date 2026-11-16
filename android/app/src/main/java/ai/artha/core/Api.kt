package ai.artha.core

import java.time.LocalDate
import java.util.Base64

/**
 * The app's API, called by the UI through the WebView bridge and by the background SMS / notification capture.
 * Same routes and JSON shapes as the original web server, so the UI code barely changed.
 */
class Api(val engine: Engine, val model: LocalModel) {
    val extractor = Extractor(model)
    val extras = Extras(engine)
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
    } catch (e: ModelMissing) {
        Response(409, mapOf("detail" to e.message))
    } catch (e: Exception) {
        Response(500, mapOf("detail" to (e.message ?: "Something went wrong")))
    }

    private fun month(q: Map<String, String>) = q["month"]?.also {
        if (!Regex("\\d{4}-\\d{2}").matches(it)) throw ApiError(400, "month must be YYYY-MM")
    } ?: today().toString().take(7)
    private fun id(seg: String) = seg.toLongOrNull() ?: throw ApiError(404, "Not found")
    private fun checkCategory(c: String?) { if (c != null && c !in engine.allCategories()) throw ApiError(400, "Unknown category") }

    private fun route(m: String, path: String, q: Map<String, String>, b: Map<String, Any?>): Any? {
        val p = path.trim('/').removePrefix("api/").split("/")
        return when {
            // ---- capture ----
            m == "POST" && p == listOf("ingest") -> ingest(b.str("text") ?: "", b.str("mode") ?: "note")
            m == "POST" && p == listOf("transactions") -> addTx(b)
            m == "POST" && p == listOf("import", "text") -> importText(b.str("text") ?: "")

            // ---- detected by the on-device AI, waiting for your OK ----
            m == "GET" && p == listOf("detected") -> engine.detected()
            m == "POST" && p == listOf("detected", "log-all") -> mapOf("logged" to engine.logAllDetected())
            m == "POST" && p == listOf("detected", "undo-history") -> mapOf("removed" to engine.undoHistory())
            m == "POST" && p == listOf("detected", "group") -> {
                val action = b.str("action") ?: "log"
                if (action !in setOf("log", "skip")) throw ApiError(400, "Unknown action")
                checkCategory(b.str("category"))
                mapOf("count" to engine.actOnGroup(b.str("key") ?: b.str("party") ?: throw ApiError(400, "Which payee?"), b.str("type") ?: "debit", action,
                    b.str("category"), b.str("name")))
            }
            m == "POST" && p.size == 2 && p[0] == "detected" -> {
                val id = id(p[1])
                when (b.str("action")) {
                    "log" -> { checkCategory(b.str("category")); engine.logDetected(id, b) }
                    "rename" -> { val d = engine.db.one("SELECT upi_id, category FROM detected WHERE id=?", id) ?: throw ApiError(404, "Not found")
                        engine.rememberPayee(d.s("upi_id"), b.str("party") ?: throw ApiError(400, "Name?"), b.str("category") ?: d.s("category") ?: "Other"); mapOf("ok" to true) }
                    "skip" -> { engine.skipDetected(id); mapOf("ok" to true) }
                    "purpose" -> engine.logWithPurpose(id, b.str("purpose") ?: throw ApiError(400, "What was it for?"), extractor)
                    else -> throw ApiError(400, "Unknown action")
                }
            }

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
            m == "GET" && p == listOf("settings") -> engine.settings().apply { this["model_ready"] = model.available() }
            m == "PUT" && p == listOf("settings") -> putSettings(b)
            m == "GET" && p == listOf("budget") -> engine.budgetStatus()
            m == "GET" && p == listOf("daily") -> engine.daily(q["date"]?.let { LocalDate.parse(it) } ?: today())
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
                    "recommendations" to engine.recommendations(mo))
            }
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
            // Quick Add: read several payments from a note without saving, so you can review them first.
            m == "POST" && p == listOf("parse") -> extractor.fromNote(b.str("text") ?: throw ApiError(400, "Type something first"), engine.allCategories(), engine.learnedMap(), today())
                .filter { it.type in setOf("debit", "credit") }.map { mapOf("amount" to it.amount, "party" to it.party, "category" to it.category, "date" to (it.date ?: today()).toString(), "type" to it.type) }
            // ---- streaks, envelopes, pace, groups ----
            m == "GET" && p == listOf("streak") -> extras.streak()
            m == "POST" && p == listOf("streak", "zero") -> extras.markZeroDay()
            m == "GET" && p == listOf("pace") -> extras.pace()
            m == "GET" && p == listOf("envelopes") -> extras.envelopes(q["archived"] == "1")
            m == "POST" && p == listOf("envelopes") -> extras.createEnvelope(b.str("name") ?: "", b.dbl("budget") ?: 0.0, b.str("end_date"))
            m == "DELETE" && p.size == 2 && p[0] == "envelopes" -> { extras.deleteEnvelope(id(p[1])); mapOf("ok" to true) }
            m == "POST" && p.size == 3 && p[0] == "transactions" && p[2] == "envelope" -> { extras.linkEnvelope(id(p[1]), b.dbl("envelope_id")?.toLong()); engine.row(id(p[1])) }
            m == "GET" && p == listOf("friends") -> extras.friends()
            m == "GET" && p == listOf("groups") -> extras.groups()
            m == "POST" && p == listOf("groups") -> extras.createGroup(b.str("name") ?: "", b.strList("members") ?: emptyList())
            m == "DELETE" && p.size == 2 && p[0] == "groups" -> { extras.deleteGroup(id(p[1])); mapOf("ok" to true) }
            m == "POST" && p == listOf("splits", "new") -> extras.newSplit(b.dbl("amount") ?: 0.0, b.str("what") ?: "", b.strList("people") ?: emptyList(), b.str("category"))
            else -> throw ApiError(404, "Not found")
        }
    }

    // ---------------- capture ----------------
    private fun splitMessages(text: String): List<String> {
        val t = text.trim()
        return if (Regex("\\n\\s*\\n").containsMatchIn(t)) t.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        else t.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Typed or spoken notes are read by the AI and logged right away (you wrote them, so no confirmation needed).
     * A pasted bank SMS goes to the Detected list like any other SMS.
     */
    fun ingest(text: String, mode: String): Map<String, Any?> {
        if (text.isBlank()) throw ApiError(400, "Type or say a payment first")
        if (text.length > 5_000) throw ApiError(413, "That's too long. Use Import statement for big lists")
        if (mode == "sms") {
            val queued = engine.enqueue(text, "sms", java.time.LocalDateTime.now())
            while (engine.analyzeNext(extractor)) {}
            return mapOf("queued" to queued, "detected" to engine.detected())
        }
        val cats = engine.allCategories()
        val added = extractor.fromNote(text, cats, engine.learnedMap(), today()).filter { it.type in setOf("debit", "credit") }.mapNotNull { e ->
            engine.record(engine.clampDate(e.date ?: today()), e.amount, e.type, e.party, text, "text",
                e.category.takeIf { it != "Other" }, splitWith = e.splitWith, splitWays = e.splitWays, confident = e.confident)
        }
        return mapOf("added" to added)
    }

    /** Background capture from a bank SMS: queue it for the AI. Returns true if it was new. */
    fun captureSms(body: String, sender: String?, at: java.time.LocalDateTime, history: Boolean = false): Boolean =
        engine.enqueue(body, if (history) "history" else "sms", at, sender)

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
        b.dbl("monthly_budget")?.let { if (it !in 0.0..10_000_000.0) throw ApiError(400, "Budget looks wrong"); clean["monthly_budget"] = round2(it) }
        b.str("user_name")?.let { clean["user_name"] = it.trim().take(30) }
        b.str("theme")?.takeIf { it in setOf("system", "light", "dark") }?.let { clean["theme"] = it }
        @Suppress("UNCHECKED_CAST") (b["category_limits"] as? Map<String, Any?>)?.let { m ->
            clean["category_limits"] = m.mapValues { (it.value as? Number)?.toDouble() ?: 0.0 }.filter { it.value > 0 } }
        b.dbl("reminder_hour")?.let { clean["reminder_hour"] = it.toLong().coerceIn(0, 23) }
        b.dbl("savings_goal_pct")?.let { clean["savings_goal_pct"] = it.toLong().coerceIn(0, 90) }
        b.str("budget_start")?.let { clean["budget_start"] = LocalDate.parse(it).toString() }
        for (k in listOf("reminder_on", "auto_capture_sms", "auto_log_confident", "auto_log_history", "track_received", "notify_logged", "app_lock", "hide_in_recents"))
            b.bool(k)?.let { clean[k] = it }
        engine.ensureStarted()
        return engine.setSettings(clean).apply { this["model_ready"] = model.available() }
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
        if (model.available()) try { plan = extractor.plan(q, engine.allCategories(), today()); usedAi = plan != null } catch (_: Exception) {}
        val pl = plan ?: engine.localPlan(q)
        val r = engine.runPlan(pl)
        return mapOf("answer" to engine.describe(pl, r), "plan" to pl, "used_ai" to usedAi) + r
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

    /**
     * Imports a statement or any list of transactions (CSV or plain text). Nothing is parsed by hand:
     * each row is queued for the on-device AI, which reads it and suggests a category. Results show up
     * in the Detected list where you can log them one by one or all at once.
     */
    private fun importText(text: String): Map<String, Any?> {
        if (text.length > 10 * 1024 * 1024) throw ApiError(413, "File is larger than 10 MB")
        val lines = text.removePrefix("\uFEFF").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw ApiError(400, "Empty file")
        val now = java.time.LocalDateTime.now()
        // A CSV-style file: first line is the header, the AI sees it with every row.
        val tabular = lines.size > 1 && lines.take(5).all { it.count { c -> c == ',' || c == '\t' || c == ';' } >= 2 }
        var queued = 0
        if (tabular) for (row in lines.drop(1)) { if (engine.enqueue(row, "import", now, header = lines[0])) queued++ }
        else for (block in text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }.flatMap { b -> if (b.lines().size > 3) b.lines() else listOf(b) }) {
            if (engine.enqueue(block.trim(), "import", now, header = "")) queued++
        }
        return mapOf("queued" to queued, "pending" to engine.pendingCount())
    }

    private fun exportCsv(): String {
        fun q(v: Any?) = (v?.toString() ?: "").let { if (Regex("[\",\\n]").containsMatchIn(it)) "\"" + it.replace("\"", "\"\"") + "\"" else it }
        val sb = StringBuilder("date,amount,my_share,kind,merchant,category,note,source\n")
        for (r in db.query("SELECT * FROM transactions ORDER BY date"))
            sb.append(listOf("date", "amount", "my_share", "kind", "merchant", "category", "note", "source").joinToString(",") { q(r[it]) }).append('\n')
        return sb.toString()
    }
}
