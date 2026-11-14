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

class FakeAi(var on: Boolean = false) : Ai {
    var expenses: List<Ai.Expense> = emptyList()
    var receipt: Ai.Receipt? = null
    override fun available() = on
    override fun parseExpenses(text: String, categories: List<String>, today: LocalDate) = expenses
    override fun scanReceipt(data: ByteArray, mediaType: String, categories: List<String>, today: LocalDate) = receipt!!
    override fun planQuery(question: String, categories: List<String>, today: LocalDate): Map<String, Any?> = throw Ai.Unavailable("no")
    override fun monthlyInsights(statsJson: String): Map<String, Any?> = mapOf("headline" to "ok")
}

@Suppress("UNCHECKED_CAST")
class EngineTest {
    private val today = LocalDate.of(2026, 11, 14)
    private lateinit var engine: Engine
    private lateinit var ai: FakeAi
    private lateinit var api: Api

    @Before fun setUp() {
        engine = Engine(JdbcDb()) { today }
        ai = FakeAi()
        api = Api(engine, ai)
    }

    private fun call(m: String, path: String, body: Any? = null): Any? {
        val r = api.handle(m, path, body?.let { toJson(it).toString() })
        assertEquals("$m $path -> ${r.body}", 200, r.status)
        return fromJson(toJson(r.body)) // round-trip like the UI sees it
    }
    private fun status(m: String, path: String, body: Any? = null) = api.handle(m, path, body?.let { toJson(it).toString() }).status
    private fun added(text: String) = (call("POST", "/api/ingest", mapOf("text" to text)) as Map<String, Any?>)["added"] as List<Map<String, Any?>>
    private fun review() = call("GET", "/api/review") as List<Map<String, Any?>>
    private fun txs() = call("GET", "/api/transactions") as List<Map<String, Any?>>

    @Test fun parsesAndCategorizes() {
        val cases = listOf(
            listOf("250 swiggy dinner", 250.0, "Swiggy Dinner", "Food & Dining"),
            listOf("petrol 1,500", 1500.0, "Petrol", "Fuel"),
            listOf("spent 120 on uber", 120.0, "Uber", "Transport"),
            listOf("Rs.499.00 debited from A/c XX1234 on 12-11-26 to VPA netflix@icici UPI Ref 123456", 499.0, "Netflix", "Entertainment"),
            listOf("INR 1,234.50 spent on HDFC Bank Card XX9876 at AMAZON on 2026-11-10. Avl Lmt: INR 50,000", 1234.5, "Amazon", "Shopping"),
            listOf("Sent Rs.85.00 From HDFC Bank A/C *1234 To ZEPTO On 13/11/26 Ref 999", 85.0, "Zepto", "Groceries"),
        )
        for ((text, amount, merchant, cat) in cases) {
            val p = Parser.parse(text as String, today)!!
            assertEquals(text, amount, p.amount)
            assertEquals(text, merchant, p.merchant)
            assertEquals(text, cat, Categorizer.categorize(p.note, p.merchant))
        }
        val c = Parser.parse("Rs 5000 credited to your A/c XX1234 on 01-11-26 by NEFT", today)!!
        assertEquals("credit", c.kind); assertEquals(LocalDate.of(2026, 11, 1), c.date)
        assertEquals(LocalDate.of(2026, 11, 13), Parser.parse("coffee 90 yesterday", today)!!.date)
        assertNull(Parser.parse("hello there", today))
        val s = Parser.parse("900 dinner at toit split with rahul and priya", today)!!
        assertEquals(listOf("Rahul", "Priya"), s.splitWith)
        assertEquals(3, Parser.parse("600 cab split 3 ways", today)!!.splitWays)
    }

    @Test fun automaticCaptureIsStrict() {
        assertNull(api.captureAutomatic("Meet at 5, bring 400 chairs", "sms"))
        assertNull(api.captureAutomatic("Your OTP is 482913 for txn of Rs 2000", "sms"))
        assertNotNull(api.captureAutomatic("Rs.649 debited from A/c XX1234 on 13-11-26 to VPA blinkit@hdfc UPI Ref 1", "sms"))
        // the same payment from the UPI app notification a minute later is merged silently, not asked about
        assertNull(api.captureAutomatic("Paid ₹649 to Blinkit", "notification"))
        assertEquals(1, txs().size); assertTrue(review().isEmpty())
    }

    @Test fun learnsFromCorrections() {
        val r = added("250 swiggy\n400 ramesh kirana store")
        assertEquals(2, r.size)
        val ramesh = txs().first { it["merchant"] == "Ramesh Kirana Store" }
        assertEquals("Groceries", ramesh["category"]) // "kirana" keyword
        call("PATCH", "/api/transactions/${ramesh["id"]}", mapOf("category" to "Shopping"))
        added("100 ramesh kirana store")
        assertEquals(listOf("Shopping", "Shopping"), txs().filter { it["merchant"] == "Ramesh Kirana Store" }.map { it["category"] })
    }

    @Test fun nonTransactionsAndRedaction() {
        val r = call("POST", "/api/ingest", mapOf("text" to "Your OTP is 482913 for txn of Rs 2000\n\nRs 999 will be debited on 20-11-26 for your SIP\n\n" +
            "Rs 300 debited from A/c 123456789012 to VPA chai@ybl. Avl Bal Rs 45,000")) as Map<String, Any?>
        assertEquals(2L, r["ignored"])
        val note = (r["added"] as List<Map<String, Any?>>)[0]["note"] as String
        assertFalse("123456789012" in note); assertFalse("45,000" in note); assertTrue("9012" in note)
    }

    @Test fun clarificationOnlyWhenNeeded() {
        added("250 swiggy\n700 zxqv traders")
        val rv = review()
        assertEquals(listOf("Zxqv Traders"), rv.map { it["merchant"] }); assertNotNull(rv[0]["question"])
        call("POST", "/api/review/${rv[0]["id"]}", mapOf("action" to "confirm", "category" to "Shopping"))
        assertTrue(review().isEmpty())
        added("300 zxqv traders")
        assertTrue(review().isEmpty())
    }

    @Test fun duplicatePaymentDetection() {
        api.ingestText("Rs.649 debited from A/c XX1234 on 13-11-26 to VPA blinkit@hdfc UPI Ref 1", "sms", false)
        added("649 blinkit 13-11-26")
        val rv = review()
        assertEquals(1, rv.size); assertEquals("duplicate", rv[0]["review_reason"])
        call("POST", "/api/review/${rv[0]["id"]}", mapOf("action" to "delete"))
        assertEquals(1, txs().size)
    }

    @Test fun allowanceAndSavingsPool() {
        call("PUT", "/api/settings", mapOf("daily_allowance" to 1000, "budget_start" to "2026-11-10"))
        call("POST", "/api/transactions", mapOf("amount" to 400, "merchant" to "Swiggy", "date" to "2026-11-10"))
        call("POST", "/api/transactions", mapOf("amount" to 1500, "merchant" to "Zara", "date" to "2026-11-11"))
        call("POST", "/api/transactions", mapOf("amount" to 20000, "merchant" to "Landlord", "category" to "Rent", "date" to "2026-11-11"))
        call("POST", "/api/transactions", mapOf("amount" to 300, "merchant" to "Chai", "category" to "Food & Dining"))
        val b = call("GET", "/api/budget") as Map<String, Any?>
        assertEquals(4 * 1000 - 1900.0, b["savings_pool"] as Double, 0.001)   // 10th..13th, rent excluded
        assertEquals(700.0, b["left_today"] as Double, 0.001)
    }

    @Test fun splitAndAutoSettle() {
        val tx = added("900 dinner at toit split with rahul and priya")[0]
        assertEquals(300.0, tx["my_share"] as Double, 0.001)
        assertEquals(setOf("Rahul", "Priya"), (call("GET", "/api/splits") as List<Map<String, Any?>>).map { it["person"] }.toSet())
        added("Rs 300 credited to your A/c XX1234 from Rahul Sharma via UPI")
        assertEquals(listOf("Priya"), (call("GET", "/api/splits") as List<Map<String, Any?>>).map { it["person"] })
        assertEquals(300.0, (call("GET", "/api/summary") as Map<String, Any?>)["today"] as Double, 0.001)
    }

    @Test fun recurringAndSubscriptions() {
        for (d in listOf("2026-08-05", "2026-09-05", "2026-10-05")) call("POST", "/api/transactions", mapOf("amount" to 649, "merchant" to "Spotify", "date" to d))
        api.ingestText("Rs.199 debited from A/c XX1 to VPA netflix@icici UPI Ref 9", "sms", false)
        val subs = (call("GET", "/api/subscriptions") as Map<String, Any?>)["subscriptions"] as List<Map<String, Any?>>
        val byName = subs.associateBy { it["name"] }
        assertEquals("active", byName["Spotify"]!!["status"]); assertEquals("2026-11-05", byName["Spotify"]!!["next_date"])
        assertEquals("suggested", byName["Netflix"]!!["status"])
        call("PATCH", "/api/subscriptions/${byName["Spotify"]!!["id"]}", mapOf("status" to "cancelled"))
        call("POST", "/api/transactions", mapOf("amount" to 649, "merchant" to "Spotify", "date" to "2026-11-05"))
        assertTrue(review().any { it["review_reason"] == "cancelled_subscription" })
    }

    @Test fun customRulesAndCategories() {
        call("PUT", "/api/settings", mapOf("custom_categories" to listOf("Pets")))
        val id = added("1200 heads up for tails")[0]["id"]
        assertEquals(1L, (call("POST", "/api/rules", mapOf("pattern" to "tails", "category" to "Pets")) as Map<String, Any?>)["updated_transactions"])
        assertEquals("Pets", (call("GET", "/api/transactions/$id") as Map<String, Any?>)["category"])
        call("POST", "/api/rules", mapOf("pattern" to "^uber", "match_type" to "regex", "category" to "Travel", "min_amount" to 1000))
        val (a, b) = added("uber 1500\nuber 200")
        assertEquals("Travel", a["category"]); assertEquals("Transport", b["category"])
    }

    @Test fun askMerchantHistoryInsights() {
        added("250 swiggy\n400 zomato\nuber 180")
        val r = call("POST", "/api/ask", mapOf("question" to "how much did I spend on food this month?")) as Map<String, Any?>
        assertEquals(650.0, r["total"] as Double, 0.001); assertTrue("650" in (r["answer"] as String))
        assertEquals(1L, (call("POST", "/api/ask", mapOf("question" to "show uber")) as Map<String, Any?>)["count"])
        val h = call("GET", "/api/merchants/history?name=Swiggy") as Map<String, Any?>
        assertEquals(250.0, h["total"] as Double, 0.001); assertEquals(12, (h["monthly"] as List<*>).size)
        val ins = call("GET", "/api/insights") as Map<String, Any?>
        assertTrue((ins["insights"] as List<*>).isNotEmpty())
        assertTrue(((ins["recommendations"] as Map<String, Any?>)["suggested_daily_allowance"] as Long) > 0)
        assertEquals(400, status("POST", "/api/insights/ai"))
    }

    @Test fun userControlledDeletion() {
        added("250 swiggy\n100 chai")
        assertEquals(400, status("POST", "/api/data/delete", mapOf("scope" to "everything", "confirm" to "nope")))
        val r = call("POST", "/api/data/delete", mapOf("scope" to "everything", "confirm" to "DELETE")) as Map<String, Any?>
        assertEquals(0L, r["remaining_transactions"]); assertTrue(txs().isEmpty())
    }

    @Test fun csvImportAndExport() {
        val csv = "Date,Narration,Withdrawal Amt,Deposit Amt\n10/11/2026,UPI-BLINKIT-123,320.00,\n11/11/2026,SALARY,,50000\n"
        assertEquals(2L, (call("POST", "/api/import/csv", mapOf("text" to csv)) as Map<String, Any?>)["added"])
        val out = (call("GET", "/api/export.csv") as Map<String, Any?>)["content"] as String
        assertTrue("Blinkit" in out && "Groceries" in out)
    }

    @Test fun aiFeaturesWithFakeClaude() {
        ai.on = true
        call("PUT", "/api/settings", mapOf("ai_enabled" to true))
        ai.expenses = listOf(Ai.Expense(450.0, "Third Wave Coffee", "Food & Dining", "debit", today.toString(), listOf("Asha"), 2, true))
        val r = (call("POST", "/api/ingest", mapOf("text" to "coffee with asha four fifty we split it", "smart" to true)) as Map<String, Any?>)["added"] as List<Map<String, Any?>>
        assertEquals("Third Wave Coffee", r[0]["merchant"]); assertEquals(225.0, r[0]["my_share"] as Double, 0.001)
        ai.receipt = Ai.Receipt("DMart", 1834.5, "2026-11-12", "Groceries", listOf("Rice", "Dal"), true)
        val rc = call("POST", "/api/receipt", mapOf("mime" to "image/jpeg", "data" to java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))) as Map<String, Any?>
        assertEquals(1834.5, (rc["transaction"] as Map<String, Any?>)["amount"] as Double, 0.001)
    }
}

@Suppress("UNCHECKED_CAST")
class BackupTest {
    @Test fun backupRestoresIntoAFreshInstall() {
        val a = Api(Engine(JdbcDb()) { LocalDate.of(2026, 11, 14) }, FakeAi())
        a.handle("POST", "/api/ingest", toJson(mapOf("text" to "900 dinner at toit split with rahul and priya\n250 swiggy")).toString())
        a.handle("PUT", "/api/settings", toJson(mapOf("daily_allowance" to 777)).toString())
        val backup = (a.handle("GET", "/api/export.json", null).body as Map<String, Any?>)["content"] as String

        val b = Api(Engine(JdbcDb()) { LocalDate.of(2026, 11, 14) }, FakeAi())
        assertEquals(400, b.handle("POST", "/api/data/restore", toJson(mapOf("content" to backup, "confirm" to "no")).toString()).status)
        val r = b.handle("POST", "/api/data/restore", toJson(mapOf("content" to backup, "confirm" to "RESTORE")).toString())
        assertEquals(200, r.status); assertEquals(2L, (r.body as Map<String, Any?>)["transactions"])
        assertEquals(2, (b.handle("GET", "/api/splits", null).body as List<*>).size)
        assertEquals(777.0, (b.handle("GET", "/api/settings", null).body as Map<String, Any?>)["daily_allowance"] as Double, 0.0)
    }
}
