package fansirsqi.xposed.sesame.task.antOrchard

import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.hook.RequestManager
import fansirsqi.xposed.sesame.util.maps.UserMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.function.BooleanSupplier

/** 每次请求及等待都检查原任务和原账号，暂停后不留下旧业务继续提交。 */
class OrchardActivityRuntimeGateway(private val uid: String, private val executionGuard: BooleanSupplier) : OrchardActivityGateway {
    override fun canContinue(): Boolean = executionGuard.asBoolean && uid == UserMap.currentUid && !ApplicationHook.offline && !RequestManager.isVerificationPaused()

    override suspend fun request(call: OrchardActivityCall): JSONObject {
        currentCoroutineContext().ensureActive()
        if (!canContinue()) return JSONObject().put("success", false)
        val response = RequestManager.requestString(call.method, call.arguments, 1, 0)
        currentCoroutineContext().ensureActive()
        if (!canContinue()) return JSONObject().put("success", false)
        return runCatching { JSONObject(response) }.getOrElse { JSONObject().put("success", false) }
    }

    override suspend fun pause(milliseconds: Long) {
        currentCoroutineContext().ensureActive()
        if (canContinue()) delay(milliseconds)
    }
}
