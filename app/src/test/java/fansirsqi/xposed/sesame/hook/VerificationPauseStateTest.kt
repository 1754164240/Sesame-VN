package fansirsqi.xposed.sesame.hook

import org.junit.Assert.*
import org.junit.Test

class VerificationPauseStateTest {
    @Test fun `新进程不继承上个进程尚未通过的验证暂停`() {
        val previousProcess = VerificationPauseState()
        previousProcess.pause("user")
        assertNotNull(previousProcess.tokenFor("user"))
        val restartedProcess = VerificationPauseState()
        assertNull(restartedProcess.tokenFor("user"))
    }

    @Test fun `同一进程内重新初始化仍保留本次暂停令牌`() {
        val state = VerificationPauseState()
        val token = state.pause("user")
        assertEquals(token, state.tokenFor("user"))
        assertEquals(token, state.pause("user"))
    }

    @Test fun `各账号独立且旧令牌不能解除新一轮暂停`() {
        val state = VerificationPauseState()
        val old = state.pause("first")
        val second = state.pause("second")
        assertFalse(state.finish("first", second))
        assertTrue(state.finish("first", old))
        val fresh = state.pause("first")
        assertNotEquals(old, fresh)
        assertFalse(state.finish("first", old))
        assertEquals(fresh, state.tokenFor("first"))
        assertEquals(second, state.tokenFor("second"))
    }

    @Test fun `真实通过仅能结束对应暂停一次`() {
        val state = VerificationPauseState()
        val token = state.pause("user")
        assertTrue(state.finish("user", token))
        assertNull(state.tokenFor("user"))
        assertFalse(state.finish("user", token))
        assertNull(state.tokenFor(null))
    }
}
