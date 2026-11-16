package fansirsqi.xposed.sesame.task.antDodo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DodoCardPolicyTest {
    @Test fun `万能卡跨图鉴优先未曾获得且不提前停止等级筛选`() {
        val cards = JSONArray("""[
          {"star":1,"animal":{"animalId":"low"},"collectDetail":{"count":0,"hasCollected":false}},
          {"star":3,"animal":{"animalId":"owned"},"collectDetail":{"count":0,"hasCollected":true}},
          {"star":2,"animal":{"animalId":"new"},"collectDetail":{"count":1,"hasCollected":false}}
        ]""")
        assertEquals("new", DodoCardPolicy.selectUniversal(cards, 2, true).getString("animalId"))
        assertEquals("owned", DodoCardPolicy.selectUniversal(cards, 2, false).getString("animalId"))
    }
    @Test fun `图鉴筛选拒绝未知状态并同时匹配三种筛选`() {
        val book = JSONObject("""{"bookStatus":"END","bookCollectedStatus":"NOT_COMPLETED","medalGenerationStatus":"CAN_NOT_GENERATE"}""")
        assertTrue(DodoCardPolicy.matchesBook(book, 1, 1, 1))
        assertFalse(DodoCardPolicy.matchesBook(book, 2, 1, 1))
        assertFalse(DodoCardPolicy.matchesBook(JSONObject(), 0, 0, 0))
    }
    @Test fun `赠卡只选达到最低等级且有明确库存的卡片`() {
        val cards = JSONArray("""[
          {"star":1,"animal":{"animalId":"low"},"collectDetail":{"count":2}},
          {"star":3,"animal":{"animalId":"empty"},"collectDetail":{"count":0}},
          {"star":2,"animal":{"animalId":"gift"},"collectDetail":{"count":2}},
          {"star":3,"animal":{"animalId":"unknown"},"collectDetail":{}}
        ]""")
        assertEquals(listOf("gift"), DodoCardPolicy.giftCards(cards, 2).map { it.getJSONObject("animal").getString("animalId") })
    }
    @Test fun `刮卡仅选择明确可刮未刮完整卡并去重`() {
        val data = JSONObject("""{"animal":{"animalId":"a","bookId":"b"},"collectDetail":{"cardList":[
          {"cardInstanceId":"ok","scratchable":true,"scratched":false},
          {"cardInstanceId":"ok","scratchable":true},
          {"cardInstanceId":"old","scratchable":true,"scratched":true},
          {"cardInstanceId":"unknown"}, {"scratchable":true}]}}""")
        assertEquals(listOf("ok"), DodoCardPolicy.scratchCards(data).map { it.getString("cardInstanceId") })
        assertTrue(DodoCardPolicy.scratchCards(JSONObject()).isEmpty())
    }
    @Test fun `末日抽卡不在未到期或未知到期时间使用`() {
        assertFalse(DodoCardPolicy.shouldUseDraw(1, 0L, 1000L))
        assertFalse(DodoCardPolicy.shouldUseDraw(1, 172801000L, 1000L))
        assertTrue(DodoCardPolicy.shouldUseDraw(1, 86400000L, 1000L))
        assertTrue(DodoCardPolicy.shouldUseDraw(0, 0L, 1000L))
    }
    @Test fun `失败或无效响应不能被认定为成功`() {
        assertFalse(DodoCardPolicy.accepted("{}"))
        assertFalse(DodoCardPolicy.accepted("not-json"))
        assertFalse(DodoCardPolicy.accepted("""{"success":false,"resultCode":"SUCCESS"}"""))
        assertTrue(DodoCardPolicy.accepted("""{"success":true}"""))
    }
}
