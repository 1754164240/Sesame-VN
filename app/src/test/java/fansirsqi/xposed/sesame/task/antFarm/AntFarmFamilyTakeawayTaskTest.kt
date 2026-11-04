package fansirsqi.xposed.sesame.task.antFarm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AntFarmFamilyTakeawayTaskTest {
    private class Server {
        var state = "TODO"
        var canDo = "true"
        var present = true
        var expired = false
        var failList = false
        var failDo = false
        var autoReceive = true
        var lists = 0
        var completions = 0
        val logs = mutableListOf<String>()

        fun call(method: String, p: JSONObject): JSONObject {
            assertEquals("NORMAL", p.getString("requestType"))
            assertEquals("ANTFARM", p.getString("sceneCode"))
            assertEquals("H5", p.getString("source"))
            val res = JSONObject().put("success", true).put("resultCode", "100")
            when (method) {
                "com.alipay.antfarm.listFamilyTask" -> {
                    lists++
                    assertEquals("Asia/Shanghai", p.getString("timeZoneId"))
                    if (failList) return res.put("success", false)
                    val tasks = JSONArray().put(JSONObject().put("taskId", "TREAT").put("bizKey", "TREAT").put("taskStatus", "TODO"))
                    if (present) tasks.put(JSONObject().put("taskId", "FAMILY_SHANGOU")
                        .put("bizKey", "FAMILY_SHANGOU").put("taskStatus", state)
                        .put("taskEntityExpireTime", if (expired) 1L else Long.MAX_VALUE)
                        .put("extend", JSONObject().put("canDo", canDo).toString()))
                    return res.put("familyTasks", tasks)
                }
                "com.alipay.antfarm.doFarmTask" -> {
                    completions++
                    assertEquals("FAMILY_SHANGOU", p.getString("bizKey"))
                    assertEquals("ANTFARM_FAMILY_TASK", p.getString("taskSceneCode"))
                    if (failDo) return res.put("success", false)
                    if (autoReceive) state = "RECEIVED"
                    return res.put("taskId", "FAMILY_SHANGOU").put("taskStatus", "FINISHED").put("awardCount", 1)
                }
                else -> error("不应调用独立领奖或其他家庭任务接口：$method")
            }
        }

        fun run(): Boolean = AntFarmFamilyTakeawayTask(::call, logs::add).run()
    }

    @Test fun `按抓包完成家庭外卖任务并复查直接已领取`() {
        val s = Server(); assertTrue(s.run())
        assertEquals(1, s.completions); assertEquals(2, s.lists)
        assertTrue(s.logs.any { it.contains("已领取") })
    }

    @Test fun `同日重复执行以已领取状态跳过`() {
        val s = Server(); assertTrue(s.run()); assertTrue(s.run())
        assertEquals(1, s.completions)
    }

    @Test fun `任务未出现或不可执行及过期时不完成`() {
        val absent = Server(); absent.present = false; assertFalse(absent.run()); assertEquals(0, absent.completions)
        val blocked = Server(); blocked.canDo = "false"; assertFalse(blocked.run()); assertEquals(0, blocked.completions)
        val expired = Server(); expired.expired = true; assertFalse(expired.run()); assertEquals(0, expired.completions)
    }

    @Test fun `完成响应成功但复查未领取不能报告成功`() {
        val s = Server(); s.autoReceive = false; assertFalse(s.run())
        assertEquals(1, s.completions); assertFalse(s.logs.any { it.contains("已领取") })
    }

    @Test fun `接口失败不继续完成也不自动重试`() {
        val listFailed = Server(); listFailed.failList = true; assertFalse(listFailed.run()); assertEquals(0, listFailed.completions)
        val doFailed = Server(); doFailed.failDo = true; assertFalse(doFailed.run()); assertEquals(1, doFailed.completions)
        assertEquals(1, doFailed.lists)
    }
}
