package fansirsqi.xposed.sesame.task.goldenbeans

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class GoldenBeansExecutionTest {
    private class Fixture : AutoCloseable {
        var day = "2026-11-13"
        var state = "TODO"
        var rights = 0
        var sends = 0
        var failRead = false
        var reject = false
        var failConfirmation = false
        val saved = mutableMapOf<String, String>()
        init {
            GoldenBeansRuntime.installForTest({ "u" }, { day }, { true }, { })
            GoldenBeansJournal.installForTest({ saved[it].orEmpty() }, { key, value ->
                if (failConfirmation && value.contains("\"state\":\"CONFIRMED\"")) throw IllegalStateException("模拟确认落盘失败")
                saved[key] = value
            })
            GoldenBeansRuntime.begin { true }
        }
        fun transport() {
            goldenbeansRpcCall.setTransportForTest { method, _ ->
                when {
                    method.endsWith(".index") -> """{"success":true}"""
                    method.endsWith(".sync") -> if (failRead) "{}" else JSONObject().put("success", true)
                        .put("taskList", JSONArray().put(JSONObject().put("taskId", "browse").put("sceneCode", "GOLDEN_BEAN_MASTER_TASK")
                            .put("taskStatus", state).put("rightsTimes", rights).put("rightsTimesLimit", 2))).toString()
                    method.contains("finishTask") -> { sends++; if (reject) """{"resultCode":"ILLEGAL_PARAMETER"}""" else """{"success":true}""" }
                    method.contains("receiveTaskAward") -> { sends++; throw IllegalStateException("请求已发但响应丢失") }
                    else -> "{}"
                }
            }
        }
        fun tasks() = GoldenBeansTasks(null, false)
        override fun close() {
            goldenbeansRpcCall.setTransportForTest(null)
            GoldenBeansGameReporter.setTransportForTest(null)
            GoldenBeansRuntime.end()
            GoldenBeansRuntime.clearTestEnvironment()
            GoldenBeansJournal.clearTestStorage()
        }
    }

    @Test fun `受理但阶段无进展不会续发下一阶段且下轮仅回查`() {
        Fixture().use { f ->
            f.transport()
            f.tasks().processEntry(GoldenBeansEntry.FARM, 0, false, false, true)
            assertEquals(1, f.sends)
            f.tasks().processEntry(GoldenBeansEntry.FARM, 0, false, false, true)
            assertEquals(1, f.sends)
        }
    }
    @Test fun `未知状态不能当成阶段进展`() {
        Fixture().use { f ->
            f.transport()
            f.tasks().processEntry(GoldenBeansEntry.FARM, 0, false, false, true)
            f.state = "UNKNOWN"
            assertFalse(f.tasks().processEntry(GoldenBeansEntry.FARM, 0, false, false, true))
            assertEquals(1, f.sends)
        }
    }
    @Test fun `领奖响应丢失跨轮不重发回读已领取可恢复`() {
        Fixture().use { f ->
            f.state = "FINISHED"; f.transport()
            f.tasks().processEntry(GoldenBeansEntry.FARM, 0, false, false, true)
            f.tasks().processEntry(GoldenBeansEntry.FARM, 0, false, false, true)
            assertEquals(1, f.sends)
            f.state = "RECEIVED"
            assertTrue(f.tasks().processEntry(GoldenBeansEntry.FARM, 0, false, false, true))
            assertEquals(1, f.sends)
        }
    }
    @Test fun `会话跨日后不能写入并且新日重新隔离状态`() {
        Fixture().use { f ->
            GoldenBeansStatus.setIntFlagToday("limit", 2)
            f.day = "2026-11-14"
            try { GoldenBeansStatus.setIntFlagToday("limit", 3); fail("应停止旧会话") } catch (_: CancellationException) { }
            GoldenBeansRuntime.end(); GoldenBeansRuntime.begin { true }
            assertEquals(0, GoldenBeansStatus.getIntFlagToday("limit"))
            GoldenBeansStatus.setIntFlagToday("limit", 1)
            assertEquals(1, GoldenBeansStatus.getIntFlagToday("limit"))
        }
    }
    @Test fun `两入口备用任务来源保持H5`() {
        val calls = mutableListOf<JSONObject>()
        goldenbeansRpcCall.setTransportForTest { _, body -> calls += JSONArray(body).getJSONObject(0); "{}" }
        try {
            goldenbeansRpcCall.doFarmTask("browse", "GOLDEN_BEAN_MASTER_TASK")
            goldenbeansRpcCall.doFarmTask("browse", "GOLDEN_BEAN_ZHIMA_LIST")
            assertEquals(listOf("H5", "H5"), calls.map { it.getString("source") })
            assertEquals(listOf("GOLDEN_BEAN_MASTER_TASK", "GOLDEN_BEAN_ZHIMA_LIST"), calls.map { it.getString("sceneCode") })
        } finally { goldenbeansRpcCall.setTransportForTest(null) }
    }
    @Test fun `相同动作恢复仅认关联订单明确拒绝允许下轮重试`() {
        Fixture().use {
            var sends = 0
            var matched = false
            var rejected = false
            val flow = GoldenBeansActions()
            val probe = GoldenBeansActions.Probe { action ->
                if (matched) GoldenBeansActions.confirmed(1) else GoldenBeansActions.unknown()
            }
            val send = GoldenBeansActions.Send { action -> sends++; if (rejected) GoldenBeansActions.rejected() else GoldenBeansActions.unknown() }
            assertEquals(GoldenBeansActions.State.WAITING, flow.execute("mall", "count", JSONObject().put("skuId", "sku"), probe, send))
            assertEquals(GoldenBeansActions.State.WAITING, flow.execute("mall", "count", JSONObject(), probe, send))
            assertEquals(1, sends)
            matched = true
            assertEquals(GoldenBeansActions.State.CONFIRMED, flow.execute("mall", "count", JSONObject(), probe, send))
            assertEquals(1, GoldenBeansStatus.getIntFlagToday("count"))
            assertEquals(GoldenBeansActions.State.CONFIRMED, flow.execute("mall", "count", JSONObject(), probe, send))
            assertEquals(1, GoldenBeansStatus.getIntFlagToday("count"))
            rejected = true
            assertEquals(GoldenBeansActions.State.REJECTED, flow.execute("reject", "", JSONObject(), probe, send))
            assertEquals(GoldenBeansActions.State.REJECTED, flow.execute("reject", "", JSONObject(), probe, send))
            assertEquals(3, sends)
        }
    }
    @Test fun `商城真实链拒绝别的SKU且恢复匹配订单只累计一次`() {
        Fixture().use { f ->
            var requestId = ""
            var matched = false
            goldenbeansRpcCall.setTransportForTest { method, body ->
                when {
                    method.endsWith(".index") -> """{"success":true,"jarInfo":{"jarCount":10}}"""
                    method.contains("exchangeBenefit") -> {
                        f.sends++; requestId = JSONArray(body).getJSONObject(0).getString("requestId")
                        """{"success":true,"canBuy":true}"""
                    }
                    method.contains("queryExchangeOrders") -> JSONObject().put("success", true).put("orderInfos", JSONArray().put(JSONObject()
                        .put("requestId", requestId).put("spuId", "spu").put("skuId", if (matched) "sku" else "other")
                        .put("orderNo", "order").put("orderStatus", "SUCCESS"))).toString()
                    else -> "{}"
                }
            }
            val item = JSONObject().put("spuName", "商品").put("spuId", "spu").put("skuId", "sku").put("cost", 1).put("dayLeft", 2)
            assertFalse(GoldenBeansMall.exchange(0, item, 1))
            assertFalse(GoldenBeansMall.exchange(0, item, 1))
            assertEquals(1, f.sends)
            assertEquals(0, GoldenBeansStatus.getIntFlagToday("goldenBeans::mallExchange::商品"))
            matched = true
            assertTrue(GoldenBeansMall.exchange(0, item, 1))
            assertEquals(1, GoldenBeansStatus.getIntFlagToday("goldenBeans::mallExchange::商品"))
            assertFalse(GoldenBeansMall.exchange(0, item, 1))
            assertEquals(1, f.sends)
        }
    }

    @Test fun `商城明确不可兑解除待确认下轮可重试`() {
        Fixture().use { f ->
            goldenbeansRpcCall.setTransportForTest { method, _ ->
                when {
                    method.endsWith(".index") -> """{"success":true,"jarInfo":{"jarCount":10}}"""
                    method.contains("exchangeBenefit") -> { f.sends++; """{"success":true,"canBuy":false}""" }
                    else -> "{}"
                }
            }
            val item = JSONObject().put("spuName", "商品").put("spuId", "spu").put("skuId", "sku").put("cost", 1).put("dayLeft", 2)
            assertFalse(GoldenBeansMall.exchange(0, item, 1))
            assertFalse(GoldenBeansMall.exchange(0, item, 1))
            assertEquals(2, f.sends)
        }
    }

    private fun exchangeHome() = """{"success":true,"manureExchangeInfo":{"pageOpened":true,"farmOpened":true,"taobaoBinding":true,"currentManure":100,"effectiveExchangeManure":100,"beanReward":1,"remainQuota":100,"minExchangeAmount":1}}"""

    @Test fun `换豆确认落盘失败后用原回执恢复额度不会重复发送`() {
        Fixture().use { f ->
            goldenbeansRpcCall.setTransportForTest { method, _ ->
                when {
                    method.endsWith(".index") || method.endsWith(".sync") -> exchangeHome()
                    method.endsWith(".manureExchange") -> { f.sends++; """{"success":true,"beanDelta":10,"manureCost":10}""" }
                    else -> "{}"
                }
            }
            f.failConfirmation = true
            GoldenBeansExchange.exchangeManure(0, 10)
            assertEquals(1, f.sends)
            f.failConfirmation = false
            GoldenBeansExchange.exchangeManure(0, 10)
            assertEquals(10, GoldenBeansStatus.getIntFlagToday("goldenBeans::manureExchangeBeanAmount"))
            GoldenBeansExchange.exchangeManure(0, 10)
            assertEquals(1, f.sends)
        }
    }

    @Test fun `换豆响应丢失不能把余额快照当到账也不重放`() {
        Fixture().use { f ->
            goldenbeansRpcCall.setTransportForTest { method, _ ->
                when {
                    method.endsWith(".index") || method.endsWith(".sync") -> exchangeHome()
                    method.endsWith(".manureExchange") -> { f.sends++; throw IllegalStateException("响应丢失") }
                    else -> "{}"
                }
            }
            GoldenBeansExchange.exchangeSesame(0, 10)
            GoldenBeansExchange.exchangeSesame(0, 10)
            assertEquals(1, f.sends)
            assertEquals(0, GoldenBeansStatus.getIntFlagToday("goldenBeans::sesameExchangeBeanAmount"))
        }
    }

    @Test fun `营销触发超时跨轮只查同任务不重发`() {
        Fixture().use { f ->
            var removed = false
            goldenbeansRpcCall.setTransportForTest { method, _ ->
                when {
                    method.endsWith(".index") -> """{"success":true,"marketingPopupTask":{"taskId":"popup"}}"""
                    method.endsWith(".trigger") -> { f.sends++; throw IllegalStateException("响应丢失") }
                    method.endsWith(".sync") -> if (removed) """{"success":true}""" else """{"success":true,"marketingPopupTask":{"taskId":"popup"}}"""
                    else -> "{}"
                }
            }
            assertFalse(f.tasks().processEntry(GoldenBeansEntry.FARM, 0, false, true, false))
            assertFalse(f.tasks().processEntry(GoldenBeansEntry.FARM, 0, false, true, false))
            assertEquals(1, f.sends)
            removed = true
            assertTrue(f.tasks().processEntry(GoldenBeansEntry.FARM, 0, false, true, false))
            assertEquals(1, f.sends)
        }
    }

    @Test fun `游戏每次上报后查询权益达标立即停止`() {
        Fixture().use {
            var reports = 0
            var probes = 0
            GoldenBeansGameReporter.setTransportForTest { path, body ->
                if (path == "game/login") JSONObject("""{"code":1,"data":{"token":"test"}}""") else {
                    reports++
                    assertEquals("goldenbean", body.getString("action_finish_channel"))
                    assertEquals("action-0", body.getString("reqId"))
                    JSONObject("""{"code":1}""")
                }
            }
            val reporter = GoldenBeansGameReporter.matchAppId("2060170000356601")!!
            assertEquals(1, reporter.reportSync("测试", 5, "goldenbean", "action") { probes++; true })
            assertEquals(1, reports)
            assertEquals(1, probes)
        }
    }

    @Test fun `游戏执行链上报超时跨轮仅查询不重复上报`() {
        Fixture().use {
            var reports = 0
            goldenbeansRpcCall.setTransportForTest { _, _ ->
                """{"success":true,"gameCenterDrawRights":{"quotaCanUse":0,"usedQuota":0,"quotaLimit":1},"games":[{"appId":"2060170000356601","deliveryBenefitList":[{"benefitType":"IEP_REQUEST","iepTaskId":"play","taskStatus":"TODO","rightTimes":0,"rightTimesLimit":1}]}]}"""
            }
            GoldenBeansGameReporter.setTransportForTest { path, _ ->
                if (path == "game/login") JSONObject("""{"code":1,"data":{"token":"test"}}""") else { reports++; throw IllegalStateException("响应丢失") }
            }
            assertFalse(GoldenBeansGameCenter.run(0))
            assertFalse(GoldenBeansGameCenter.run(0))
            assertEquals(1, reports)
        }
    }

    @Test fun `多阶段仅在权益确实递增后继续下一档`() {
        Fixture().use { f ->
            var rights = 0
            val ids = mutableSetOf<String>()
            goldenbeansRpcCall.setTransportForTest { method, body ->
                when {
                    method.endsWith(".index") -> """{"success":true}"""
                    method.endsWith(".sync") -> JSONObject().put("success", true).put("taskList", JSONArray().put(JSONObject()
                        .put("taskId", "browse").put("sceneCode", "GOLDEN_BEAN_MASTER_TASK")
                        .put("taskStatus", if (rights >= 2) "RECEIVED" else "TODO").put("rightsTimes", rights).put("rightsTimesLimit", 2))).toString()
                    method.contains("finishTask") -> {
                        f.sends++; rights++
                        ids += JSONArray(body).getJSONObject(0).getString("outBizNo")
                        """{"success":true}"""
                    }
                    else -> "{}"
                }
            }
            assertTrue(f.tasks().processEntry(GoldenBeansEntry.FARM, 0, false, false, true))
            assertEquals(2, f.sends)
            assertEquals(2, ids.size)
            assertTrue(f.tasks().processEntry(GoldenBeansEntry.FARM, 0, false, false, true))
            assertEquals(2, f.sends)
        }
    }

    @Test fun `游戏未确认时无关全局计数变化不能换身份重报`() {
        Fixture().use { f ->
            var reports = 0
            var used = 0
            goldenbeansRpcCall.setTransportForTest { _, _ ->
                JSONObject("""{"success":true,"gameCenterDrawRights":{"quotaCanUse":0,"usedQuota":0,"quotaLimit":3},"games":[{"appId":"2060170000356601","deliveryBenefitList":[{"benefitType":"IEP_REQUEST","iepTaskId":"play","taskStatus":"TODO","rightTimes":0,"rightTimesLimit":1}]}]}""")
                    .also { it.getJSONObject("gameCenterDrawRights").put("usedQuota", used) }.toString()
            }
            GoldenBeansGameReporter.setTransportForTest { path, _ ->
                if (path == "game/login") JSONObject("""{"code":1,"data":{"token":"test"}}""") else { reports++; throw IllegalStateException("响应丢失") }
            }
            assertFalse(GoldenBeansGameCenter.run(0))
            used = 1
            assertFalse(GoldenBeansGameCenter.run(0))
            used = 2
            assertFalse(GoldenBeansGameCenter.run(0))
            assertEquals(1, reports)
            val actions = JSONObject(f.saved.values.single()).getJSONObject("actions")
            assertTrue(actions.keys().asSequence().all { key -> actions.getJSONObject(key).getString("state") == "PENDING" })
        }
    }

}
