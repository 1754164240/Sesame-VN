package fansirsqi.xposed.sesame.hook.captcha

import android.os.SystemClock
import fansirsqi.xposed.sesame.hook.HookUtil
import fansirsqi.xposed.sesame.hook.modern.ModernXposedRuntime
import fansirsqi.xposed.sesame.util.Log
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong

internal data class CaptchaObservedRequest(
    val sequence: Long, val userId: String, val pageKey: Int, val request: CaptchaRequestData, val createdAt: Long,
    var bound: CaptchaBoundRequest? = null
)

/** 验证专用轻量观察，不依赖通用抓包，不改变请求、响应和原验证组件行为。 */
internal object CaptchaRpcObserver {
    private const val TAG = "CaptchaObserver"
    private val callbacks = CaptchaCallbackStore<CaptchaObservedRequest>()
    private val sequence = AtomicLong()
    private val hookedCallbacks = HashSet<Method>()
    private var rpcInstalled = false
    private var lastInstall = -10_000L

    @Synchronized fun install(loader: ClassLoader) {
        if (rpcInstalled || SystemClock.elapsedRealtime() - lastInstall < 5000) return
        lastInstall = SystemClock.elapsedRealtime()
        try {
            val rpcClass = Class.forName("com.alibaba.ariver.commonability.network.rpc.RpcBridgeExtension", false, loader)
            val method = rpcClass.declaredMethods.firstOrNull {
                it.name == "rpc" && it.parameterTypes.size == 16 && it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[15].name.endsWith("BridgeCallback")
            } ?: error("未找到已适配的验证调用入口")
            ModernXposedRuntime.hook(method, before = { invocation ->
                if (invocation.args[0] != CaptchaProtocol.METHOD) return@hook
                val callback = invocation.args[15] ?: return@hook
                val uid = HookUtil.getUserId(loader) ?: return@hook
                installCallbacks(callback.javaClass)
                val observation = CaptchaObservedRequest(sequence.incrementAndGet(), uid,
                    System.identityHashCode(invocation.args[13]), CaptchaProtocol.parseRequest(invocation.args[4]?.toString().orEmpty()),
                    SystemClock.elapsedRealtime())
                callbacks.put(callback, observation, observation.createdAt)
                CaptchaCoordinator.onRequest(observation)
            }, after = { invocation ->
                if (invocation.throwable != null && invocation.args[0] == CaptchaProtocol.METHOD) {
                    invocation.args[15]?.let { callbacks.remove(it, SystemClock.elapsedRealtime()) }
                }
            })
            rpcInstalled = true
            Log.record(TAG, "验证接口独立监听已安装")
        } catch (_: Throwable) {
            Log.record(TAG, "验证接口尚不可用，后续定期重试；业务验证错误仍会暂停任务")
        }
    }

    @Synchronized private fun installCallbacks(type: Class<*>) {
        for (method in type.methods.filter {
            it.name == "sendJSONResponse" && it.parameterTypes.size in 1..2 && !it.parameterTypes[0].isPrimitive
        }) {
            if (method in hookedCallbacks) continue
            try {
                ModernXposedRuntime.hook(method, before = { invocation ->
                    val callback = invocation.thisObject ?: return@hook
                    val now = SystemClock.elapsedRealtime()
                    if (callbacks.get(callback, now) == null) return@hook
                    val response = invocation.args.firstOrNull()?.toString() ?: return@hook
                    val reply = CaptchaProtocol.parseReply(response)
                    if (reply != null) {
                        val request = callbacks.remove(callback, now) ?: return@hook
                        CaptchaCoordinator.onReply(request, reply, now)
                    } else if (invocation.args.getOrNull(1) != true) {
                        callbacks.remove(callback, now)
                    }
                })
                hookedCallbacks += method
            } catch (_: Throwable) {
                Log.record(TAG, "验证响应回调暂不兼容，保持暂停等待有效验证回执")
            }
        }
    }
}
