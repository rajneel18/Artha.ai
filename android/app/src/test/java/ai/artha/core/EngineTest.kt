package ai.artha.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.time.LocalDate

/** SQLite via JDBC so the exact same engine code runs in plain JVM tests. */
class JdbcDb : Db {
    private val c: Connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
        createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
    }
    private var depth = 0
    private fun bind(sql: String, args: Array<out Any?>, keys: Boolean = false) =
        (if (keys) c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS) else c.prepareStatement(sql)).apply {
            args.forEachIndexed { i, a -> setObject(i + 1, if (a is Boolean) (if (a) 1L else 0L) else a) }
        }
    override fun query(sql: String, vararg args: Any?): List<Row> = bind(sql, args).use { st ->
        st.executeQuery().use { rs ->
            val md = rs.metaData; val out = mutableListOf<Row>()
            while (rs.next()) out += (1..md.columnCount).associate { i ->
                md.getColumnLabel(i) to when (val v = rs.getObject(i)) { is Int -> v.toLong(); is Float -> v.toDouble(); else -> v }
            }
            out
        }
    }
    override fun exec(sql: String, vararg args: Any?) { bind(sql, args).use { it.executeUpdate() } }
    override fun insert(sql: String, vararg args: Any?): Long = try {
        bind(sql, args, true).use { st -> st.executeUpdate(); st.generatedKeys.use { if (it.next()) it.getLong(1) else -1 } }
    } catch (e: java.sql.SQLException) { if ("UNIQUE" in (e.message ?: "")) -1 else throw e }
    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) { depth++; try { return block() } finally { depth-- } }
        c.autoCommit = false; depth = 1
        try { val r = block(); c.commit(); return r } catch (e: Throwable) { c.rollback(); throw e } finally { depth = 0; c.autoCommit = true }
    }
    override fun vacuum() { c.createStatement().use { it.execute("VACUUM") } }
}

/** Stands in for Qwen: answers with canned JSON keyed by a phrase in the message, and counts calls. */
class FakeModel(var ready: Boolean = true) : LocalModel {
    val answers = mutableMapOf<String, String>()
    var calls = 0
    override fun available() = ready
    override fun generate(prompt: String): String {
        calls++
        val tail = prompt.substringAfterLast("\nSMS: ").substringAfterLast("\nNote: ").substringAfterLast("\nRow: ").substringAfterLast("\nQuestion: ")
        return answers.entries.firstOrNull { it.key in tail }?.value ?: """{"type": "ignore", "amount": 0, "party": "", "category": "Other"}"""
    }
}

@Suppress("UNCHECKED_CAST")
class EngineTest {
    private val today = LocalDate.of(2026, 11, 14)
    private val now = java.time.LocalDateTime.of(2026, 11, 14, 17, 46)
    private lateinit var engine: Engine
    private lateinit var model: FakeModel
    private lateinit var api: Api

    private val SARASWAT_DEBIT = "Your a/c no. XX6196 is debited for Rs.1.00 on 14-11-2026 17:46:28 and credited to vpa shravani.shinde109-1@okicici (UPI Ref no 631826724674) Your Current Balance is INR 84413.27. If this not done by you, kindly contact on  022-41572070 - Saraswat Co-op Bank Ltd."
    private val NETFLIX_MANDATE = "UPI AutoPay Mandate for Netflix debit of Rs.499.00 is scheduled on 30-10-2026. Please ensure sufficient balance in your account - Saraswat Co-op Bank Ltd."
    private val OTP = "Dear Customer, 482913 is your OTP for transaction of Rs.2,000.00 at AMAZON. Do not share it - Saraswat Bank"

    @Before fun setUp() {
        engine = Engine(JdbcDb()) { today }
        model = FakeModel()
        api = Api(engine, model)
        model.answers["shravani"] = """{"type": "debit", "amount": 1.0, "party": "Shravani Shinde", "category": "Transfers", "confidence": "high"}"""
        model.answers["Netflix debit"] = """{"type": "debit", "amount": 499, "party": "Netflix", "category": "Entertainment"}""" // model gets it wrong
        model.answers["OTP"] = """{"type": "debit", "amount": 2000, "party": "Amazon", "category": "Shopping"}""" // model gets it wrong
    }

    private fun call(m: String, path: String, body: Any? = null): Any? {
        val r = api.handle(m, path, body?.let { toJson(it).toString() })
        assertEquals("$m $path -> ${r.body}", 200, r.status)
        return fromJson(toJson(r.body))
    }
    private fun status(m: String, path: String, body: Any? = null) = api.handle(m, path, body?.let { toJson(it).toString() }).status
    private fun detected() = call("GET", "/api/detected") as Map<String, Any?>
    private fun items() = detected()["items"] as List<Map<String, Any?>>
    private fun txs() = call("GET", "/api/transactions") as List<Map<String, Any?>>
    private fun receive(sms: String) { api.captureSms(sms, "AX-SARBNK", now); while (engine.analyzeNext(api.extractor)) {} }

    @Test fun smsIsReadByAiAndWaitsForConfirmation() {
        receive(SARASWAT_DEBIT)
        val it = items().single()
        assertEquals("debit", it["type"]); assertEquals(1.0, it["amount"] as Double, 0.0); assertEquals("Shravani Shinde", it["party"])
        assertEquals(1L, it["confident"])
        assertTrue("nothing is logged until you confirm", txs().isEmpty())
        call("POST", "/api/detected/${it["id"]}", mapOf("action" to "log"))
        val tx = txs().single()
        assertEquals("Shravani Shinde", tx["merchant"]); assertEquals("2026-11-14", tx["date"]); assertEquals("Transfers", tx["category"])
        assertFalse("balance is not stored", (tx["note"] as String).contains("84413"))
    }

    @Test fun safetyCheckOverridesTheModel() {
        receive(OTP)
        receive(NETFLIX_MANDATE)
        val d = detected()
        assertEquals(1, (d["skipped"] as List<*>).size)                         // OTP: never a payment
        val upcoming = (d["items"] as List<Map<String, Any?>>).single()
        assertEquals("upcoming", upcoming["type"]); assertEquals("Netflix", upcoming["party"])
        assertEquals(1, model.calls)                                             // the OTP never even reached the model
        call("POST", "/api/detected/${upcoming["id"]}", mapOf("action" to "log"))
        assertTrue("an upcoming mandate is not spending", txs().isEmpty())
        val subs = (call("GET", "/api/subscriptions") as Map<String, Any?>)["subscriptions"] as List<Map<String, Any?>>
        assertEquals("Netflix", subs.single()["name"]); assertEquals(499.0, subs.single()["amount"] as Double, 0.0)
    }

    @Test fun inventedAmountsAreNotTrusted() {
        model.answers["swiggy"] = """{"type": "debit", "amount": 950, "party": "Swiggy", "category": "Food & Dining"}"""
        receive("Rs.250.00 debited from A/c XX1 to VPA swiggy@icici UPI Ref 9")
        assertEquals(0L, items().single()["confident"])
    }

    @Test fun logAllAndSkip() {
        model.answers["zomato"] = """{"type": "debit", "amount": 349, "party": "Zomato", "category": "Food & Dining"}"""
        model.answers["uber"] = """{"type": "debit", "amount": 120, "party": "Uber", "category": "Transport"}"""
        receive(SARASWAT_DEBIT); receive("Rs.349 spent at zomato Ref 1"); receive("Rs.120 paid to uber Ref 2")
        call("POST", "/api/detected/${items().first { it["party"] == "Uber" }["id"]}", mapOf("action" to "skip"))
        assertEquals(2L, (call("POST", "/api/detected/log-all") as Map<String, Any?>)["logged"])
        assertEquals(setOf("Shravani Shinde", "Zomato"), txs().map { it["merchant"] }.toSet())
        assertTrue(items().isEmpty())
        receive("Rs.349 spent at zomato Ref 1")                                   // the same SMS again is not queued twice
        assertTrue(items().isEmpty())
    }

    @Test fun yourCorrectionsBeatTheModel() {
        model.answers["ramesh"] = """{"type": "debit", "amount": 400, "party": "Ramesh Stores", "category": "Shopping"}"""
        receive("Rs.400 paid to ramesh stores Ref 1")
        call("POST", "/api/detected/${items().single()["id"]}", mapOf("action" to "log", "category" to "Groceries"))
        receive("Rs.400 paid to ramesh stores Ref 2")
        assertEquals("Groceries", items().single()["category"])
    }

    @Test fun notesAreLoggedDirectlyWithSplits() {
        model.answers["coffee"] = """{"items": [{"type": "debit", "amount": 450, "party": "Third Wave Coffee", "date": "2026-11-14", "category": "Food & Dining", "split_with": ["Asha"], "split_ways": 2, "confidence": "high"}]}"""
        val r = call("POST", "/api/ingest", mapOf("text" to "450 coffee with asha, split it")) as Map<String, Any?>
        val tx = (r["added"] as List<Map<String, Any?>>).single()
        assertEquals(225.0, tx["my_share"] as Double, 0.0)
        assertEquals(listOf("Asha"), (call("GET", "/api/splits") as List<Map<String, Any?>>).map { it["person"] })
    }

    @Test fun statementImportGoesThroughTheQueue() {
        model.answers["BLINKIT"] = """{"type": "debit", "amount": 320, "party": "Blinkit", "date": "2026-11-10", "category": "Groceries"}"""
        model.answers["SALARY"] = """{"type": "credit", "amount": 50000, "party": "Acme Salary", "date": "2026-11-11", "category": "Other"}"""
        val r = call("POST", "/api/import/text", mapOf("text" to "Date,Narration,Withdrawal Amt,Deposit Amt\n10/11/2026,UPI-BLINKIT-123,320.00,\n11/11/2026,SALARY,,50000\n")) as Map<String, Any?>
        assertEquals(2L, r["queued"])
        while (engine.analyzeNext(api.extractor)) {}
        call("POST", "/api/detected/log-all")
        val byName = txs().associateBy { it["merchant"] }
        assertEquals("2026-11-10", byName["Blinkit"]!!["date"]); assertEquals("credit", byName["Acme Salary"]!!["kind"])
    }

    @Test fun withoutTheModelMessagesWait() {
        model.ready = false
        api.captureSms(SARASWAT_DEBIT, "AX-SARBNK", now)
        assertEquals(1L, engine.pendingCount())
        assertEquals(409, status("POST", "/api/ingest", mapOf("text" to "250 swiggy")))
        model.ready = true
        while (engine.analyzeNext(api.extractor)) {}
        assertEquals(0L, engine.pendingCount()); assertEquals(1, items().size)
    }

    @Test fun autoLogConfidentWhenTurnedOn() {
        call("PUT", "/api/settings", mapOf("auto_log_confident" to true))
        receive(SARASWAT_DEBIT); receive(NETFLIX_MANDATE)
        assertEquals(1, txs().size)                      // confident debit logged automatically
        assertEquals("upcoming", items().single()["type"]) // mandates still wait for you
    }

    @Test fun importedHistoryIsGroupedByPayee() {
        model.answers["swiggy"] = """{"type": "debit", "amount": 250, "party": "Swiggy", "category": "Food & Dining"}"""
        model.answers["kirana"] = """{"type": "debit", "amount": 120, "party": "Ramesh Kirana", "category": "Other"}"""
        for (i in 1..5) api.captureSms("Rs.250 paid to swiggy Ref $i", "AX-HDFCBK", now.minusDays(i.toLong()), history = true)
        for (i in 1..3) api.captureSms("Rs.120 paid to kirana Ref $i", "AX-HDFCBK", now.minusDays(i.toLong()), history = true)
        while (engine.analyzeNext(api.extractor)) {}
        val d = detected()
        assertTrue("history doesn't flood the one-by-one list", (d["items"] as List<*>).isEmpty())
        val groups = d["groups"] as List<Map<String, Any?>>
        assertEquals(listOf("Swiggy" to 5L, "Ramesh Kirana" to 3L), groups.map { it["party"] to it["count"] })
        call("POST", "/api/detected/group", mapOf("party" to "Ramesh Kirana", "type" to "debit", "action" to "log", "category" to "Groceries"))
        assertEquals(3, txs().count { it["category"] == "Groceries" })
        // the choice is remembered for future SMS from that payee
        receive("Rs.120 paid to kirana Ref 99")
        assertEquals("Groceries", items().single()["category"])
        call("POST", "/api/detected/log-all")
        assertEquals(9, txs().size)
        assertTrue("SMS repeats are real payments, not double charges", (call("GET", "/api/review") as List<*>).isEmpty())
    }

    @Test fun answerWhatWasItFor() {
        receive(SARASWAT_DEBIT)
        model.answers["Purpose: lunch"] = """{"category": "Food & Dining"}"""
        val tx = call("POST", "/api/detected/${items().single()["id"]}", mapOf("action" to "purpose", "purpose" to "lunch")) as Map<String, Any?>
        assertEquals("Food & Dining", tx["category"]); assertTrue((tx["note"] as String).startsWith("lunch"))
        // answering again re-categorizes the same payment instead of logging it twice
        model.answers["Purpose: gift"] = """{"category": "Shopping"}"""
        val id = (engine.db.one("SELECT id FROM detected")!!["id"] as Long)
        call("POST", "/api/detected/$id", mapOf("action" to "purpose", "purpose" to "gift"))
        assertEquals(listOf("Shopping"), txs().map { it["category"] })
    }

    @Test fun allowanceAndSavingsPool() {
        call("PUT", "/api/settings", mapOf("daily_allowance" to 1000, "budget_start" to "2026-11-10"))
        call("POST", "/api/transactions", mapOf("amount" to 400, "merchant" to "Swiggy", "date" to "2026-11-10"))
        call("POST", "/api/transactions", mapOf("amount" to 1500, "merchant" to "Zara", "date" to "2026-11-11"))
        call("POST", "/api/transactions", mapOf("amount" to 20000, "merchant" to "Landlord", "category" to "Rent", "date" to "2026-11-11"))
        call("POST", "/api/transactions", mapOf("amount" to 300, "merchant" to "Chai", "category" to "Food & Dining"))
        val b = call("GET", "/api/budget") as Map<String, Any?>
        assertEquals(4 * 1000 - 1900.0, b["savings_pool"] as Double, 0.001)
        assertEquals(700.0, b["left_today"] as Double, 0.001)
    }

    @Test fun recurringAndCancelledSubscriptions() {
        for (d in listOf("2026-08-05", "2026-09-05", "2026-10-05")) call("POST", "/api/transactions", mapOf("amount" to 649, "merchant" to "Spotify", "date" to d, "category" to "Entertainment"))
        val subs = (call("GET", "/api/subscriptions") as Map<String, Any?>)["subscriptions"] as List<Map<String, Any?>>
        val s = subs.single(); assertEquals("active", s["status"]); assertEquals("2026-11-05", s["next_date"])
        call("PATCH", "/api/subscriptions/${s["id"]}", mapOf("status" to "cancelled"))
        call("POST", "/api/transactions", mapOf("amount" to 649, "merchant" to "Spotify", "date" to "2026-11-05", "category" to "Entertainment"))
        assertTrue((call("GET", "/api/review") as List<Map<String, Any?>>).any { it["review_reason"] == "cancelled_subscription" })
    }

    @Test fun customRulesAndCategories() {
        call("PUT", "/api/settings", mapOf("custom_categories" to listOf("Pets")))
        call("POST", "/api/rules", mapOf("pattern" to "tails", "category" to "Pets"))
        model.answers["tails"] = """{"type": "debit", "amount": 1200, "party": "Heads Up For Tails", "category": "Shopping"}"""
        receive("Rs.1200 spent at heads up for tails Ref 5")
        assertEquals("Pets", items().single()["category"])
    }

    @Test fun mostlyOfflineQuestions() {
        call("POST", "/api/transactions", mapOf("amount" to 250, "merchant" to "Swiggy", "category" to "Food & Dining"))
        call("POST", "/api/transactions", mapOf("amount" to 400, "merchant" to "Zomato", "category" to "Food & Dining"))
        model.answers["food"] = """{"date_from": "2026-11-01", "date_to": "2026-11-14", "categories": ["Food & Dining"], "party": "", "kind": "debit", "min_amount": 0, "max_amount": 0, "aggregate": "sum"}"""
        val r = call("POST", "/api/ask", mapOf("question" to "how much on food this month")) as Map<String, Any?>
        assertEquals(650.0, r["total"] as Double, 0.001); assertEquals(true, r["used_ai"])
        val h = call("GET", "/api/merchants/history?name=Swiggy") as Map<String, Any?>
        assertEquals(250.0, h["total"] as Double, 0.001)
    }

    @Test fun userControlledDeletion() {
        call("POST", "/api/transactions", mapOf("amount" to 250, "merchant" to "Swiggy"))
        receive(SARASWAT_DEBIT)
        assertEquals(400, status("POST", "/api/data/delete", mapOf("scope" to "everything", "confirm" to "nope")))
        call("POST", "/api/data/delete", mapOf("scope" to "everything", "confirm" to "DELETE"))
        assertTrue(txs().isEmpty()); assertTrue(items().isEmpty())
    }
}

@Suppress("UNCHECKED_CAST")
class BackupTest {
    @Test fun backupRestoresIntoAFreshInstall() {
        val a = Api(Engine(JdbcDb()) { LocalDate.of(2026, 11, 14) }, FakeModel())
        val t = a.handle("POST", "/api/transactions", toJson(mapOf("amount" to 900, "merchant" to "Toit")).toString()).body as Map<String, Any?>
        a.handle("POST", "/api/transactions/${t["id"]}/split", toJson(mapOf("people" to listOf("Rahul", "Priya"))).toString())
        a.handle("POST", "/api/transactions", toJson(mapOf("amount" to 250, "merchant" to "Swiggy")).toString())
        a.handle("PUT", "/api/settings", toJson(mapOf("daily_allowance" to 777)).toString())
        val backup = (a.handle("GET", "/api/export.json", null).body as Map<String, Any?>)["content"] as String

        val b = Api(Engine(JdbcDb()) { LocalDate.of(2026, 11, 14) }, FakeModel())
        assertEquals(400, b.handle("POST", "/api/data/restore", toJson(mapOf("content" to backup, "confirm" to "no")).toString()).status)
        val r = b.handle("POST", "/api/data/restore", toJson(mapOf("content" to backup, "confirm" to "RESTORE")).toString())
        assertEquals(200, r.status); assertEquals(2L, (r.body as Map<String, Any?>)["transactions"])
        assertEquals(2, (b.handle("GET", "/api/splits", null).body as List<*>).size)
        assertEquals(777.0, (b.handle("GET", "/api/settings", null).body as Map<String, Any?>)["daily_allowance"] as Double, 0.0)
    }
}
