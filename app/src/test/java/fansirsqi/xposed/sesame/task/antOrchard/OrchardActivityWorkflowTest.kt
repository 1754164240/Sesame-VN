package fansirsqi.xposed.sesame.task.antOrchard

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrchardActivityWorkflowTest {
    @Test
    fun `抽奖协议保留动态场景并区分限定任务领奖`() {
        val draw = OrchardActivityProtocol.enterDraw("活动\"一", "DYNAMIC_SCENE")
        assertEquals("活动\"一", draw.body().getString("activityId"))
        assertEquals("DYNAMIC_SCENE", draw.body().getString("sceneCode"))
        assertEquals("RPC", draw.body().getString("requestType"))
        assertFalse(OrchardActivityProtocol.claimDraw("S", "T").body().has("awardCountForReceive"))
        assertEquals(3, OrchardActivityProtocol.claimOptional("S", "T", 3).body().getInt("awardCountForReceive"))
    }

    @Test
    fun `限定任务完成三阶段并领已完成黑名单任务但过滤交易任务`() = runBlocking {
        var stage = 0
        var finished = false
        val calls = mutableListOf<String>()
        val gateway = gateway { method, body ->
            when {
                method.endsWith("queryOptionalPlay") -> JSONObject().put("success", true).put("taskTriggerPlayInfo", JSONObject().put("taskList", JSONArray().put(
                    JSONObject().put("taskType", "VISIT").put("sceneCode", "PLAY").put("taskStatus", if (finished) "FINISHED" else "TODO")
                        .put("rightsTimes", stage).put("rightsTimesLimit", 3).put("awardCount", 3).put("alreadyReceiveAwardCount", 0).put("bizInfo", JSONObject().put("title", "浏览任务"))
                ).put(JSONObject().put("taskType", "PAY_ORDER").put("sceneCode", "PLAY").put("taskStatus", "TODO").put("bizInfo", JSONObject().put("title", "支付任务")))
                 .put(JSONObject().put("taskType", "BLACK").put("sceneCode", "PLAY").put("taskStatus", "FINISHED").put("awardCount", 1).put("alreadyReceiveAwardCount", if (calls.contains("award:BLACK")) 1 else 0).put("bizInfo", JSONObject().put("title", "已拉黑")))))
                method.endsWith("finishTaskantorchard") -> { calls += "finish:${body.optString("taskType")}"; stage++; finished = stage == 3; JSONObject("""{"success":true}""") }
                method.endsWith("receiveTaskAwardantorchard") -> { calls += "award:${body.optString("taskType")}"; if (body.optString("taskType") == "VISIT") finished = false; JSONObject("""{"success":true}""") }
                else -> JSONObject("""{"success":false}""")
            }
        }
        OrchardActivityWorkflow(gateway, "uid", optionalBlacklist = setOf("已拉黑")).runOptionalPlay()
        assertEquals(3, calls.count { it == "finish:VISIT" })
        assertEquals(1, calls.count { it == "award:BLACK" })
        assertEquals(1, calls.count { it == "award:VISIT" })
        assertFalse(calls.contains("finish:PAY_ORDER"))
    }

    @Test
    fun `动态抽奖成功但余额不减少时停止避免重复抽奖`() = runBlocking {
        var draws = 0
        val gateway = gateway { method, body ->
            when {
                method.endsWith("enterDrawActivityantorchard") -> if (body.optString("activityId").isEmpty()) JSONObject("""{"code":"100000000","drawActivity":{"activityId":"a","name":"寻宝","sceneCode":"NEW_SCENE"}}""") else JSONObject("""{"code":"100000000","drawAsset":{"blance":2}}""")
                method.endsWith("listTaskantorchard") -> JSONObject("""{"success":true,"taskInfoList":[]}""")
                method.endsWith("drawantorchard") -> { draws++; assertEquals("NEW_SCENE", body.getString("sceneCode")); JSONObject("""{"success":true,"drawAsset":{"blance":2}}""") }
                else -> JSONObject("""{"success":true}""")
            }
        }
        OrchardActivityWorkflow(gateway, "uid").runDrawActivities()
        assertEquals(1, draws)
    }

    @Test
    fun `丰收礼包只领取FINISHED阶段且失败不记成功`() = runBlocking {
        var claims = 0
        val gateway = gateway { method, _ ->
            if (method.endsWith("receiveTaskAward")) { claims++; JSONObject("""{"success":false}""") }
            else JSONObject("""{"resultCode":"100","spreadManureActivity":{"spreadManureStage":{"status":"FINISHED","sceneCode":"HARVEST","taskType":"STAGE"}}}""")
        }
        val workflow = OrchardActivityWorkflow(gateway, "uid")
        assertFalse(workflow.claimSpreadStage(JSONObject("""{"spreadManureActivity":{"spreadManureStage":{"status":"TODO"}}}""")))
        assertEquals(0, claims)
        assertFalse(workflow.claimSpreadStage(JSONObject("""{"spreadManureActivity":{"spreadManureStage":{"status":"FINISHED","sceneCode":"HARVEST","taskType":"STAGE"}}}""")))
        assertEquals(1, claims)
    }

    @Test
    fun `扫描双入口去重并保留农场游戏事件和结项上下文`() = runBlocking {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val marked = mutableListOf<String>()
        val gateway = gateway { method, body ->
            calls += method to body
            when {
                method.endsWith("indexFeeds") -> JSONObject("""{"feedsInfoList":[{"itemType":"game","gameItem":{"gameItemId":"game1","gameName":"游戏"},"itemOutExtMap":{"orchardBenefitInfo":"{\"benefitType\":\"FLOAT_BALL_TASK\",\"iepTaskId\":\"task1\",\"iepSceneCode\":\"STAY\",\"bizInfo\":{\"floatBallDuration\":45}}"}}]}""")
                method.endsWith("orchardIndexDelivery") -> JSONObject("""{"indexDeliveryList":[{"contentId":"game1","deliveryBenefitInfo":{"benefitType":"FLOAT_BALL_TASK","iepTaskId":"task1","iepSceneCode":"STAY"}},{"contentId":"bad","deliveryBenefitInfo":{"benefitType":"FLOAT_BALL_TASK","iepTaskId":"bad"}}]}""")
                method.endsWith("finishTask") -> JSONObject("""{"code":"100000000","finishAwardResultVO":{"deltaAwardCount":20}}""")
                else -> JSONObject("""{"success":true}""")
            }
        }
        AntOrchardGameStayTask(gateway, "uid", markDone = { marked += it }).execute()
        assertEquals(listOf("game1"), marked)
        assertEquals(1, calls.count { it.first.endsWith("noticeGame") })
        val event = calls.first { it.first.endsWith("submitEvent") && it.second.optString("eventId") == "game_play" }.second
        assertEquals("nongchangleyuan", event.getJSONObject("eventAttrMap").getString("SCENE_ID"))
        assertEquals("lianyun_nc_sydb", event.getJSONObject("eventAttrMap").getString("CH_INFO"))
        assertEquals(45, calls.first { it.first.endsWith("submitUserPlayDurationAction") }.second.getInt("playTime"))
        val finish = calls.first { it.first.endsWith("finishTask") }.second
        assertEquals("STAY", finish.getString("sceneCode"))
        assertEquals("task1", finish.getString("taskType"))
    }

    @Test
    fun `运行门禁关闭后不再发请求或确认游戏完成`() = runBlocking {
        var requests = 0
        val gateway = object : OrchardActivityGateway {
            override fun canContinue() = false
            override suspend fun request(call: OrchardActivityCall): JSONObject { requests++; return JSONObject("""{"success":true}""") }
            override suspend fun pause(milliseconds: Long) = Unit
        }
        OrchardActivityWorkflow(gateway, "uid").runDrawActivities()
        OrchardActivityWorkflow(gateway, "uid").runOptionalPlay()
        AntOrchardGameStayTask(gateway, "uid").execute()
        assertEquals(0, requests)
    }

    @Test
    fun `限定任务不支持普通结项时走农场场景回退且保留任务场景`() = runBlocking {
        var queried = 0
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val fake = gateway { method, body ->
            calls += method to body
            when {
                method.endsWith("queryOptionalPlay") -> {
                    queried++
                    JSONObject("""{"success":true,"taskTriggerPlayInfo":{"taskList":[{"taskType":"GAME_VISIT","sceneCode":"DYNAMIC_PLAY","taskStatus":"TODO","rightsTimes":0,"rightsTimesLimit":1,"bizInfo":{"title":"游戏浏览"}}]}}""")
                }
                method.endsWith("doFarmTask") -> JSONObject("""{"success":true}""")
                else -> JSONObject("""{"success":false,"code":"400000040"}""")
            }
        }
        OrchardActivityWorkflow(fake, "uid").runOptionalPlay()
        val fallback = calls.single { it.first.endsWith("doFarmTask") }.second
        assertEquals("ORCHARD", fallback.getString("sceneCode"))
        assertEquals("DYNAMIC_PLAY", fallback.getString("taskSceneCode"))
        assertEquals("GAME_VISIT", fallback.getString("bizKey"))
        assertEquals(2, queried)
    }

    @Test
    fun `限定任务领取后允许RECEIVED状态继续下一阶段`() = runBlocking {
        var finishes = 0
        val fake = gateway { method, _ ->
            when {
                method.endsWith("queryOptionalPlay") -> JSONObject("""{"success":true,"taskTriggerPlayInfo":{"taskList":[{"taskType":"NEXT","sceneCode":"PLAY","taskStatus":"RECEIVED","rightsTimes":${finishes + 1},"rightsTimesLimit":3,"bizInfo":{"title":"多档任务"}}]}}""")
                method.endsWith("finishTaskantorchard") -> { finishes++; JSONObject("""{"success":true}""") }
                else -> JSONObject("""{"success":false}""")
            }
        }
        OrchardActivityWorkflow(fake, "uid").runOptionalPlay()
        assertEquals(2, finishes)
    }

    @Test
    fun `抽抽乐同一里程碑需连续完成剩余次数后领取奖励`() = runBlocking {
        var finishes = 0
        var awards = 0
        val fake = gateway { method, body ->
            when {
                method.endsWith("enterDrawActivityantorchard") -> if (body.optString("activityId").isEmpty()) JSONObject("""{"code":"100000000","drawActivity":{"activityId":"multi","sceneCode":"MULTI"}}""") else JSONObject("""{"success":true,"drawAsset":{"blance":0}}""")
                method.endsWith("listTaskantorchard") -> JSONObject("""{"success":true,"taskInfoList":[{"taskBaseInfo":{"taskType":"MULTI_VISIT","sceneCode":"MULTI_TASK","taskStatus":"${if (awards > 0) "RECEIVED" else if (finishes >= 3) "FINISHED" else "TODO"}","bizInfo":"{\"title\":\"三次浏览\"}"},"taskRights":{"rightsTimes":${if (awards > 0) 3 else 0},"rightsTimesLimit":3}}]}""")
                method.endsWith("finishTaskantorchard") -> { finishes++; JSONObject("""{"success":true}""") }
                method.endsWith("receiveTaskAwardantorchard") -> { awards++; JSONObject("""{"success":true}""") }
                else -> JSONObject("""{"success":true}""")
            }
        }
        OrchardActivityWorkflow(fake, "uid").runDrawActivities()
        assertEquals(3, finishes)
        assertEquals(1, awards)
    }

    @Test
    fun `游戏时长上报后账号门禁关闭则不结项不落成功标记`() = runBlocking {
        var active = true
        val methods = mutableListOf<String>()
        val marked = mutableListOf<String>()
        val fake = object : OrchardActivityGateway {
            override fun canContinue() = active
            override suspend fun pause(milliseconds: Long) = Unit
            override suspend fun request(call: OrchardActivityCall): JSONObject {
                methods += call.method
                return when {
                    call.method.endsWith("indexFeeds") -> JSONObject("""{"feedsInfoList":[]}""")
                    call.method.endsWith("orchardIndexDelivery") -> JSONObject("""{"indexDeliveryList":[{"contentId":"a","deliveryBenefitInfo":{"benefitType":"FLOAT_BALL_TASK","iepTaskId":"t","iepSceneCode":"s"}}]}""")
                    call.method.endsWith("submitUserPlayDurationAction") -> { active = false; JSONObject("""{"success":true}""") }
                    else -> JSONObject("""{"success":true}""")
                }
            }
        }
        AntOrchardGameStayTask(fake, "uid", markDone = { marked += it }).execute()
        assertFalse(methods.any { it.endsWith("finishTask") })
        assertTrue(marked.isEmpty())
    }

    @Test
    fun `限定任务累计三次完成后服务端才推进权利次数仍可领奖`() = runBlocking {
        var finishes = 0
        var awards = 0
        val fake = gateway { method, _ ->
            when {
                method.endsWith("queryOptionalPlay") -> JSONObject("""{"success":true,"taskTriggerPlayInfo":{"taskList":[{"taskType":"PLAY_CUMULATIVE","sceneCode":"PLAY_STAGE","taskStatus":"${if (awards > 0) "RECEIVED" else if (finishes >= 3) "FINISHED" else "TODO"}","rightsTimes":${if (finishes >= 3) 3 else 0},"rightsTimesLimit":3,"awardCount":3,"alreadyReceiveAwardCount":${if (awards > 0) 3 else 0},"bizInfo":{"title":"累计游玩三次"}}]}}""")
                method.endsWith("finishTaskantorchard") -> { finishes++; JSONObject("""{"success":true}""") }
                method.endsWith("receiveTaskAwardantorchard") -> { awards++; JSONObject("""{"success":true}""") }
                else -> JSONObject("""{"success":false}""")
            }
        }
        OrchardActivityWorkflow(fake, "uid").runOptionalPlay()
        assertEquals(3, finishes)
        assertEquals(1, awards)
    }

    @Test
    fun `限定任务阶段无推进仅按剩余次数申报且失败立即停止`() = runBlocking {
        for (successful in listOf(true, false)) {
            var finishes = 0
            val fake = gateway { method, _ ->
                when {
                    method.endsWith("queryOptionalPlay") -> JSONObject("""{"success":true,"taskTriggerPlayInfo":{"taskList":[{"taskType":"PLAY_STALE","sceneCode":"PLAY_STAGE","taskStatus":"TODO","rightsTimes":0,"rightsTimesLimit":3,"bizInfo":{"title":"未落态任务"}}]}}""")
                    method.endsWith("finishTaskantorchard") -> { finishes++; JSONObject().put("success", successful) }
                    else -> JSONObject("""{"success":false}""")
                }
            }
            OrchardActivityWorkflow(fake, "uid").runOptionalPlay()
            assertEquals(if (successful) 3 else 1, finishes)
        }
    }

    private fun OrchardActivityCall.body(): JSONObject = JSONArray(arguments).getJSONObject(0)

    private fun gateway(reply: (String, JSONObject) -> JSONObject) = object : OrchardActivityGateway {
        override fun canContinue() = true
        override suspend fun request(call: OrchardActivityCall) = reply(call.method, call.body())
        override suspend fun pause(milliseconds: Long) = Unit
    }
}
