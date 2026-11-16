package fansirsqi.xposed.sesame.task.antFarm

import com.fasterxml.jackson.core.type.TypeReference
import fansirsqi.xposed.sesame.data.Config
import fansirsqi.xposed.sesame.entity.OtherEntity
import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.hook.RequestManager
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectModelField
import fansirsqi.xposed.sesame.util.DataStore
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.maps.UserMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** AI 厨房独立于旧厨房，任务候选目录只保存标题，不保存账号资产。 */
object AiKitchenTask {
    private const val CATALOGUE = "ai_kitchen_task_catalogue"

    fun taskList(): List<OtherEntity> = catalogue().sorted().map { OtherEntity(it, it) }

    private fun catalogue(): Set<String> = runCatching {
        DataStore.getOrCreate(CATALOGUE, object : TypeReference<Set<String>>() {})
    }.getOrDefault(emptySet())

    suspend fun execute(autoCook: Boolean, autoFinishTask: Boolean, autoBlacklist: Boolean, field: SelectModelField) {
        val uid = UserMap.currentUid?.takeIf { it.isNotBlank() } ?: return
        suspend fun checkpoint() {
            currentCoroutineContext().ensureActive()
            if (ApplicationHook.offline || RequestManager.isVerificationPaused() || UserMap.currentUid != uid) {
                throw CancellationException("小鸡大厨已暂停或切换账号")
            }
        }
        val candidates = catalogue().toMutableSet()
        try {
            val runner = AiKitchenRunner(
                request = { method, body -> JSONObject(RequestManager.requestString(method, JSONArray().put(body).toString(), 1, 0)) },
                userId = uid, checkpoint = ::checkpoint,
                blacklist = field.value,
                onCandidate = { if (candidates.add(it)) DataStore.put(CATALOGUE, candidates.toSet()) },
                onAutoBlacklist = {
                    if (field.value.add(it)) Config.save(uid, true)
                },
                log = { Log.record("AiKitchen", it) }
            )
            val result = runner.execute(autoCook, autoFinishTask, autoBlacklist)
            Log.record("AiKitchen", "小鸡大厨结束：领取${result.received}项任务奖励，制作${result.cooked}道美食")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.record("AiKitchen", "小鸡大厨请求未完成，等待下轮重试")
        }
    }
}
