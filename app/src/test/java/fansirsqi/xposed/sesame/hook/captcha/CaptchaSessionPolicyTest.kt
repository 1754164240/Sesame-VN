package fansirsqi.xposed.sesame.hook.captcha

import org.junit.Assert.*
import org.junit.Test

class CaptchaSessionPolicyTest {
    private val policy = CaptchaSessionPolicy()
    private fun challenge(sequence: Long = 1, fingerprint: String = "f", token: String = "t") =
        policy.beginChallenge("u", "b", fingerprint, 7, token, sequence, 0)!!

    @Test fun `匹配当前挑战的真实通过仅接受一次`() {
        val session = challenge()
        val request = policy.bindRequest("u", "b", 7, "t", 10)
        assertEquals(session.id, policy.acceptPass(request, "u", "t", 20)?.id)
        assertNull(policy.acceptPass(request, "u", "t", 21))
    }

    @Test fun `旧挑战和跨账号令牌窗口的通过不能恢复`() {
        challenge()
        val old = policy.bindRequest("u", "b", 7, "t", 10)
        challenge(2, "fresh")
        assertNull(policy.acceptPass(old, "u", "t", 20))
        val current = policy.bindRequest("u", "b", 7, "t", 21)
        assertNull(policy.acceptPass(current, "other", "t", 22))
        assertNull(policy.acceptPass(current, "u", "new-token", 22))
        val otherPage = policy.bindRequest("u", "b", 8, "t", 21)
        assertNull(policy.acceptPass(otherPage, "u", "t", 22))
        assertNull(policy.acceptPass(current, "u", "t", 400_000))
    }

    @Test fun `晚到的旧挑战不覆盖新会话`() {
        val current = challenge(3, "new")
        assertNull(policy.beginChallenge("u", "b", "old", 7, "t", 2, 10))
        assertTrue(policy.isCurrent(current.id, "u", "t"))
    }

    @Test fun `请求必须在发现挑战之后绑定且属于同一暂停世代`() {
        val early = policy.bindRequest("u", "b", 7, null, 0)
        challenge()
        assertNull(policy.acceptPass(early, "u", "t", 10))
        val wrong = policy.bindRequest("u", "other", 7, "t", 10)
        assertNull(policy.acceptPass(wrong, "u", "t", 20))
    }

    @Test fun `挑战排队处理后仍能绑定观察之后发出的验证请求`() {
        val session = policy.beginChallenge("u", "b", "f", 7, "t", 1,
            now = 120, observedAt = 100)!!
        val request = policy.bindRequest("u", "b", 7, "t", 110)
        val early = policy.bindRequest("u", "b", 7, "t", 90)
        assertNull(early.sessionId)
        assertEquals(session.id, policy.acceptPass(request, "u", "t", 130)?.id)
    }

    @Test fun `队列中已经过期或时钟倒退的挑战不能新建有效会话`() {
        assertNull(policy.beginChallenge("u", "b", "f", 7, "t", 1,
            now = 300_100, observedAt = 100))
        assertNull(policy.beginChallenge("u", "b", "f", 7, "t", 2,
            now = 100, observedAt = 120))
    }

}
