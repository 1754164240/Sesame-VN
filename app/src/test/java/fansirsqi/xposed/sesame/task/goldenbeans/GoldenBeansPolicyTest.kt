package fansirsqi.xposed.sesame.task.goldenbeans

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GoldenBeansPolicyTest {
    @Test fun `换豆额度同时遵守库存汇率服务端额度和每日剩余`() {
        assertEquals(40, GoldenBeansPolicy.exchangeAmount(10, 8, 5, 100, 1, 60, 20))
        assertEquals(0, GoldenBeansPolicy.exchangeAmount(10, 8, 5, 100, 50, 60, 20))
        assertEquals(Int.MAX_VALUE, GoldenBeansPolicy.exchangeAmount(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 1, 0, 0))
        assertEquals(0, GoldenBeansPolicy.exchangeAmount(10, 8, 0, 100, 1, 0, 0))
    }
    @Test fun `商城只允许已知余额且无现金附加费用并限制次数`() {
        assertTrue(GoldenBeansPolicy.canExchange(2, 3, false, 1, 2, 1))
        assertFalse(GoldenBeansPolicy.canExchange(2, -1, false, 1, 2, 1))
        assertFalse(GoldenBeansPolicy.canExchange(2, 3, true, 1, 2, 1))
        assertFalse(GoldenBeansPolicy.canExchange(2, 3, false, 0, 2, 1))
        assertFalse(GoldenBeansPolicy.canExchange(2, 3, false, 1, 2, 2))
    }
    @Test fun `领奖失败只有服务端已领状态才算确认`() {
        assertTrue(GoldenBeansPolicy.rewardConfirmed("RECEIVED"))
        assertTrue(GoldenBeansPolicy.rewardConfirmed("HAS_RECEIVED"))
        assertFalse(GoldenBeansPolicy.rewardConfirmed("FINISHED"))
        assertFalse(GoldenBeansPolicy.rewardConfirmed("TODO"))
        assertFalse(GoldenBeansPolicy.rewardConfirmed(""))
    }
    @Test fun `离线验证码暂停取消和切账号均停止`() {
        assertTrue(GoldenBeansPolicy.shouldContinue("a", "a", true, false, false))
        assertFalse(GoldenBeansPolicy.shouldContinue("a", "b", true, false, false))
        assertFalse(GoldenBeansPolicy.shouldContinue("a", "a", false, false, false))
        assertFalse(GoldenBeansPolicy.shouldContinue("a", "a", true, true, false))
        assertFalse(GoldenBeansPolicy.shouldContinue("a", "a", true, false, true))
        assertFalse(GoldenBeansPolicy.shouldContinue("", "", true, false, false))
    }
    @Test fun `双入口协议不会混用场景且兑换量是金豆数`() {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        goldenbeansRpcCall.setTransportForTest { method, body ->
            calls += method to org.json.JSONArray(body).getJSONObject(0)
            "{}"
        }
        try {
            goldenbeansRpcCall.exchangeBeanOf("ZHIMA", "lianjin", 50)
            goldenbeansRpcCall.claimAwardOf("ZHIMA", "lianjin", "GOLDEN_BEAN_ZHIMA_LIST", "test")
            assertEquals(50, calls[0].second.getInt("exchangeBeanAmount"))
            assertEquals("lianjin", calls[0].second.getString("source"))
            assertEquals("GOLDEN_BEAN_ZHIMA_LIST", calls[1].second.getString("sceneCode"))
            assertEquals("ZHIMA", calls[1].second.getJSONObject("bizInfo").getString("bizType"))
        } finally { goldenbeansRpcCall.setTransportForTest(null) }
    }
    @Test fun `商城协议采用独立场景版本与订单来源`() {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        goldenbeansRpcCall.setTransportForTest { method, body ->
            calls += method to org.json.JSONArray(body).getJSONObject(0)
            "{}"
        }
        try {
            goldenbeansRpcCall.mallItems(20, 20)
            goldenbeansRpcCall.mallExchange("spu", "sku")
            goldenbeansRpcCall.mallOrders(1, 20)
            assertEquals("ANTORCHARD_JINDOU_MALL", calls[0].second.getString("sceneCode"))
            assertEquals("20260901.01", calls[0].second.getString("version"))
            assertEquals(20, calls[0].second.getInt("startIndex"))
            assertEquals("MASTER", calls[1].second.getString("source"))
            assertEquals("antorchard", calls[2].second.getString("source"))
            assertEquals("spu", calls[1].second.getString("spuId"))
            assertEquals("sku", calls[1].second.getString("skuId"))
        } finally { goldenbeansRpcCall.setTransportForTest(null) }
    }

    @Test fun `签到回读必须明确今日已签且奖励不接受负数`() {
        assertFalse(GoldenBeansSupport.todaySigned(JSONObject("""{"signInfo":{"signList":[{"today":false,"signed":true}]}}""")))
        assertFalse(GoldenBeansSupport.todaySigned(JSONObject("""{"signInfo":{"signList":[{"today":true,"signed":false}]}}""")))
        assertTrue(GoldenBeansSupport.todaySigned(JSONObject("""{"signInfo":{"signList":[{"today":true,"signed":true}]}}""")))
        assertEquals(7, GoldenBeansSupport.awardCount(JSONObject("""{"beanDelta":7}""")))
        assertEquals("", GoldenBeansSupport.awardText(JSONObject("""{"awardCount":-1}""")))
    }

    @Test fun `矿工和游戏协议不使用商城场景且指定金豆渠道`() {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        goldenbeansRpcCall.setTransportForTest { method, body ->
            calls += method to org.json.JSONArray(body).getJSONObject(0)
            "{}"
        }
        try {
            goldenbeansRpcCall.minerHome()
            goldenbeansRpcCall.grabBean("BEAN", "item")
            goldenbeansRpcCall.fetchGameList()
            goldenbeansRpcCall.drawLottery()
            assertEquals(goldenbeansRpcCall.MINER_PAGE_SOURCE, calls[0].second.getString("source"))
            assertEquals("item", calls[1].second.getString("itemId"))
            assertEquals("GOLDENBEAN", calls[2].second.getString("sceneCode"))
            assertEquals(1, calls[3].second.getInt("batchDrawCount"))
            assertEquals("goldenbean", goldenbeansRpcCall.GAME_CHANNEL)
        } finally { goldenbeansRpcCall.setTransportForTest(null) }
    }

}
