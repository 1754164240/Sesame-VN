package fansirsqi.xposed.sesame.task.antForest

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReadForestAuthorizationTest {
    @Test fun `授权阻塞时任务可取消并且不堆积新授权线程`() = runBlocking {
        val auth = ReadForestAuthorization()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            auth.await {
                started.countDown()
                // 模拟底层服务忽略中断，任务仍应能取消。
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
                "old-code"
            }
        }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            try { auth.await { "new-code" }; fail("旧授权未退出不应新建线程") }
            catch (_: IOException) { }
        } finally { release.countDown(); job.cancelAndJoin() }
    }
}
