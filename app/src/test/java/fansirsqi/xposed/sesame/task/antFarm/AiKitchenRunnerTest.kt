package fansirsqi.xposed.sesame.task.antFarm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiKitchenRunnerTest {
    private class Fixture {
        val calls = mutableListOf<String>()
        val candidates = mutableSetOf<String>()
        val blocked = mutableSetOf<String>()
        var stopped = false
        var stock = 3
        var feed = 100
        var cost = 10
        var decrease = true
        var failCook = false
        var taskState = "TODO"
        var failTask = false
        var failAward = false
        var rejectFurtherCook = false
        fun home() = JSONObject().put("success", true).put("seasonId", "s1")
            .put("feedStock", feed).put("feedCost", cost).put("canCook", true)
            .put("ingredientList", JSONArray((1..3).map { JSONObject().put("ingredientId", "i$it").put("count", stock) }))
        val runner get() = AiKitchenRunner(
            request = { method, body ->
                calls += method
                when (method) {
                    "com.alipay.antfarm.enterAiKitchen" -> home()
                    "com.alipay.antfarm.listFarmTask" -> {
                        assertEquals("ANTFARM_AI_KITCHEN_TASK", body.getString("taskSceneCode"))
                        JSONObject().put("success", true).put("farmTaskList", JSONArray().put(
                            JSONObject().put("taskId", "browse").put("title", "看美食知识").put("taskStatus", taskState).put("canReceiveAwardCount", 1)))
                    }
                    "com.alipay.antieptask.finishTaskantfarm" -> {
                        if (failTask) JSONObject().put("success", false).put("resultCode", "SYSTEM_BUSY")
                        else { taskState = "FINISHED"; JSONObject().put("success", true) }
                    }
                    "com.alipay.antieptask.receiveTaskAwardantfarm" -> {
                        assertEquals("ANTFARM_AI_KITCHEN_TASK", body.getString("sceneCode"))
                        assertEquals(1, body.getInt("awardCountForReceive"))
                        if (failAward) JSONObject().put("success", false).put("resultCode", "TASK_NOT_SUPPORT")
                        else { taskState = "RECEIVED"; JSONObject().put("success", true) }
                    }
                    "com.alipay.antfarm.cookAiKitchen" -> {
                        assertEquals(3, body.getJSONArray("ingredientIds").length())
                        if (failCook) JSONObject().put("success", false)
                        else if (rejectFurtherCook) JSONObject().put("success", true).put("canCook", false)
                        else { if (decrease) stock--; feed -= cost; home().put("dishName", "菜品").put("cookResultType", "NORMAL") }
                    }
                    else -> error("未知接口: $method")
                }
            },
            userId = "u", checkpoint = { if (stopped) throw CancellationException("暂停") },
            pause = {}, blacklist = blocked, onCandidate = { candidates += it }, onAutoBlacklist = { blocked += it }
        )
    }

    @Test fun `先完成食材任务并领奖再做菜直到库存不足`() = runBlocking {
        val f = Fixture()
        val result = f.runner.execute(autoCook = true, autoFinishTask = true, autoBlacklist = true)
        assertEquals("RECEIVED", f.taskState)
        assertEquals(3, result.cooked)
        assertTrue(f.calls.indexOf("com.alipay.antieptask.receiveTaskAwardantfarm") < f.calls.indexOf("com.alipay.antfarm.cookAiKitchen"))
        assertTrue(f.candidates.contains("看美食知识"))
    }

    @Test fun `饲料不足或少于三种食材不能发送制作请求`() = runBlocking {
        val f = Fixture().apply { feed = 5 }
        assertEquals(0, f.runner.execute(true, false, false).cooked)
        assertFalse(f.calls.contains("com.alipay.antfarm.cookAiKitchen"))
        assertNull(AiKitchenPolicy.pickIngredients(JSONObject().put("ingredientList", JSONArray().put(
            JSONObject().put("ingredientId", "only").put("count", 99))), emptySet()))
    }

    @Test fun `优先选择未尝试且库存有效的三种不同食材`() {
        val state = Fixture().home()
        state.getJSONArray("ingredientList").put(JSONObject().put("ingredientId", "i4").put("count", 1))
        val choice = AiKitchenPolicy.pickIngredients(state, setOf("i1|i2|i3"))!!
        assertEquals(3, choice.toSet().size)
        assertTrue(choice.contains("i4"))
    }

    @Test fun `库存连续不下降时结束制作避免重复消耗`() = runBlocking {
        val f = Fixture().apply { decrease = false }
        assertEquals(2, f.runner.execute(true, false, false).cooked)
        assertEquals(2, f.calls.count { it == "com.alipay.antfarm.cookAiKitchen" })
    }

    @Test fun `黑名单任务不申报不领奖且临时失败不自动拉黑`() = runBlocking {
        val f = Fixture().apply { blocked += "看美食知识" }
        f.runner.execute(false, true, true)
        assertFalse(f.calls.any { it.contains("finishTask") || it.contains("receiveTaskAward") })
        val busy = Fixture().apply { failTask = true }
        busy.runner.execute(false, true, true)
        assertTrue(busy.blocked.isEmpty())
        assertEquals("TODO", busy.taskState)
    }

    @Test fun `制作失败不累计成功且任务取消后不能继续请求`() = runBlocking {
        val f = Fixture().apply { failCook = true }
        assertEquals(0, f.runner.execute(true, false, false).cooked)
        val stopped = Fixture().apply { stopped = true }
        try { stopped.runner.execute(true, true, true); fail("应取消") } catch (_: CancellationException) { }
        assertTrue(stopped.calls.isEmpty())
    }

    @Test fun `领奖永久失败回查仍可领时自动拉黑而不是当作已领`() = runBlocking {
        val f = Fixture().apply { taskState = "FINISHED"; failAward = true }
        f.runner.execute(false, false, true)
        assertEquals(setOf("看美食知识"), f.blocked)
    }

    @Test fun `制作回包明确禁止继续即使库存未返回也停止`() = runBlocking {
        val f = Fixture().apply { rejectFurtherCook = true }
        assertEquals(1, f.runner.execute(true, false, false).cooked)
        assertEquals(1, f.calls.count { it == "com.alipay.antfarm.cookAiKitchen" })
    }
}
