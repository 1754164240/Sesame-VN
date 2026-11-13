package fansirsqi.xposed.sesame.hook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class RpcRecoveryPolicyTest {

    @Test
    fun `完成验证不能解除之前已经存在的网络阻断`() {
        val policy = RpcRecoveryPolicy()
        policy.onNetworkFailure(1)
        policy.onVerificationRequired()
        policy.finishVerification()
        assertEquals(RpcBlockReason.NETWORK, policy.blockReason)
    }

    @Test
    fun `完成单独验证后可再次检测新挑战`() {
        val policy = RpcRecoveryPolicy()
        policy.onVerificationRequired()
        policy.finishVerification()
        assertEquals(RpcBlockReason.NONE, policy.blockReason)
        assertEquals(RecoveryDecision.WAIT_FOR_MANUAL_VERIFICATION, policy.onVerificationRequired())
    }

    @Test
    fun `验证后晚到的成功响应不能解除暂停`() {
        val policy = RpcRecoveryPolicy()
        policy.onVerificationRequired()
        policy.onSuccess()
        assertEquals(RpcBlockReason.VERIFICATION, policy.blockReason)
    }

    @Test
    fun `只有显式重置才解除验证暂停并允许下一次验证通知`() {
        val policy = RpcRecoveryPolicy()
        policy.onVerificationRequired()
        policy.onSuccess()
        assertEquals(RecoveryDecision.NONE, policy.onExternalOffline())
        policy.reset()
        assertEquals(RpcBlockReason.NONE, policy.blockReason)
        assertEquals(RecoveryDecision.WAIT_FOR_MANUAL_VERIFICATION, policy.onVerificationRequired())
    }

    @Test
    fun `网络失败达到阈值后只调度一次恢复`() {
        val policy = RpcRecoveryPolicy()

        assertEquals(RecoveryDecision.NONE, policy.onNetworkFailure(2))
        assertEquals(RecoveryDecision.SCHEDULE_REOPEN, policy.onNetworkFailure(2))
        assertEquals(RecoveryDecision.NONE, policy.onBlockedRequest())
        assertEquals(RpcBlockReason.NETWORK, policy.blockReason)
    }

    @Test
    fun `并发离线请求也只会产生一次恢复决定`() {
        val policy = RpcRecoveryPolicy()
        val executor = Executors.newFixedThreadPool(8)

        val decisions = try {
            executor.invokeAll(
                (1..32).map {
                    Callable { policy.onExternalOffline() }
                }
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, decisions.count { it == RecoveryDecision.SCHEDULE_REOPEN })
    }

    @Test
    fun `并发外部离线只执行一次恢复动作`() {
        val policy = RpcRecoveryPolicy()
        val executor = Executors.newFixedThreadPool(8)
        val recoveryCount = AtomicInteger()

        try {
            executor.invokeAll(
                (1..32).map {
                    Callable {
                        policy.handleExternalOffline {
                            recoveryCount.incrementAndGet()
                        }
                    }
                }
            ).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, recoveryCount.get())
    }

    @Test
    fun `安全验证只通知一次且不调度重开`() {
        val policy = RpcRecoveryPolicy()

        assertEquals(RecoveryDecision.WAIT_FOR_MANUAL_VERIFICATION, policy.onVerificationRequired())
        assertEquals(RecoveryDecision.NONE, policy.onVerificationRequired())
        assertEquals(RecoveryDecision.NONE, policy.onBlockedRequest())
        assertEquals(RpcBlockReason.VERIFICATION, policy.blockReason)
    }

    @Test
    fun `成功响应会清空阻断状态和失败计数`() {
        val policy = RpcRecoveryPolicy()
        policy.onNetworkFailure(1)

        policy.onSuccess()

        assertEquals(RpcBlockReason.NONE, policy.blockReason)
        assertEquals(0, policy.failureCount)
        assertEquals(RecoveryDecision.NONE, policy.onNetworkFailure(2))
    }

    @Test
    fun `无返回值请求完成不能重置失败计数`() {
        val policy = RpcRecoveryPolicy()
        policy.onNetworkFailure(3)

        policy.onRequestCompletedWithoutResponse()

        assertEquals(1, policy.failureCount)
    }
}
