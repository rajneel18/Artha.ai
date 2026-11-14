package ai.artha.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Serves the bundled UI with the real Kotlin engine behind it, for testing the UI in a desktop browser.
 * Only runs when ARTHA_DEVSERVER=<seconds> is set:  ARTHA_DEVSERVER=600 gradle testDebugUnitTest --tests '*DevServer*'
 */
class DevServer {
    private val model = FakeModel().apply {
        answers["shravani"] = """{"type": "debit", "amount": 1.0, "party": "Shravani Shinde", "category": "Transfers"}"""
        answers["Netflix"] = """{"type": "debit", "amount": 499, "party": "Netflix", "category": "Entertainment"}"""
        answers["ZOMATO"] = """{"type": "debit", "amount": 1250, "party": "Zomato", "category": "Food & Dining"}"""
        answers["rahul"] = """{"type": "credit", "amount": 2500, "party": "Rahul Patil", "category": "Transfers"}"""
        answers["ramesh"] = """{"type": "debit", "amount": 640, "party": "Ramesh", "category": "Other", "confidence": "low"}"""
    }
    private val api = Api(Engine(JdbcDb()), model).also { a ->
        val now = java.time.LocalDateTime.now()
        listOf(
            "Your a/c no. XX6196 is debited for Rs.1.00 on 14-11-2026 17:46:28 and credited to vpa shravani.shinde109-1@okicici (UPI Ref no 631826724674) Your Current Balance is INR 84413.27 - Saraswat Co-op Bank Ltd.",
            "UPI AutoPay Mandate for Netflix debit of Rs.499.00 is scheduled on 30-10-2026. Please ensure sufficient balance in your account - Saraswat Co-op Bank Ltd.",
            "INR 1,250.00 spent on ICICI Bank Card XX9876 at ZOMATO on 14-Nov-26. Avl Lmt: INR 50,000",
            "Your a/c no. XX6196 is credited by Rs.2,500.00 from vpa rahul.patil22@oksbi (UPI Ref no 631812345678) - Saraswat Co-op Bank Ltd.",
            "Rs.640 debited from a/c XX6196 to ramesh UPI Ref 77",
            "482913 is your OTP for transaction of Rs.2,000.00 at AMAZON - Saraswat Bank",
        ).forEachIndexed { i, sms -> a.captureSms(sms, "AX-SARBNK", now.minusHours(i.toLong())) }
        run {
            model.answers["swiggy"] = """{"type": "debit", "amount": 250, "party": "Swiggy", "category": "Food & Dining"}"""
            model.answers["blinkit"] = """{"type": "debit", "amount": 430, "party": "Blinkit", "category": "Groceries"}"""
            model.answers["kirana"] = """{"type": "debit", "amount": 120, "party": "Ramesh Kirana", "category": "Other", "confidence": "low"}"""
        }
        for (i in 1..14) a.captureSms("Rs.250 paid to swiggy Ref $i", "AX-HDFCBK", now.minusDays(i * 2L), history = true)
        for (i in 1..9) a.captureSms("Rs.430 paid to blinkit Ref $i", "AX-HDFCBK", now.minusDays(i * 3L), history = true)
        for (i in 1..6) a.captureSms("Rs.120 paid to kirana Ref $i", "AX-HDFCBK", now.minusDays(i * 4L), history = true)
        while (a.engine.analyzeNext(a.extractor)) {}
        a.handle("PUT", "/api/settings", toJson(mapOf("daily_allowance" to 800, "budget_start" to java.time.LocalDate.now().minusDays(5).toString())).toString())
        listOf(250 to "Swiggy", 180 to "Uber", 420 to "Blinkit").forEach { (amt, m) ->
            a.handle("POST", "/api/transactions", toJson(mapOf("amount" to amt, "merchant" to m, "category" to if (m == "Uber") "Transport" else "Food & Dining")).toString())
        }
    }
    private val assets = File("src/main/assets")

    @Test fun serve() {
        val seconds = System.getenv("ARTHA_DEVSERVER")?.toLongOrNull()
        assumeTrue(seconds != null)
        val server = ServerSocket(8130, 50, InetAddress.getByName("127.0.0.1"))
        val until = System.currentTimeMillis() + seconds!! * 1000
        server.soTimeout = 1000
        println("DevServer on http://127.0.0.1:8130")
        while (System.currentTimeMillis() < until) {
            val s = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
            try { handle(s) } catch (_: Exception) {} finally { s.close() }
        }
        server.close()
    }

    private fun handle(s: Socket) {
        val inp = s.getInputStream().buffered()
        fun line(): String { val sb = StringBuilder(); while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }; return sb.toString() }
        val (method, path) = line().split(" ").let { it[0] to it[1].substringBefore("?") }
        var len = 0
        while (true) { val h = line(); if (h.isEmpty()) break; if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(":").trim().toInt() }
        val body = ByteArray(len).also { var o = 0; while (o < len) { val n = inp.read(it, o, len - o); if (n < 0) break; o += n } }
        val (code, type, bytes) = if (method == "POST" && path == "/bridge") {
            val req = parseBody(body.decodeToString())
            val r = synchronized(api) { api.handle(req["method"] as String, req["path"] as String, req["body"] as String?) }
            Triple(200, "application/json", toJson(mapOf("status" to r.status, "body" to r.body)).toString().toByteArray())
        } else {
            val f = File(assets, if (path == "/") "index.html" else path.trimStart('/'))
            if (f.isFile && f.canonicalPath.startsWith(assets.canonicalPath)) Triple(200, "text/html; charset=utf-8", f.readBytes())
            else Triple(404, "text/plain", "not found".toByteArray())
        }
        val out = s.getOutputStream()
        out.write("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes); out.flush()
    }
}
