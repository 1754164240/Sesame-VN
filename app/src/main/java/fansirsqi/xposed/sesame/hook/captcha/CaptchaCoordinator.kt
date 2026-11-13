package fansirsqi.xposed.sesame.hook.captcha

import android.os.SystemClock
import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.hook.HookUtil
import fansirsqi.xposed.sesame.hook.RequestManager
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.maps.UserMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** 仅观察真实验证接口，不读取页面图像、不生成答案、不模拟输入。 */
object CaptchaCoordinator {
    private const val TAG = "Captcha"
    private sealed interface Event {
        data class Request(val value: CaptchaObservedRequest) : Event
        data class Reply(val request: CaptchaObservedRequest, val reply: CaptchaReply, val observedAt: Long) : Event
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val policy = CaptchaSessionPolicy()
    private val pendingChallenges = AtomicInteger()
    private var lastPassedSequence = 0L
    private var loader: ClassLoader? = null
    private var installed = false

    @Synchronized fun install(classLoader: ClassLoader) {
        loader = classLoader
        CaptchaRpcObserver.install(classLoader)
        if (installed) return
        installed = true
        scope.launch {
            for (event in events) {
                try {
                    when (event) {
                        is Event.Request -> bind(event.value)
                        is Event.Reply -> try { reply(event.request, event.reply, event.observedAt) }
                            finally { if (event.reply is CaptchaReply.Challenge) pendingChallenges.decrementAndGet() }
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Throwable) { Log.record(TAG, "验证回执处理失败，保持暂停等待手动验证") }
            }
        }
        scope.launch {
            while (isActive) {
                CaptchaRpcObserver.install(classLoader)
                delay(5000)
            }
        }
    }

    internal fun onRequest(request: CaptchaObservedRequest) { events.trySend(Event.Request(request)) }
    internal fun onReply(request: CaptchaObservedRequest, reply: CaptchaReply, observedAt: Long) {
        if (reply is CaptchaReply.Challenge) pendingChallenges.incrementAndGet()
        events.trySend(Event.Reply(request, reply, observedAt))
    }

    @JvmStatic fun hasPendingChallenge(): Boolean = pendingChallenges.get() > 0

    private fun bind(request: CaptchaObservedRequest) {
        if (request.userId != UserMap.currentUid) return
        request.bound = policy.bindRequest(request.userId, request.request.bizNo, request.pageKey,
            RequestManager.currentVerificationToken(), request.createdAt)
    }

    private fun reply(request: CaptchaObservedRequest, reply: CaptchaReply, observedAt: Long) {
        if (request.userId != UserMap.currentUid || actualUid() != request.userId) return
        when (reply) {
            is CaptchaReply.Challenge -> {
                if (request.sequence <= lastPassedSequence || policy.isStaleChallenge(request.sequence)) return
                RequestManager.handleVerificationRequired(CaptchaProtocol.METHOD)
                val token = RequestManager.currentVerificationToken() ?: return
                val previous = policy.current()
                val session = policy.beginChallenge(request.userId, reply.bizNo, reply.fingerprint,
                    request.pageKey, token, request.sequence, SystemClock.elapsedRealtime(), observedAt) ?: return
                if (session.id != previous?.id) Log.record(TAG, "发现安全验证，任务已暂停，请手动完成验证")
            }
            is CaptchaReply.Passed -> {
                val bound = request.bound ?: return
                if (reply.bizNo.isNotBlank() && reply.bizNo != bound.bizNo) return
                val session = policy.acceptPass(bound, UserMap.currentUid,
                    RequestManager.currentVerificationToken(), SystemClock.elapsedRealtime()) ?: return
                lastPassedSequence = maxOf(lastPassedSequence, request.sequence)
                // 等待任务退出时保持事件队列可处理，新挑战会使旧恢复失效。
                scope.launch {
                    val resumed = RequestManager.resumeAfterObservedVerification(session.userId, session.pauseToken, beforeCommit = {
                        withTimeoutOrNull(5000) {
                            while (hasPendingChallenge()) delay(10)
                            true
                        } == true
                    }) {
                        policy.isCurrent(session.id, UserMap.currentUid, RequestManager.currentVerificationToken()) &&
                            actualUid() == session.userId
                    }
                    if (resumed) {
                        Log.record(TAG, "服务端确认手动验证通过，恢复任务调度")
                        ApplicationHook.execHandler()
                    }
                }
            }
        }
    }

    private fun actualUid(): String? = loader?.let { runCatching { HookUtil.getUserId(it) }.getOrNull() }
}
