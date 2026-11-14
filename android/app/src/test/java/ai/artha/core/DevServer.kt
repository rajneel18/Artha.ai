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
    private val api = Api(Engine(JdbcDb()), FakeAi())
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
