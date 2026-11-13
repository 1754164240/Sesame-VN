package fansirsqi.xposed.sesame.task.antForest

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class ReadForestRpcCallTest {
    @Test fun `实际HTTP请求携带小程序头令牌及正确章节参数`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var query: String? = null
        var method: String? = null
        var token: String? = null
        var appId: String? = null
        server.createContext("/api/book/chapter") { exchange ->
            query = exchange.requestURI.rawQuery
            method = exchange.requestMethod
            token = exchange.requestHeaders.getFirst("authorization")
            appId = exchange.requestHeaders.getFirst("x-mass-tappid")
            val bytes = """{"code":2000,"data":{"id":12}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = ReadForestRpcCall("test-UA", "http://127.0.0.1:${server.address.port}/api")
            assertEquals(12, client.request("/book/chapter", null, "book&1", "12", "secret").getJSONObject("data").getInt("id"))
            assertEquals("GET", method)
            assertTrue(query.orEmpty().contains("book_id=book%261"))
            assertTrue(query.orEmpty().contains("chapter_id=12"))
            assertEquals("Bearer secret", token)
            assertEquals("2021003114652763", appId)
        } finally { server.stop(0) }
    }

    @Test fun `阅读时长上报使用POST与数值章节且不重放失败请求`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var calls = 0
        var body = JSONObject()
        var method = ""
        server.createContext("/api/user/updateWel") { exchange ->
            calls++
            method = exchange.requestMethod
            body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            val bytes = "敏感响应正文".toByteArray()
            exchange.sendResponseHeaders(503, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = ReadForestRpcCall("test", "http://127.0.0.1:${server.address.port}/api")
            try {
                client.request("/user/updateWel", JSONObject().put("chapter_id", 12).put("time", 333), "b", "12", "secret")
                fail("失败状态不能当作成功")
            } catch (e: ReadForestHttpException) {
                assertEquals(503, e.statusCode)
                assertFalse(e.message!!.contains("敏感"))
                assertFalse(e.message!!.contains("secret"))
            }
            assertEquals(1, calls)
            assertEquals("POST", method)
            assertTrue(body.get("chapter_id") is Number)
            assertEquals(333, body.getInt("time"))
        } finally { server.stop(0) }
    }
}
