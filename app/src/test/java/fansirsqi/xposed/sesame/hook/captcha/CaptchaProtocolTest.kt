package fansirsqi.xposed.sesame.hook.captcha

import org.junit.Assert.*
import org.junit.Test

class CaptchaProtocolTest {
    @Test fun `外层成功不能把挑战判为通过`() {
        val reply = CaptchaProtocol.parseReply("""{"errorCode":"00000","data":{"result":"captcha","bizNo":"b","captchaConfig":{"interaction":"Puzzle","payload":"p"}}}""")
        assertTrue(reply is CaptchaReply.Challenge)
        assertEquals("Puzzle", (reply as CaptchaReply.Challenge).interaction)
        assertTrue(CaptchaProtocol.parseReply("""{"errorCode":"00000","data":{"result":"pass"}}""") is CaptchaReply.Passed)
        assertNull(CaptchaProtocol.parseReply("""{"errorCode":"10000","data":{"result":"pass"}}"""))
        assertNull(CaptchaProtocol.parseReply("""{"errorCode":"00000"}"""))
    }

    @Test fun `解析真实抓包中的请求包装而不保留敏感载荷`() {
        val request = CaptchaProtocol.parseRequest("""{"requestData":"[{\"bizNo\":\"b\",\"scene\":\"Puzzle\",\"payload\":{\"cipher\":\"secret\"}}]"}""")
        assertEquals("b", request.bizNo)
        assertEquals("Puzzle", request.scene)
        assertFalse(request.toString().contains("secret"))
    }
}
