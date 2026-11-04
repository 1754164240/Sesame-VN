package fansirsqi.xposed.sesame.task.antForest

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForestPatrolTest {
    private class Server {
        var region = "a"
        var dice = 1
        var rolls = 0
        var confirms = 0
        var awards = 0
        var finishes = 0
        var switched = mutableListOf<String>()
        var tasks = false
        var received = false
        var finished = false
        var failConfirm = false
        var throwConfirm = false
        var missingEventId = false
        var stagnantDice = false
        var malformedRoll = false
        var fullRegions = setOf<String>()
        var emptyCreatures = false
        var missingMax = false
        var otherMapIncomplete = false
        var stepTodo = false
        var unavailableRegion = false
        var failRoll = false
        var failingTaskMethod = ""
        private val requestIds = mutableSetOf<String>()

        fun creatures(): JSONArray = when {
            emptyCreatures -> JSONArray()
            missingMax -> JSONArray("""[{"level":5}]""")
            region in fullRegions -> JSONArray("""[{"level":5,"maxLevel":5},{"level":3,"maxLevel":3}]""")
            else -> JSONArray("""[{"level":5,"maxLevel":5},{"level":4,"maxLevel":5}]""")
        }

        fun call(method: String, p: JSONObject): JSONObject {
            if (method.substringAfterLast('.') == failingTaskMethod) return JSONObject().put("success", false)
            if (method.startsWith("alipay.antisle.monopoly")) {
                assertEquals("monopoly_home_animal", p.getString("source"))
                assertTrue(requestIds.add(p.getString("uniqueId")))
            }
            val res = JSONObject().put("success", true)
            when (method.substringAfterLast('.')) {
                "queryMonopolyEntryInfo" -> res.put("totalDiceCount", dice)
                    .put("userInfo", JSONObject().put("currentRegionCode", region).put("currentMapCode", "$region#lv1"))
                    .put("mapInfo", JSONObject().put("creatureList", creatures()))
                "triggerHomePageProps" -> Unit
                "listTaskopengreen" -> {
                    assertEquals(region, p.getJSONObject("extend").getString("regionCode"))
                    assertEquals("Asia/Shanghai", p.getString("zoneId"))
                    val list = JSONArray()
                    if (tasks) {
                        list.put(JSONObject().put("taskBaseInfo", JSONObject()
                            .put("taskType", "XZBSDH").put("taskStatus", if (stepTodo) "TODO" else if (received) "RECEIVED" else "FINISHED")
                            .put("taskMode", "ACC_ANTIEP").put("taskProdPlayType", "VISIT_FLOAT_BALL")
                            .put("sceneCode", "ANTFOREST_MONOPOLY_TASK_HSDWY")))
                        list.put(JSONObject().put("taskBaseInfo", JSONObject()
                            .put("taskType", "SHENQIWUZHONG_LIULAN")
                            .put("taskStatus", if (received) "RECEIVED" else if (finished) "FINISHED" else "TODO")
                            .put("taskMode", "NORMAL").put("taskProdPlayType", "VISIT_FLOAT_BALL")
                            .put("prodPlayParam", "{\"timeCount\":5}")
                            .put("sceneCode", "ANTFOREST_MONOPOLY_TASK_HSDWY")))
                    }
                    res.put("taskInfoList", list)
                }
                "finishTaskopengreen" -> {
                    assertEquals("H5", p.getString("requestType"))
                    assertEquals("ANTFOREST", p.getString("source"))
                    assertFalse(p.getString("taskType") == "XZBSDH")
                    finishes++; finished = true
                }
                "receiveTaskAwardopengreen" -> {
                    assertFalse(p.getBoolean("ignoreLimit"))
                    awards++
                    if (p.getString("taskType") == "SHENQIWUZHONG_LIULAN") received = true
                    // 故意不增加骰子，验证不能根据领奖接口推算次数。
                    res.put("incAwardCount", 1)
                }
                "rollDice" -> {
                    assertTrue(dice > 0)
                    rolls++
                    if (failRoll) return JSONObject().put("success", false).put("resultDesc", "次数不足")
                    if (malformedRoll) return res
                    if (!stagnantDice) dice--
                    res.put("totalDiceCount", dice).put("diceNumber", 2)
                        .put("eventInfo", JSONObject().put("needConfirm", true).put("eventId", if (missingEventId) "" else "event-$rolls"))
                }
                "eventConfirm" -> {
                    confirms++
                    if (throwConfirm) throw IllegalStateException("确认探索响应解析失败")
                    assertEquals("CONFIRMED", p.getString("status"))
                    assertEquals("confirm", p.getJSONObject("decisionData").getString("actionKey"))
                    assertEquals("ROLL_DICE_STEP", p.getJSONObject("decisionData").getString("source"))
                    res.put("success", !failConfirm)
                }
                "queryMonopolyMapPage" -> {
                    val maps = JSONArray().put(JSONObject().put("creatureList", creatures()))
                    if (otherMapIncomplete) maps.put(JSONObject().put("mapAvailability", "LOCKED")
                        .put("creatureList", JSONArray("""[{"maxLevel":5}]""")))
                    res.put("currentRegionMapList", maps).put("regionList", JSONArray()
                        .put(JSONObject().put("regionCode", "a").put("regionAvailability", "AVAILABLE"))
                        .put(JSONObject().put("regionCode", "b").put("regionAvailability", if (unavailableRegion) "PREPARING" else "AVAILABLE")))
                }
                "switchMonopolyRegion" -> {
                    region = p.getString("regionCode"); switched.add(region)
                }
                else -> fail("未预期接口：$method")
            }
            return res
        }

        fun run() = ForestPatrol(::call, {}, {}).run()
    }

    @Test fun `未满级继续摇骰并确认事件直到次数耗尽`() {
        val s = Server(); s.dice = 2; s.run()
        assertEquals(2, s.rolls); assertEquals(2, s.confirms); assertTrue(s.switched.isEmpty())
    }

    @Test fun `步数只领奖浏览任务完成后复查再领奖且不虚增骰子`() {
        val s = Server(); s.tasks = true; s.run()
        assertEquals(1, s.finishes); assertEquals(2, s.awards); assertEquals(1, s.rolls)
    }

    @Test fun `全部满级即使没有骰子也切换到其他保护地`() {
        val s = Server(); s.fullRegions = setOf("a"); s.dice = 0; s.run()
        assertEquals(listOf("b"), s.switched)
    }

    @Test fun `切换后继续使用新保护地次数摇骰`() {
        val s = Server(); s.fullRegions = setOf("a"); s.run()
        assertEquals(listOf("b"), s.switched); assertEquals(1, s.rolls)
    }

    @Test fun `步数不足时不伪造完成进度也不领取步数奖励`() {
        val s = Server(); s.tasks = true; s.stepTodo = true; s.run()
        assertEquals(1, s.finishes); assertEquals(1, s.awards)
    }

    @Test fun `不切换到筹备中的保护地`() {
        val s = Server(); s.fullRegions = setOf("a"); s.unavailableRegion = true; s.run()
        assertTrue(s.switched.isEmpty()); assertEquals(0, s.rolls)
    }

    @Test fun `摇骰失败后不重试消耗型请求`() {
        val s = Server(); s.dice = 3; s.failRoll = true; s.run()
        assertEquals(1, s.rolls); assertEquals(0, s.confirms)
    }

    @Test fun `所有保护地满级后停止避免往返切换`() {
        val s = Server(); s.fullRegions = setOf("a", "b"); s.run()
        assertEquals(listOf("b"), s.switched); assertEquals(0, s.rolls)
    }

    @Test fun `空动物列表和缺失最高等级不得判定满级`() {
        val empty = Server(); empty.emptyCreatures = true; empty.run(); assertTrue(empty.switched.isEmpty())
        val missing = Server(); missing.missingMax = true; missing.run(); assertTrue(missing.switched.isEmpty())
    }

    @Test fun `当前地图满级但其他地图未满级时不切换保护地`() {
        val s = Server(); s.fullRegions = setOf("a"); s.otherMapIncomplete = true; s.run()
        assertTrue(s.switched.isEmpty()); assertEquals(0, s.rolls)
    }

    @Test fun `任务列表失败后停止不消耗骰子`() {
        val s = Server(); s.failingTaskMethod = "listTaskopengreen"; s.run()
        assertEquals(0, s.rolls)
    }

    @Test fun `完成任务失败后停止不消耗骰子`() {
        val s = Server(); s.tasks = true; s.failingTaskMethod = "finishTaskopengreen"; s.run()
        assertEquals(0, s.rolls)
    }

    @Test fun `领奖失败后停止不消耗骰子`() {
        val s = Server(); s.tasks = true; s.failingTaskMethod = "receiveTaskAwardopengreen"; s.run()
        assertEquals(0, s.rolls)
    }

    @Test fun `事件确认失败后继续摇骰直到次数耗尽`() {
        val s = Server(); s.dice = 3; s.failConfirm = true; s.run()
        assertEquals(3, s.rolls); assertEquals(3, s.confirms)
    }

    @Test fun `事件确认抛出异常也继续摇骰`() {
        val s = Server(); s.dice = 3; s.throwConfirm = true; s.run()
        assertEquals(3, s.rolls); assertEquals(3, s.confirms)
    }

    @Test fun `确认事件缺少编号时记录后继续摇骰`() {
        val s = Server(); s.dice = 3; s.missingEventId = true; s.run()
        assertEquals(3, s.rolls); assertEquals(0, s.confirms)
    }

    @Test fun `骰子响应缺失事件信息时停止`() {
        val s = Server(); s.dice = 3; s.malformedRoll = true; s.run()
        assertEquals(1, s.rolls)
    }

    @Test fun `服务端次数不变时仍有循环上限`() {
        val s = Server(); s.stagnantDice = true; s.run()
        assertTrue(s.rolls in 1..200)
    }
}
