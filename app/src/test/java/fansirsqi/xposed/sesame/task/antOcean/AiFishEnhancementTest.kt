package fansirsqi.xposed.sesame.task.antOcean

import org.junit.Assert.*
import org.junit.Test

class AiFishEnhancementTest {
    @Test fun `默认鱼才开通且失败不继续点赞`() {
        val gateway = Gateway()
        gateway.drawResponse = "{}"
        AiFishEnhancement(gateway).run(true, true)
        assertEquals(1, gateway.draws)
        assertEquals(0, gateway.queries)
        assertFalse(gateway.limited)
    }
    @Test fun `重复鱼只点赞一次且无进展页面停止`() {
        val gateway = Gateway()
        AiFishEnhancement(gateway).run(false, true)
        assertEquals(1, gateway.likes)
        assertEquals(2, gateway.queries)
        assertFalse(gateway.limited)
    }
    @Test fun `画鱼受理但主页仍为默认鱼时不继续点赞`() {
        val gateway = Gateway()
        AiFishEnhancement(gateway).run(true, true)
        assertEquals(1, gateway.draws)
        assertEquals(0, gateway.queries)
    }
    @Test fun `画鱼回查已开通后继续独立点赞`() {
        val gateway = Gateway().apply {
            afterDrawHome = """{"success":true,"fishStatus":"AI_FISH"}"""
        }
        AiFishEnhancement(gateway).run(true, true)
        assertEquals(1, gateway.draws)
        assertEquals(1, gateway.likes)
    }
    @Test fun `未知奖励不能标记已达上限`() {
        val gateway = Gateway()
        gateway.likeResponse = """{"success":true}"""
        AiFishEnhancement(gateway).run(false, true)
        assertFalse(gateway.limited)
        assertEquals(1, gateway.likes)
    }
    @Test fun `明确零次才记录上限且取消不发请求`() {
        val gateway = Gateway()
        gateway.listResponse = """{"success":true,"appraiseRewardRemainNum":0,"fishList":[]}"""
        AiFishEnhancement(gateway).run(false, true)
        assertTrue(gateway.limited)
        val stopped = Gateway().apply { active = false }
        AiFishEnhancement(stopped).run(true, true)
        assertEquals(0, stopped.draws)
        assertEquals(0, stopped.queries)
    }
    @Test fun `默认鱼开通读取状态接口根字段而非主页交互对象`() {
        val gateway = Gateway().apply {
            separateStatus = true
            afterDrawHome = """{"success":true,"fishStatus":"AI_FISH"}"""
        }
        AiFishEnhancement(gateway).run(true, true)
        assertEquals(1, gateway.draws)
        assertEquals(1, gateway.likes)
    }
    @Test fun `评价接收人为空时回退鱼所属用户`() {
        val gateway = Gateway().apply {
            listResponse = """{"success":true,"fishList":[{"imageId":"i","seasonId":"s","userId":"u","appraiseVO":{"canAppraise":true,"receiveAppraiseUserId":""}}]}"""
        }
        AiFishEnhancement(gateway).run(false, true)
        assertEquals(1, gateway.likes)
    }
    private class Gateway : AiFishEnhancement.Gateway {
        var draws = 0; var queries = 0; var likes = 0; var limited = false; var active = true
        var separateStatus = false
        var drawResponse = """{"success":true}"""
        var afterDrawHome = """{"success":true,"fishStatus":"DEFAULT_AI_FISH"}"""
        var likeResponse = """{"success":true,"appraiseRewardList":[{"extInfo":{"popup":{"rightsNums":"1"}}}]}"""
        var listResponse = """{"success":true,"appraiseRewardRemainNum":30,"fishList":[{"imageId":"i","seasonId":"s","userId":"u","appraiseVO":{"canAppraise":true}},{"imageId":"i","seasonId":"s","userId":"u","appraiseVO":{"canAppraise":true}}]}"""
        override fun active() = active
        override fun home() = if (separateStatus) """{"success":true,"myFish":{"interactVO":{}}}""" else if (draws > 0) afterDrawHome else """{"success":true,"myFish":{"fishStatus":"DEFAULT_AI_FISH"}}"""
        override fun status() = if (draws > 0) afterDrawHome else """{"success":true,"fishStatus":"DEFAULT_AI_FISH"}"""
        override fun draw(): String { draws++; return drawResponse }
        override fun list(): String { queries++; return listResponse }
        override fun like(imageId: String, seasonId: String, targetUserId: String): String { likes++; return likeResponse }
        override fun limitedToday() = limited
        override fun markLimited() { limited = true }
        override fun waitMillis(millis: Long) {}
    }
}
