package fansirsqi.xposed.sesame.hook

import fansirsqi.xposed.sesame.hook.rpc.intervallimit.DefaultIntervalLimit
import fansirsqi.xposed.sesame.hook.rpc.intervallimit.RpcIntervalLimit
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcIntervalLimitTest {
    @Test
    fun `等待结束后才记录放行时间避免紧邻请求连续发出`() {
        val method = "test.interval.${System.nanoTime()}"
        val limit = DefaultIntervalLimit(100).apply { time = System.currentTimeMillis() }
        RpcIntervalLimit.updateIntervalLimit(method, limit)
        val previous = limit.time

        RpcIntervalLimit.enterIntervalLimit(method)
        assertTrue("放行时间必须包含实际等待时间", limit.time - previous >= 100)

        val first = System.nanoTime()
        RpcIntervalLimit.enterIntervalLimit(method)
        val elapsedMs = (System.nanoTime() - first) / 1_000_000
        assertTrue("下一次请求仍应等待一个完整间隔，实际为${elapsedMs}ms", elapsedMs >= 90)
    }
}
