package fansirsqi.xposed.sesame.task.antForest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReadForestRunnerTest {
    private class Fixture {
        val state = mutableMapOf<String, String>()
        val calls = mutableListOf<String>()
        var progress = 0
        var total = 2
        var stopped = false
        var failProgress = false
        var next = "0"
        var gain = true
        var chapterNumber = 0
        var maxChapters = 120
        val requestedChapters = mutableListOf<String?>()
        var authorize: (JSONObject?) -> JSONObject = { ok(JSONObject().put("token", "secret")) }
        var books = """[{"block_resource":[{"book_id":"paid","price":"1.00"},{"book_id":"a","price":"0.00"},{"book_id":"b","price":"0.00"}]}]"""
        val runner get() = ReadForestRunner(
            request = { path, body, book, chapter, _ ->
                calls += "$path:$book"
                when (path) {
                    "/authorization" -> authorize(body)
                    "/index/index?type=1" -> JSONObject().put("code", 2000).put("data", org.json.JSONArray(books))
                    "/book/chapter" -> {
                        requestedChapters += chapter
                        chapterNumber++
                        ok(JSONObject().put("id", chapterNumber).put("next_id", if (next == "0") "0" else (chapterNumber + 1).toString()).put("name", "章节"))
                    }
                    "/user/updateWel" -> { if (gain) progress++; ok(JSONObject()) }
                    "/book/set_read_recently" -> ok(JSONObject())
                    "/user/forestPro" -> if (failProgress) ok(JSONObject()) else ok(JSONObject().put("current", progress).put("total", total))
                    else -> error("未知接口")
                }
            },
            readState = { state[it] }, writeState = { k, v -> state[k] = v },
            authCode = { "code" }, day = "2026-11-13",
            checkpoint = { if (stopped) throw CancellationException("暂停") },
            pause = { }, reportSeconds = { 333 }, maxChapters = maxChapters
        )
    }

    @Test fun `只读免费书并在尾章查询满额后记录当天完成`() = runBlocking {
        val f = Fixture()
        assertEquals(ReadForestRunner.Result.FULL, f.runner.run())
        assertFalse(f.calls.any { it.endsWith(":paid") })
        assertTrue(f.calls.contains("/book/chapter:b"))
        assertEquals("2026-11-13", f.state["done"])
        val count = f.calls.size
        f.runner.run()
        assertEquals(count, f.calls.size)
    }

    @Test fun `进度缺字段不能误判满额也不能上报`() = runBlocking {
        val f = Fixture().apply { failProgress = true }
        assertEquals(ReadForestRunner.Result.NO_PROGRESS, f.runner.run())
        assertNull(f.state["done"])
        assertFalse(f.calls.any { it.startsWith("/user/updateWel") })
    }

    @Test fun `连续六章无收益停止且不标记完成`() = runBlocking {
        val f = Fixture().apply { next = "2"; gain = false }
        assertEquals(ReadForestRunner.Result.NO_GAIN, f.runner.run())
        assertNull(f.state["done"])
        assertEquals(6, f.calls.count { it.startsWith("/user/updateWel") })
    }

    @Test fun `请求后检测暂停不能继续读书或保存令牌`() = runBlocking {
        val f = Fixture()
        f.authorize = { f.stopped = true; ok(JSONObject().put("token", "secret")) }
        try { f.runner.run(); fail("应中断") } catch (_: CancellationException) { }
        assertEquals(1, f.calls.size)
        assertNull(f.state["token"])
        assertNull(f.state["done"])
    }

    @Test fun `昨日完成标记不影响今日且已满额不读章节`() = runBlocking {
        val f = Fixture().apply { state["done"] = "2026-11-12"; progress = 2 }
        assertEquals(ReadForestRunner.Result.FULL, f.runner.run())
        assertFalse(f.calls.any { it.startsWith("/book/chapter") })
    }

    @Test fun `没有新书时刷新当天书城一次且不标记满额`() = runBlocking {
        val f = Fixture().apply { books = "[]" }
        assertEquals(ReadForestRunner.Result.NO_BOOKS, f.runner.run())
        assertNull(f.state["done"])
        assertEquals(1, f.calls.count { it.startsWith("/index/index") })
        f.calls.clear()
        f.runner.run()
        assertEquals(1, f.calls.count { it.startsWith("/index/index") })
    }

    @Test fun `缓存令牌失效重新授权但失败响应不能写完成`() = runBlocking {
        val f = Fixture().apply { state["token"] = "old" }
        f.authorize = { body -> if (body == null) JSONObject().put("code", 401) else ok(JSONObject().put("token", "new")) }
        f.runner.run()
        assertEquals("new", f.state["token"])
        assertEquals(2, f.calls.count { it.startsWith("/authorization") })
    }

    @Test fun `轮次受限保存下一章下轮接续且不写完成`() = runBlocking {
        val f = Fixture().apply { maxChapters = 1; next = "2"; total = 150 }
        assertEquals(ReadForestRunner.Result.LIMIT, f.runner.run())
        assertNull(f.state["done"])
        f.runner.run()
        assertEquals(listOf(null, "2"), f.requestedChapters)
    }

    @Test fun `令牌HTTP失效后清缓存并在当前轮重新登录`() = runBlocking {
        val f = Fixture().apply { state["token"] = "expired" }
        f.authorize = { body ->
            if (body == null) throw ReadForestHttpException(401)
            ok(JSONObject().put("token", "renewed"))
        }
        assertEquals(ReadForestRunner.Result.FULL, f.runner.run())
        assertEquals("renewed", f.state["token"])
    }

    companion object {
        private fun ok(data: JSONObject) = JSONObject().put("code", 2000).put("data", data)
    }
}
