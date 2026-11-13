package fansirsqi.xposed.sesame.task.antForest

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 授权反射可能阻塞；只允许一个在途调用，不阻塞任务取消，也不堆积重试线程。 */
internal class ReadForestAuthorization {
    private val executor = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { runnable -> Thread(runnable, "forest-read-auth").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())

    suspend fun await(provider: () -> String?): String? = suspendCancellableCoroutine { continuation ->
        try {
            val future = executor.submit {
                try {
                    val code = provider()
                    if (continuation.isActive) continuation.resume(code)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("无纸阅读授权失败"))
                }
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        } catch (_: RejectedExecutionException) {
            continuation.resumeWithException(IOException("上次无纸阅读授权尚未退出"))
        }
    }
}
