package fansirsqi.xposed.sesame.hook

import android.Manifest
import androidx.annotation.RequiresPermission
import fansirsqi.xposed.sesame.entity.RpcEntity
import fansirsqi.xposed.sesame.hook.rpc.bridge.RpcBridge
import fansirsqi.xposed.sesame.hook.captcha.CaptchaCoordinator
import fansirsqi.xposed.sesame.model.BaseModel
import fansirsqi.xposed.sesame.util.CoroutineUtils
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.NetworkUtils
import fansirsqi.xposed.sesame.util.Notify
import fansirsqi.xposed.sesame.util.TimeUtil
import fansirsqi.xposed.sesame.util.maps.UserMap
import fansirsqi.xposed.sesame.task.ModelTask
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * RPC 请求管理器 (带熔断与兜底机制)
 */
object RequestManager {

    private const val TAG = "RequestManager"
    const val EMPTY_RPC_RESPONSE =
        """{"success":false,"resultCode":"EMPTY_RPC_RESPONSE","resultDesc":"RPC返回为空"}"""
    const val VERIFICATION_REQUIRED_RESPONSE =
        """{"success":false,"resultCode":"RPC_VERIFICATION_REQUIRED","resultDesc":"触发安全验证，请人工验证后继续"}"""

    private val recoveryPolicy = RpcRecoveryPolicy()
    private val verificationPauseState = VerificationPauseState()
    private val verificationResumeMutex = Mutex()

    @JvmStatic
    fun isVerificationPaused(): Boolean = recoveryPolicy.blockReason == RpcBlockReason.VERIFICATION

    @JvmStatic
    fun isEmptyRpcResponse(result: String?): Boolean {
        return result.isNullOrBlank()
    }

    @JvmStatic
    fun isVerificationRequired(errorCode: String?, errorMessage: String?): Boolean {
        val message = errorMessage.orEmpty()
        return errorCode == "1009" ||
            message.contains("为保障您的正常访问，请进行验证后继续") ||
            message.contains("为了保障您的操作安全，请进行验证后继续") ||
            message.contains("请进行验证后继续")
    }

    @JvmStatic
    @Synchronized
    fun handleVerificationRequired(method: String?) {
        if (ApplicationHook.offline && recoveryPolicy.blockReason == RpcBlockReason.NONE) recoveryPolicy.onExternalOffline()
        ApplicationHook.setOffline(true)
        if (recoveryPolicy.onVerificationRequired() != RecoveryDecision.WAIT_FOR_MANUAL_VERIFICATION) {
            return
        }

        UserMap.currentUid?.let(verificationPauseState::pause)
        // 也取消主调度流程，避免其等待中的下一批任务在解除暂停后继续启动。
        ApplicationHook.mainTask?.stopTask()
        ModelTask.stopAllTask()
        Log.record(TAG, "检测到安全验证，暂停后续RPC请求: $method")
    }

    @Synchronized
    fun currentVerificationToken(): String? {
        if (!isVerificationPaused()) return null
        val uid = UserMap.currentUid ?: return null
        return verificationPauseState.tokenFor(uid)
    }

    /** 内部真实回执入口，不通过可外发广播传入任何“通过”标记。 */
    suspend fun resumeAfterObservedVerification(userId: String, token: String, beforeCommit: suspend () -> Boolean, stillCurrent: () -> Boolean): Boolean =
        finishVerification(userId, token, "服务端回执确认完成验证", beforeCommit, stillCurrent)

    private suspend fun finishVerification(userId: String, token: String, message: String, beforeCommit: suspend () -> Boolean = { true }, stillCurrent: () -> Boolean): Boolean =
        verificationResumeMutex.withLock {
            fun valid() = UserMap.currentUid == userId && currentVerificationToken() == token && stillCurrent()
            if (!valid()) return@withLock false
            // 等待期间不持有对象监视器，也不阻塞验证接口回调或主线程。
            val stopped = withTimeoutOrNull(15_000) {
                ApplicationHook.mainTask?.stopTaskAndJoin()
                ModelTask.stopAllTaskAndJoin()
                true
            } == true
            if (!stopped) {
                Log.record(TAG, "旧任务未能及时退出，保留验证暂停")
                return@withLock false
            }
            if (!beforeCommit()) return@withLock false
            synchronized(this) {
                if (!valid()) return@synchronized false
                if (!verificationPauseState.finish(userId, token)) return@synchronized false
                recoveryPolicy.finishVerification()
                val blocked = recoveryPolicy.blockReason != RpcBlockReason.NONE
                ApplicationHook.setOffline(blocked)
                Log.record(TAG, if (blocked) "$message，仍保留其他离线原因" else "$message，恢复自动任务")
                !blocked
            }
        }

    /**
     * 核心执行函数 (内联优化)
     * 流程：离线检查 -> 获取 Bridge -> 执行请求 -> 结果校验 -> 错误计数/重置
     */
    private inline fun executeRpc(methodLog: String?, block: (RpcBridge) -> String?): String {
        // 独立监听已收到挑战而串行暂停事件尚未处理时，也禁止新业务请求穿过。
        if (CaptchaCoordinator.hasPendingChallenge()) return VERIFICATION_REQUIRED_RESPONSE
        // 1. 【前置检查】如果已经离线，直接中断并尝试恢复
        if (ApplicationHook.offline) {
            recoveryPolicy.handleExternalOffline(::handleOfflineRecovery)
            return blockedResponse()
        }

        // 2. 获取 Bridge (包含网络检查)
        // 如果这里获取失败，也视为一次错误
        val bridge = getRpcBridge()
        if (bridge == null) {
            handleFailure("Network/Bridge Unavailable", "网络或Bridge不可用")
            return EMPTY_RPC_RESPONSE
        }

        // 3. 执行请求
        val result = try {
            block(bridge)
        } catch (e: Throwable) {
            Log.printStackTrace(TAG, "RPC 执行异常: $methodLog", e)
            null // 异常视为 null，触发失败逻辑
        }

        // 在途请求可能在验证后才结束，不能按普通空响应处理。
        if (ApplicationHook.offline) return blockedResponse()

        // 4. 结果校验与状态维护
        if (isEmptyRpcResponse(result)) {
            // 失败：增加计数，检查兜底
            handleFailure(methodLog ?: "Unknown", "返回数据为空")
            return EMPTY_RPC_RESPONSE
        }

        if (result == VERIFICATION_REQUIRED_RESPONSE || ApplicationHook.offline) {
            return blockedResponse()
        }

        val hadFailure = recoveryPolicy.failureCount > 0 ||
            recoveryPolicy.blockReason != RpcBlockReason.NONE
        recoveryPolicy.onSuccess()
        if (isVerificationPaused()) return blockedResponse()
        if (hadFailure) {
            Log.record(TAG, "RPC 恢复正常，错误计数重置")
        }
        return result.orEmpty()
    }

    /**
     * 处理失败逻辑：计数、报警、熔断
     */
    private fun handleFailure(method: String, reason: String) {
        val maxCount = BaseModel.setMaxErrorCount.value
        val decision = recoveryPolicy.onNetworkFailure(maxCount)
        val currentCount = recoveryPolicy.failureCount

        Log.error(TAG, "RPC 失败 ($currentCount/$maxCount) | Method: $method | Reason: $reason")

        if (decision == RecoveryDecision.SCHEDULE_REOPEN) {
            Log.record(TAG, "🔴 连续失败次数达到阈值，触发熔断兜底机制！")
            ApplicationHook.setOffline(true)
            if (BaseModel.errNotify.value) {
                val msg = "${TimeUtil.getTimeStr()} | 网络异常次数超过阈值[$maxCount]"
                Notify.sendNewNotification(msg, "RPC 连续失败，脚本已暂停")
            }
            handleOfflineRecovery()
        }
    }

    private fun blockedResponse(): String {
        recoveryPolicy.onBlockedRequest()
        return if (recoveryPolicy.blockReason == RpcBlockReason.VERIFICATION) {
            VERIFICATION_REQUIRED_RESPONSE
        } else {
            EMPTY_RPC_RESPONSE
        }
    }

    @JvmStatic
    @Synchronized
    fun onRpcBridgeReady() {
        val uid = UserMap.currentUid
        val pausedInThisProcess = verificationPauseState.tokenFor(uid) != null
        // 配置重载仍属于当前进程，不能把仍待完成的验证或其网络阻断原因清掉。
        if (pausedInThisProcess && isVerificationPaused()) {
            ApplicationHook.setOffline(true)
            return
        }
        recoveryPolicy.reset()
        if (pausedInThisProcess) {
            recoveryPolicy.onVerificationRequired()
            ApplicationHook.setOffline(true)
            Log.record(TAG, "保留当前进程的安全验证暂停，等待验证通过回执")
        }
    }

    /**
     * 处理离线恢复逻辑
     * 可以是发送广播、拉起 App 等
     */
    private fun handleOfflineRecovery() {
        // 防止短时间内频繁触发恢复逻辑 (可选)
        // 这里简单实现：尝试拉起支付宝或发送重登录广播

        Log.record(TAG, "正在尝试执行离线恢复策略...")
        // 策略 A: 重新拉起 App (推荐)
        ApplicationHook.reOpenApp()
        // 策略 B: 发送重登录广播 (如果宿主还能响应广播)
        // ApplicationHook.reLoginByBroadcast()
    }

    /**
     * 获取 RpcBridge 实例
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun getRpcBridge(): RpcBridge? {
        if (!NetworkUtils.isNetworkAvailable()) {
            Log.record(TAG, "网络不可用，尝试等待 5秒...")
            CoroutineUtils.sleepCompat(5000)
            if (!NetworkUtils.isNetworkAvailable()) {
                return null
            }
        }

        var bridge = ApplicationHook.rpcBridge
        if (bridge == null) {
            Log.record(TAG, "RpcBridge 未初始化，尝试等待 5秒...")
            CoroutineUtils.sleepCompat(5000)
            bridge = ApplicationHook.rpcBridge
        }

        return bridge
    }

    // ================== 公开 API (保持不变) ==================

    @JvmStatic
    fun requestString(rpcEntity: RpcEntity): String {
        return executeRpc(rpcEntity.methodName) { bridge ->
            bridge.requestString(rpcEntity, 3, 1200)
        }
    }

    @JvmStatic
    fun requestString(rpcEntity: RpcEntity, tryCount: Int, retryInterval: Int): String {
        return executeRpc(rpcEntity.methodName) { bridge ->
            bridge.requestString(rpcEntity, tryCount, retryInterval)
        }
    }

    @JvmStatic
    fun requestString(method: String?, data: String?): String {
        return executeRpc(method) { bridge ->
            bridge.requestString(method, data)
        }
    }

    @JvmStatic
    fun requestString(method: String?, data: String?, relation: String?): String {
        return executeRpc(method) { bridge ->
            bridge.requestString(method, data, relation)
        }
    }

    @JvmStatic
    fun requestString(
        method: String?,
        data: String?,
        appName: String?,
        methodName: String?,
        facadeName: String?
    ): String {
        return executeRpc(method) { bridge ->
            bridge.requestString(method, data, appName, methodName, facadeName)
        }
    }

    @JvmStatic
    fun requestString(method: String?, data: String?, tryCount: Int, retryInterval: Int): String {
        return executeRpc(method) { bridge ->
            bridge.requestString(method, data, tryCount, retryInterval)
        }
    }

    @JvmStatic
    fun requestString(
        method: String?,
        data: String?,
        relation: String?,
        tryCount: Int,
        retryInterval: Int
    ): String {
        return executeRpc(method) { bridge ->
            bridge.requestString(method, data, relation, tryCount, retryInterval)
        }
    }

    @JvmStatic
    fun requestObject(rpcEntity: RpcEntity?, tryCount: Int, retryInterval: Int) {
        if (rpcEntity == null) return
        // requestObject 不涉及返回值判断，但同样需要离线检查
        if (ApplicationHook.offline) {
            recoveryPolicy.handleExternalOffline(::handleOfflineRecovery)
            return
        }

        val bridge = getRpcBridge()
        if (bridge == null) {
            handleFailure("requestObject", "Bridge Unavailable")
            return
        }

        try {
            bridge.requestObject(rpcEntity, tryCount, retryInterval)
            recoveryPolicy.onRequestCompletedWithoutResponse()
        } catch (e: Throwable) {
            Log.printStackTrace(TAG, "requestObject 异常: ${rpcEntity.methodName}", e)
            handleFailure(rpcEntity.methodName ?: "Unknown", "Exception")
        }
    }
}
