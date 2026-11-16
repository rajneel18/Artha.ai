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
    var lastPrompt = ""
    override fun available() = ready
    override fun generate(prompt: String): String {
        calls++; lastPrompt = prompt
        val tail = prompt.substringAfterLast("\nSMS: ").substringAfterLast("\nNote: ").substringAfterLast("\nRow: ").substringAfterLast("\nQuestion: ")
        answers.entries.firstOrNull { it.key in tail }?.let { return it.value }
        // The short "which category" question: answer with the category of a matching canned answer.
        if ("Pick the spending category" in prompt) answers.entries.firstOrNull { it.key.lowercase() in prompt.lowercase() }?.let { a ->
            Regex("\"category\":\\s*\"([^\"]+)\"").find(a.value)?.let { return "{\"category\": \"${it.groupValues[1]}\"}" } }
        return """{"type": "ignore", "amount": 0, "party": "", "category": "Other"}"""
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

    @Test fun bankNameOrRawIdIsNeverThePayee() {
        model.answers["shravani"] = """{"type": "debit", "amount": 1.0, "party": "Saraswat Co-op Bank Ltd.", "category": "Transfers"}"""
        receive(SARASWAT_DEBIT)
        assertEquals("Shravani Shinde", items().single()["party"])
        assertFalse("the bank signature never reaches the model", model.lastPrompt.contains("Saraswat Co-op Bank Ltd"))
        model.answers["meera"] = """{"type": "debit", "amount": 75, "party": "meera.k12", "category": "Transfers"}"""
        receive("Your a/c XX6196 is debited for Rs.75.00 on 13-11-2026 and credited to vpa meera.kulkarni12@okaxis - Saraswat Co-op Bank Ltd.")
        assertEquals("Meera Kulkarni", items().first { it["amount"] == 75.0 }["party"])
    }

    private val QR_115 = "Your a/c no. XX6196 is debited for Rs.115.00 on 12-11-2026 17:13:52 and credited to vpa bhqr.2933317A@sib (UPI Ref no 631644270678) Your Current Balance is INR 85570.27. If this not done by you, kindly contact on  022-41572070 - Saraswat Co-op Bank Ltd."
    private val PHONE_1 = "Your a/c no. XX6196 is debited for Rs.1.00 on 16-11-2026 13:59:38 and credited to vpa 8380081708@upi (UPI Ref no 632017674849) Your Current Balance is INR 84574.27. If this not done by you, kindly contact on  022-41572070 - Saraswat Co-op Bank Ltd."
    private val PAYTM_95 = "Your a/c no. XX6196 is debited for Rs.95.00 on 13-11-2026 13:45:35 and credited to vpa paytmqr6vyrxd@ptys (UPI Ref no 631756578767) Your Current Balance is INR 84729.27. If this not done by you, kindly contact on  022-41572070 - Saraswat Co-op Bank Ltd."

    @Test fun unnamedQrAndNumbersAreLabelledThenRemembered() {
        model.answers["bhqr"] = """{"type": "debit", "amount": 115, "party": "Bhqr", "category": "Shopping"}"""
        model.answers["8380081708"] = """{"type": "debit", "amount": 1, "party": "UPI", "category": "Transfers"}"""
        model.answers["paytmqr"] = """{"type": "debit", "amount": 95, "party": "Paytm", "category": "Shopping"}"""
        receive(QR_115); receive(PHONE_1); receive(PAYTM_95)
        val byAmount = items().associateBy { it["amount"] }
        assertEquals("BharatPe QR shop", byAmount[115.0]!!["party"]); assertEquals(0L, byAmount[115.0]!!["confident"])
        assertEquals("UPI 83800 81708", byAmount[1.0]!!["party"])
        assertEquals("Paytm QR shop", byAmount[95.0]!!["party"])
        // name the canteen QR once
        call("POST", "/api/detected/${byAmount[115.0]!!["id"]}", mapOf("action" to "log", "party" to "College Canteen", "category" to "Food & Dining"))
        // next payment to the same QR: named and categorized without the AI
        val before = model.calls
        receive(QR_115.replace("115.00", "60.00").replace("631644270678", "631644270999"))
        assertEquals("the AI isn't used for a known payee", before, model.calls)
        val next = items().first { it["amount"] == 60.0 }
        assertEquals("College Canteen", next["party"]); assertEquals("Food & Dining", next["category"]); assertEquals(1L, next["confident"])
        // "Mom, gift" in the notification reply names the number and logs it
        model.answers["Purpose: gift"] = """{"category": "Shopping"}"""
        val tx = call("POST", "/api/detected/${byAmount[1.0]!!["id"]}", mapOf("action" to "purpose", "purpose" to "Mom, gift")) as Map<String, Any?>
        assertEquals("Mom", tx["merchant"]); assertEquals("Shopping", tx["category"])
        assertEquals(setOf("bhqr.2933317a@sib", "8380081708@upi"), engine.payees().map { it["upi_id"] }.toSet())
    }

    @Test fun historyGroupsUnnamedQrsSeparately() {
        model.answers["bhqr"] = """{"type": "debit", "amount": 115, "party": "Bhqr", "category": "Food & Dining"}"""
        model.answers["paytmqr"] = """{"type": "debit", "amount": 95, "party": "Shop", "category": "Food & Dining"}"""
        model.answers["paytmqrzz"] = """{"type": "debit", "amount": 95, "party": "Shop", "category": "Food & Dining"}"""
        api.captureSms(QR_115, "AX-SARBNK", now.minusDays(3), history = true)
        api.captureSms(PAYTM_95, "AX-SARBNK", now.minusDays(2), history = true)
        api.captureSms(PAYTM_95.replace("paytmqr6vyrxd", "paytmqrzz1abc").replace("631756578767", "1"), "AX-SARBNK", now.minusDays(1), history = true)
        while (engine.analyzeNext(api.extractor)) {}
        val groups = detected()["groups"] as List<Map<String, Any?>>
        assertEquals("two different Paytm QRs aren't lumped together", 3, groups.size)
        val g = groups.first { it["upi_id"] == "paytmqr6vyrxd@ptys" }
        call("POST", "/api/detected/group", mapOf("key" to g["gkey"], "type" to "debit", "action" to "log", "name" to "Chai Point", "category" to "Food & Dining"))
        assertEquals(listOf("Chai Point"), txs().map { it["merchant"] })
    }

    @Test fun safetyCheckOverridesTheModel() {
        receive(OTP)
        receive(NETFLIX_MANDATE)
        val d = detected()
        assertEquals(1, (d["skipped"] as List<*>).size)                         // OTP: never a payment
        val upcoming = (d["items"] as List<Map<String, Any?>>).single()
        assertEquals("upcoming", upcoming["type"]); assertEquals("Netflix", upcoming["party"])
        assertEquals(0, model.calls)                                             // OTP never reaches the model; "Netflix" is a known word
        call("POST", "/api/detected/${upcoming["id"]}", mapOf("action" to "log"))
        assertTrue("an upcoming mandate is not spending", txs().isEmpty())
        val subs = (call("GET", "/api/subscriptions") as Map<String, Any?>)["subscriptions"] as List<Map<String, Any?>>
        assertEquals("Netflix", subs.single()["name"]); assertEquals(499.0, subs.single()["amount"] as Double, 0.0)
    }

    @Test fun inventedAmountsAreNotTrusted() {
        model.answers["swiggy"] = """{"type": "debit", "amount": 950, "party": "Swiggy", "category": "Food & Dining"}"""
        receive("Rs.250.00 debited from A/c XX1 to VPA swiggy@icici UPI Ref 9")
        // The pattern reader takes the amount straight from the SMS, so the model's invented 950 never gets in.
        assertEquals(250.0, items().single()["amount"] as Double, 0.0); assertEquals("Swiggy", items().single()["party"])
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
        call("PUT", "/api/settings", mapOf("track_received" to true))
        model.answers["BLINKIT"] = """{"type": "debit", "amount": 320, "party": "Blinkit", "date": "2026-11-10", "category": "Groceries"}"""
        model.answers["SALARY"] = """{"type": "credit", "amount": 50000, "party": "Acme Salary", "date": "2026-11-11", "category": "Other"}"""
        val r = call("POST", "/api/import/text", mapOf("text" to "Date,Narration,Withdrawal Amt,Deposit Amt\n10/11/2026,UPI-BLINKIT-123,320.00,\n11/11/2026,SALARY,,50000\n")) as Map<String, Any?>
        assertEquals(2L, r["queued"])
        while (engine.analyzeNext(api.extractor)) {}
        call("POST", "/api/detected/log-all")
        val byName = txs().associateBy { it["merchant"] }
        assertEquals(2, byName.size)
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

    @Test fun importedHistoryLogsConfidentAndGroupsTheRest() {
        model.answers["swiggy"] = """{"type": "debit", "amount": 250, "party": "Swiggy", "category": "Food & Dining"}"""
        model.answers["ramesh"] = """{"type": "debit", "amount": 120, "party": "Ramesh Traders", "category": "Other"}"""
        model.answers["account"] = """{"type": "debit", "amount": 90, "party": "Your Account", "category": "Transfers"}"""
        for (i in 1..5) api.captureSms("Rs.250 paid to swiggy Ref $i", "AX-HDFCBK", now.minusDays(i.toLong()), history = true)
        for (i in 1..3) api.captureSms("Rs.120 paid to ramesh Ref $i", "AX-HDFCBK", now.minusDays(i.toLong()), history = true)
        api.captureSms("Rs.90 debited from your account Ref 7", "AX-HDFCBK", now.minusDays(2), history = true)
        while (engine.analyzeNext(api.extractor)) {}
        val d = detected()
        assertEquals("confident payments are logged without asking", 5L, d["history_logged"])
        val groups = d["groups"] as List<Map<String, Any?>>
        assertEquals(setOf("Ramesh" to 3L, "Unknown" to 1L), groups.map { it["party"] to it["count"] }.toSet())
        assertTrue("each group shows its messages", (groups.first()["samples"] as List<*>).isNotEmpty())
        call("POST", "/api/detected/group", mapOf("party" to "Ramesh", "type" to "debit", "action" to "log", "category" to "Groceries"))
        receive("Rs.120 paid to ramesh Ref 99")
        assertEquals("Groceries", items().single()["category"])
        assertEquals(5L, (call("POST", "/api/detected/undo-history") as Map<String, Any?>)["removed"].let { (it as Long) - 3 })
    }

    @Test fun receivedMoneyIsHiddenByDefault() {
        receive("Your a/c no. XX6196 is credited by Rs.2,500.00 from vpa rahul.patil22@oksbi (UPI Ref no 6318) - Saraswat Co-op Bank Ltd.")
        assertTrue(items().isEmpty()); assertEquals(0, model.calls)
        receive("Rs 13,428.00 blocked in your a/c XX6196 for IPO of Jio Platforms Ltd - Saraswat Co-op Bank Ltd.")
        assertTrue("IPO blocks aren't spending or income", items().isEmpty())
    }

    @Test fun everydayWordsPickTheCategoryWithoutTheAi() {
        assertEquals("Snacks", Categorizer.hint("Chai"))
        assertEquals("Fuel", Categorizer.hint("petrol"))
        assertEquals("Transfers", Categorizer.hint("Mom"))
        assertEquals("Groceries", Categorizer.hint("sabzi from market"))
        assertEquals(null, Categorizer.hint("xyz"))
        assertTrue(needsName("Bhqr")); assertTrue(needsName("UPI")); assertTrue(needsName("Unknown")); assertTrue(needsName("Paytm QR shop"))
        assertTrue(!needsName("Mom")); assertTrue(!needsName("College Canteen")); assertTrue(!needsName("Shravani Shinde"))
    }

    @Test fun replyNamesAnUnnamedPayeeAndPicksSnacks() {
        model.answers["bhqr"] = """{"type": "debit", "amount": 115, "party": "Bhqr", "category": "Shopping"}"""
        receive(QR_115)
        val id = engine.db.one("SELECT id FROM detected")!!["id"] as Long
        val before = model.calls
        val tx = call("POST", "/api/detected/$id", mapOf("action" to "purpose", "purpose" to "Chai")) as Map<String, Any?>
        assertEquals("Chai", tx["merchant"]); assertEquals("Snacks", tx["category"])
        assertEquals("no AI needed for chai", before, model.calls)
    }

    @Test fun oldRepliesAreFixedOnUpdate() {
        val db = JdbcDb()
        val e1 = Engine(db) { today }
        val id = e1.record(today, 20.0, "debit", "Bhqr", "x", "sms", "Other")!!["id"] as Long
        db.exec("UPDATE transactions SET note='Chai (Bhqr)' WHERE id=?", id)
        db.exec("UPDATE meta SET value='3' WHERE key='cleanup_version'")
        Engine(db) { today }
        val r = db.one("SELECT merchant, category FROM transactions WHERE id=?", id)!!
        assertEquals("Chai", r["merchant"]); assertEquals("Snacks", r["category"])
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

@Suppress("UNCHECKED_CAST")
class ExtrasTest {
    private val today = LocalDate.of(2026, 11, 16)
    private val engine = Engine(JdbcDb()) { today }
    private val model = FakeModel()
    private val api = Api(engine, model)
    private fun call(m: String, p: String, b: Any? = null): Any? { val r = api.handle(m, p, b?.let { toJson(it).toString() }); assertEquals("$p ${r.body}", 200, r.status); return r.body }
    private fun tx(amount: Double, date: String, cat: String = "Food & Dining", merchant: String = "Cafe") =
        call("POST", "/api/transactions", mapOf("amount" to amount, "merchant" to merchant, "category" to cat, "date" to date)) as Map<String, Any?>

    @Test fun streakCountsZeroDaysAndUsesOneFreezeAMonth() {
        tx(100.0, "2026-11-12"); tx(80.0, "2026-11-13"); /* 14th missed */ tx(60.0, "2026-11-15")
        val s = call("GET", "/api/streak") as Map<String, Any?>
        assertEquals("freeze covers the 14th", 3, s["current"]); assertEquals(true, s["at_risk"]); assertEquals(false, s["freeze_available"])
        call("POST", "/api/streak/zero")
        val s2 = call("GET", "/api/streak") as Map<String, Any?>
        assertEquals(4, s2["current"]); assertEquals(true, s2["today_done"]); assertEquals(35, (s2["days"] as List<*>).size)
    }

    @Test fun envelopesTrackTheirOwnSpending() {
        call("POST", "/api/envelopes", mapOf("name" to "Goa trip", "budget" to 15000, "end_date" to "2026-12-31"))
        val env = (call("GET", "/api/envelopes") as List<Map<String, Any?>>).single()
        val t = tx(4000.0, "2026-11-16", "Travel", "Indigo")
        call("POST", "/api/transactions/${t["id"]}/envelope", mapOf("envelope_id" to env["id"]))
        val e2 = (call("GET", "/api/envelopes") as List<Map<String, Any?>>).single()
        assertEquals(4000.0, e2["spent"] as Double, 0.0); assertEquals(11000.0, e2["left"] as Double, 0.0)
        assertEquals("envelope spending stays out of the monthly pace", 0.0, (call("GET", "/api/pace") as Map<String, Any?>)["spent"] as Double, 0.0)
    }

    @Test fun paceLeavesOneOffCostsOutOfTheProjection() {
        call("PUT", "/api/settings", mapOf("monthly_budget" to 20000))
        tx(15000.0, "2026-11-01", "Rent", "Landlord")
        for (d in 1..16) tx(200.0, "2026-11-%02d".format(d))
        val p = call("GET", "/api/pace") as Map<String, Any?>
        // flexible 3200 over 16 days -> 6000 for 30 days, plus rent once = 21000
        assertEquals(21000L, p["projected"]); assertTrue((p["headline"] as String).contains("overshoot") || (p["headline"] as String).contains("runs out"))
    }

    @Test fun groupsAndNewSplit() {
        call("POST", "/api/groups", mapOf("name" to "Flat", "members" to listOf("rahul", "priya")))
        call("POST", "/api/splits/new", mapOf("amount" to 900, "what" to "Groceries", "people" to listOf("Rahul", "Priya")))
        val f = call("GET", "/api/friends") as Map<String, Any?>
        assertEquals(600.0, f["net"] as Double, 0.0)
        assertEquals(listOf("Rahul", "Priya"), (call("GET", "/api/groups") as List<Map<String, Any?>>).single()["members"])
    }

    @Test fun parseReviewsWithoutSaving() {
        model.answers["chai"] = """{"items": [{"type": "debit", "amount": 40, "party": "Chai", "date": "2026-11-16", "category": "Food & Dining"}, {"type": "debit", "amount": 120, "party": "Auto", "date": "2026-11-16", "category": "Transport"}]}"""
        val r = call("POST", "/api/parse", mapOf("text" to "chai 40 aur auto 120")) as List<Map<String, Any?>>
        assertEquals(listOf("Chai", "Auto"), r.map { it["party"] })
        assertTrue((call("GET", "/api/transactions") as List<*>).isEmpty())
    }
}
