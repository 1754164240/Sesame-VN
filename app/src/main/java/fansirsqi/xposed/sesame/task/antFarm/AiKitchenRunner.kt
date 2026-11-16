package fansirsqi.xposed.sesame.task.antFarm

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** 只负责服务端协议与库存推进，网络、账号和配置由接入层注入。 */
internal class AiKitchenRunner(
    private val request: suspend (String, JSONObject) -> JSONObject,
    private val userId: String,
    private val checkpoint: suspend () -> Unit,
    private val pause: suspend () -> Unit = { delay(800) },
    private val blacklist: Set<String> = emptySet(),
    private val onCandidate: (String) -> Unit = {},
    private val onAutoBlacklist: (String) -> Unit = {},
    private val log: (String) -> Unit = {}
) {
    data class Result(val cooked: Int, val received: Int)
    private var received = 0

    suspend fun execute(autoCook: Boolean, autoFinishTask: Boolean, autoBlacklist: Boolean): Result {
        checkpoint()
        if (userId.isBlank()) return Result(0, 0)
        var state = call("com.alipay.antfarm.enterAiKitchen", homeArgs())
        if (!AiKitchenPolicy.success(state)) return Result(0, 0)
        log("厨神分${state.optInt("chefScore")}，精通${state.optInt("masteryScore")}，丰富度${state.optInt("richnessScore")}")
        receiveAwards(autoBlacklist)
        if (autoFinishTask) {
            val tasks = tasks() ?: return Result(0, received)
            for (i in 0 until tasks.length()) {
                val task = tasks.optJSONObject(i) ?: continue
                val id = task.optString("taskId")
                val title = task.optString("title", id)
                if (id.isBlank() || title in blacklist || task.optString("taskStatus") != "TODO") continue
                if (!AiKitchenPolicy.canFinishTask(task)) continue
                pause()
                val result = call("com.alipay.antieptask.finishTaskantfarm", JSONObject()
                    .put("bizKey", id).put("taskType", id).put("userId", userId)
                    .put("outBizNo", userId + System.currentTimeMillis()).put("requestType", "NORMAL")
                    .put("sceneCode", SCENE).put("taskSceneCode", SCENE).put("source", "aikitchen_daily"))
                if (!AiKitchenPolicy.success(result)) confirmOrBlock(id, title, result, autoBlacklist)
            }
            receiveAwards(autoBlacklist)
        }
        if (!autoCook) return Result(0, received)
        state = call("com.alipay.antfarm.enterAiKitchen", homeArgs())
        if (!AiKitchenPolicy.success(state)) return Result(0, received)
        val tried = mutableSetOf<String>()
        var cooked = 0
        var stagnant = 0
        while (cooked < 100) {
            checkpoint()
            if (!state.optBoolean("canCook", true)) break
            val season = state.optString("seasonId")
            val feed = state.optInt("feedStock", -1)
            val cost = state.optInt("feedCost", -1)
            if (season.isBlank() || cost < 0 || feed < cost) break
            val ingredients = AiKitchenPolicy.pickIngredients(state, tried) ?: break
            val before = AiKitchenPolicy.totalStock(state)
            pause()
            val result = call("com.alipay.antfarm.cookAiKitchen", homeArgs()
                .put("seasonId", season).put("ingredientIds", JSONArray(ingredients)))
            if (!AiKitchenPolicy.success(result)) break
            cooked++
            tried += ingredients.sorted().joinToString("|")
            log("制作${result.optString("dishName", "美食")}，结果${result.optString("cookResultType")}，评分${result.optInt("score")}")
            if (!result.optBoolean("canCook", true)) break
            // 响应未返回完整库存时重新查询，不能用本地推测值继续消耗。
            state = if (result.optJSONArray("ingredientList") != null && result.has("feedStock")) {
                JSONObject(state.toString()).apply { result.keys().forEach { put(it, result.opt(it)) } }
            } else call("com.alipay.antfarm.enterAiKitchen", homeArgs())
            if (!AiKitchenPolicy.success(state)) break
            stagnant = if (AiKitchenPolicy.totalStock(state) >= before) stagnant + 1 else 0
            if (stagnant >= 2) break
        }
        return Result(cooked, received)
    }

    private suspend fun receiveAwards(autoBlacklist: Boolean) {
        val list = tasks() ?: return
        for (i in 0 until list.length()) {
            val task = list.optJSONObject(i) ?: continue
            val id = task.optString("taskId")
            val title = task.optString("title", id)
            if (title.isNotBlank()) { checkpoint(); onCandidate(title) }
            val count = task.optInt("canReceiveAwardCount", 0)
            if (id.isBlank() || title in blacklist || task.optString("taskStatus") != "FINISHED" || count <= 0) continue
            pause()
            val result = call("com.alipay.antieptask.receiveTaskAwardantfarm", JSONObject()
                .put("awardCountForReceive", count).put("ignoreLimit", true).put("taskType", id)
                .put("requestType", "RPC").put("sceneCode", SCENE).put("source", "antfarm"))
            if (AiKitchenPolicy.success(result)) { received++; log("领取食材任务奖励：$title") }
            else confirmOrBlock(id, title, result, autoBlacklist, claiming = true)
        }
    }

    private suspend fun confirmOrBlock(id: String, title: String, response: JSONObject, autoBlacklist: Boolean, claiming: Boolean = false) {
        // 失败响应先回查，临时故障及未知业务码都留待下轮，不能永久拉黑。
        val latest = tasks() ?: return
        val task = (0 until latest.length()).mapNotNull { latest.optJSONObject(it) }.firstOrNull { it.optString("taskId") == id }
        val acceptedStates = if (claiming) setOf("RECEIVED") else setOf("FINISHED", "RECEIVED")
        if (task?.optString("taskStatus") in acceptedStates) return
        val description = response.optString("resultDesc", response.optString("memo"))
        val permanent = response.optString("resultCode") in setOf("ILLEGAL_ARGUMENT", "TASK_NOT_SUPPORT", "400000040") ||
            description.contains("不支持rpc") || description.contains("任务全局配置不存在")
        if (autoBlacklist && task != null && permanent) { checkpoint(); onAutoBlacklist(title) }
    }

    private suspend fun tasks(): JSONArray? {
        val result = call("com.alipay.antfarm.listFarmTask", JSONObject().put("requestType", "NORMAL")
            .put("sceneCode", SCENE).put("signSceneCode", "").put("source", "H5").put("taskSceneCode", SCENE).put("topTask", ""))
        return if (AiKitchenPolicy.success(result)) result.optJSONArray("farmTaskList") else null
    }

    private suspend fun call(method: String, body: JSONObject): JSONObject {
        checkpoint()
        val result = request(method, body)
        checkpoint()
        return result
    }

    private fun homeArgs() = JSONObject().put("requestType", "RPC").put("sceneCode", "ANTFARM").put("source", "aikitchen_daily")

    companion object { const val SCENE = "ANTFARM_AI_KITCHEN_TASK" }
}

internal object AiKitchenPolicy {
    fun success(result: JSONObject) = result.optBoolean("success", false) || result.optBoolean("isSuccess", false)

    fun canFinishTask(task: JSONObject): Boolean {
        val text = listOf(task.optString("taskId"), task.optString("title"), task.optString("bizInfo")).joinToString(" ")
        if (listOf("充值", "支付", "购买", "下单", "交易", "邀请", "赠送", "玩游戏", "TOPUP", "PAY", "TRADE", "PURCHASE").any { text.contains(it, true) }) return false
        return listOf("浏览", "看看", "看美食", "知识", "签到", "BROWSE", "VISIT", "SIGN").any { text.contains(it, true) }
    }

    fun stock(state: JSONObject): Map<String, Int> {
        val list = state.optJSONArray("ingredientList") ?: return emptyMap()
        val result = linkedMapOf<String, Int>()
        for (i in 0 until list.length()) {
            val ingredient = list.optJSONObject(i) ?: continue
            val id = ingredient.optString("ingredientId")
            val count = ingredient.optInt("count", 0)
            if (id.isNotBlank() && count > 0) result[id] = ((result[id]?.toLong() ?: 0) + count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return result
    }

    fun totalStock(state: JSONObject) = stock(state).values.sumOf { it.toLong() }

    fun pickIngredients(state: JSONObject, tried: Set<String>): List<String>? {
        val available = stock(state).entries.sortedByDescending { it.value }.map { it.key }
        if (available.size < 3) return null
        for (i in 0 until available.size - 2) for (j in i + 1 until available.size - 1) for (k in j + 1 until available.size) {
            val ids = listOf(available[i], available[j], available[k])
            if (ids.sorted().joinToString("|") !in tried) return ids
        }
        return available.take(3)
    }
}
