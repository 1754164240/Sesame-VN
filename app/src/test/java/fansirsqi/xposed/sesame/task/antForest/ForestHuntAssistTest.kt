package fansirsqi.xposed.sesame.task.antForest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForestHuntAssistTest {
    @Test fun `助力场景读取任务基础信息中字符串或对象的prodPlayParam`() {
        assertEquals("share-scene", ForestHuntAssist.p2pScene(JSONObject()
            .put("bizInfo", """{"title":"助力"}""")
            .put("prodPlayParam", """{"p2pSceneCode":"share-scene"}""")))
        assertEquals("share-scene", ForestHuntAssist.p2pScene(JSONObject()
            .put("prodPlayParam", JSONObject().put("p2pSceneCode", "share-scene"))))
        assertEquals("", ForestHuntAssist.p2pScene(JSONObject()))
        assertTrue(ForestHuntAssist.shareTaskBlocked("FOREST_NORMAL_DRAW_SHARE", "邀请好友", setOf("邀请好友")))
        assertTrue(ForestHuntAssist.shareTaskBlocked("FOREST_NORMAL_DRAW_SHARE", "邀请好友", setOf("FOREST_NORMAL_DRAW_SHARE")))
    }
    @Test fun `短分享标识按普通和活动场景生成不同完整标识`() {
        val prefix = "abcdefghijklmnopqrstuvwx"
        val normal = ForestHuntAssist.expandShareId(prefix, "FOREST_NORMAL_DRAW_SHARE")!!
        val activity = ForestHuntAssist.expandShareId(prefix, "FOREST_ACTIVITY_DRAW_SHARE")!!
        assertNotEquals(normal, activity)
        assertTrue(normal.startsWith(prefix + "4O7FEYDgn"))
        assertNull(ForestHuntAssist.expandShareId("bad", "FOREST_NORMAL_DRAW_SHARE"))
        assertEquals(normal, ForestHuntAssist.expandShareId(normal, "FOREST_NORMAL_DRAW_SHARE"))
    }

    @Test fun `只有当前活动助力确认成功才写每日标记且失败可重试`() = runBlocking {
        val marks = mutableSetOf<String>()
        val methods = mutableListOf<String>()
        var confirmed = false
        val assist = ForestHuntAssist(
            request = { method, body ->
                methods += method
                if (method.endsWith("shareComponentRecall")) JSONObject().put("success", true)
                    .put("inviterInfoVo", JSONObject().put("userId", "2088000000000001"))
                else {
                    assertEquals("current-activity", body.getJSONObject("beSharedBizExtInfo").getString("drawActivityId"))
                    JSONObject().put("success", confirmed)
                }
            }, checkpoint = {}, pause = {}, hasFlag = { it in marks }, mark = { marks += it }, currentUserId = "self"
        )
        val ids = setOf("abcdefghijklmnopqrstuvwx")
        assertEquals(0, assist.run("current-activity", "SCENE", "FOREST_NORMAL_DRAW_SHARE", ids))
        assertTrue(marks.isEmpty())
        confirmed = true
        assertEquals(1, assist.run("current-activity", "SCENE", "FOREST_NORMAL_DRAW_SHARE", ids))
        assertEquals(0, assist.run("current-activity", "SCENE", "FOREST_NORMAL_DRAW_SHARE", ids))
        assertEquals(4, methods.size)
    }

    @Test fun `解析失败自身邀请及暂停时不能发送确认请求`() = runBlocking {
        var count = 0
        val assist = ForestHuntAssist(
            request = { _, _ -> count++; JSONObject().put("success", true).put("inviterInfoVo", JSONObject().put("userId", "self")) },
            checkpoint = {}, pause = {}, hasFlag = { false }, mark = { fail("不能记完成") }, currentUserId = "self"
        )
        assertEquals(0, assist.run("a", "s", "FOREST_NORMAL_DRAW_SHARE", setOf("abcdefghijklmnopqrstuvwx")))
        assertEquals(1, count)
        val stopped = ForestHuntAssist(request = { _, _ -> fail("不能请求"); JSONObject() },
            checkpoint = { throw CancellationException("停止") }, pause = {}, hasFlag = { false }, mark = {}, currentUserId = "self")
        try { stopped.run("a", "s", "FOREST_NORMAL_DRAW_SHARE", setOf("abcdefghijklmnopqrstuvwx")); fail("应停止") }
        catch (_: CancellationException) { }
    }
}
