package fansirsqi.xposed.sesame.task.antOrchard

import org.json.JSONArray
import org.json.JSONObject

/** 农场活动协议独立于请求桥，便于按离线回包验证动态场景。 */
data class OrchardActivityCall(val method: String, val arguments: String)

interface OrchardActivityGateway {
    fun canContinue(): Boolean
    suspend fun request(call: OrchardActivityCall): JSONObject
    suspend fun pause(milliseconds: Long)
}

object OrchardActivityProtocol {
    const val VERSION = "20250812.01"
    const val SOURCE = "ch_appcenter__chsub_9patch"
    fun call(method: String, body: JSONObject) = OrchardActivityCall(method, JSONArray().put(body).toString())
    fun normal(scene: String = "ORCHARD", source: String = SOURCE) = JSONObject().put("requestType", "NORMAL").put("sceneCode", scene).put("source", source).put("version", VERSION)
    private fun rpc(scene: String) = JSONObject().put("requestType", "RPC").put("sceneCode", scene).put("source", "antorchard")
    fun enterDraw(activity: String, scene: String) = call("com.alipay.antiepdrawprod.enterDrawActivityantorchard", rpc(scene).put("activityId", activity).put("context", JSONObject().put("appMode", "normal")))
    fun listDraw(scene: String) = call("com.alipay.antieptask.listTaskantorchard", rpc(scene + "_TASK").put("extend", JSONObject().put("appMode", "normal")))
    fun claimDraw(scene: String, task: String) = call("com.alipay.antieptask.receiveTaskAwardantorchard", rpc(scene).put("taskType", task).put("ignoreLimit", true))
    fun claimOptional(scene: String, task: String, count: Int) = call("com.alipay.antieptask.receiveTaskAwardantorchard", rpc(scene).put("taskType", task).put("ignoreLimit", true).put("awardCountForReceive", count))
    fun finish(scene: String, task: String) = call("com.alipay.antieptask.finishTaskantorchard", rpc(scene).put("taskType", task).put("outBizNo", task + "_" + java.util.UUID.randomUUID()))
    fun finishAlternative(scene: String, task: String, uid: String) = call("com.alipay.antiep.finishTask", normal(scene, "antorchard").put("taskType", task).put("userId", uid).put("outBizNo", uid + "_" + java.util.UUID.randomUUID()).put("version", "20250812.01"))
    fun farmAlternative(scene: String, task: String) = call("com.alipay.antfarm.doFarmTask", rpc("ORCHARD").put("source", "ANTFARM_ORCHARD").put("taskSceneCode", scene).put("bizKey", task).put("version", "20250812.01"))
    fun syncDraw(activity: String) = call("com.alipay.antiepdrawprod.drawSyncantorchard", rpc("ANTORCHARD_DRAW_TIMES").put("activityId", activity).put("source", "taskaward").put("context", JSONObject().put("appMode", "INT")))
    fun draw(activity: String, scene: String, uid: String) = call("com.alipay.antiepdrawprod.drawantorchard", rpc(scene).put("activityId", activity).put("userId", uid))
    fun optionalPlay() = call("com.alipay.charitygamecenter.queryOptionalPlay", normal(source = "H5").put("bizType", "ANTORCHARD").put("playTypeList", JSONArray().put("TASK_TRIGGER").put("TOP_UP_COUPON")).put("recentAppRecordList", JSONArray()).put("commonDegradeFilterRequest", JSONObject().put("appMode", "normal").put("deviceLevel", "high").put("initialized", true).put("platform", "Android").put("unityDeviceLevel", "high")))
    fun success(value: JSONObject): Boolean = value.optBoolean("success") || value.optString("code") == "100000000" || value.optString("resultCode") == "100"
    fun objectValue(value: Any?): JSONObject? = when (value) { is JSONObject -> value; is String -> runCatching { JSONObject(value) }.getOrNull(); else -> null }
}

/** 抽抽乐、乐园限定任务和丰收礼包共用有界的阶段推进与确认逻辑。 */
class OrchardActivityWorkflow(
    private val gateway: OrchardActivityGateway,
    private val uid: String,
    private val drawBlacklist: Set<String> = emptySet(),
    private val optionalBlacklist: Set<String> = emptySet(),
    private val rememberTask: (String, String, String) -> Unit = { _, _, _ -> },
    private val blacklistTask: (String, String, String) -> Unit = { _, _, _ -> },
    private val log: (String) -> Unit = {}
) {
    private suspend fun request(call: OrchardActivityCall): JSONObject? {
        if (!gateway.canContinue()) return null
        val response = gateway.request(call)
        return response.takeIf { gateway.canContinue() }
    }

    suspend fun runDrawActivities() {
        val root = request(OrchardActivityProtocol.enterDraw("", "ANTORCHARD_DRAW_TIMES")) ?: return
        if (!OrchardActivityProtocol.success(root)) return
        val activities = linkedMapOf<String, JSONObject>()
        fun add(activity: JSONObject?) {
            if (activity == null) return
            val id = activity.optString("activityId")
            val scene = activity.optString("sceneCode")
            if (id.isNotBlank() && scene.isNotBlank()) activities["$id/$scene"] = activity
        }
        add(root.optJSONObject("drawActivity"))
        add(root.optJSONObject("drawScene")?.optJSONObject("drawActivity"))
        val groups = root.optJSONArray("drawSceneGroups") ?: JSONArray()
        for (i in 0 until groups.length()) add(groups.optJSONObject(i)?.optJSONObject("drawActivity"))
        for (activity in activities.values.take(20)) {
            if (!gateway.canContinue()) return
            val id = activity.getString("activityId")
            val scene = activity.getString("sceneCode")
            advanceTasks("draw", { OrchardActivityProtocol.listDraw(scene) }, { it.optJSONArray("taskInfoList") }, drawBlacklist)
            request(OrchardActivityProtocol.syncDraw(id)) ?: return
            var current = request(OrchardActivityProtocol.enterDraw(id, scene)) ?: return
            if (!OrchardActivityProtocol.success(current)) continue
            var balance = current.optJSONObject("drawAsset")?.optInt("blance", 0) ?: 0
            for (drawNumber in 0 until 30) {
                if (balance <= 0 || !gateway.canContinue()) break
                current = request(OrchardActivityProtocol.draw(id, scene, uid)) ?: return
                if (!OrchardActivityProtocol.success(current)) {
                    // 动作回包可能丢失，只查询资产确认，不重放抽奖请求。
                    val probe = request(OrchardActivityProtocol.enterDraw(id, scene)) ?: return
                    val probedBalance = probe.optJSONObject("drawAsset")?.optInt("blance", -1) ?: -1
                    if (OrchardActivityProtocol.success(probe) && probedBalance in 0 until balance) {
                        log("农场抽抽乐🎰[${activity.optString("name")}]抽奖次数已扣减，响应未确认奖品")
                    }
                    break
                }
                val next = current.optJSONObject("drawAsset")?.optInt("blance", -1) ?: -1
                log("农场抽抽乐🎰[${activity.optString("name")}]${current.optJSONObject("prizeVO")?.optString("prizeName", "已抽奖") ?: "已抽奖"}")
                // 抽奖回包没有确认扣减时停止，避免余额暂态导致重复抽取。
                if (next < 0 || next >= balance) break
                balance = next
                gateway.pause(1500)
            }
        }
    }

    suspend fun runOptionalPlay() = advanceTasks("play", OrchardActivityProtocol::optionalPlay, { it.optJSONObject("taskTriggerPlayInfo")?.optJSONArray("taskList") }, optionalBlacklist)

    private suspend fun advanceTasks(kind: String, query: () -> OrchardActivityCall, extract: (JSONObject) -> JSONArray?, blacklist: Set<String>) {
        val attempted = mutableSetOf<String>()
        val playStageAttempts = mutableMapOf<String, Int>()
        val failedPlayStages = mutableSetOf<String>()
        repeat(12) {
            val root = request(query()) ?: return
            if (!OrchardActivityProtocol.success(root)) return
            val tasks = extract(root) ?: return
            var acted = false
            for (i in 0 until tasks.length()) {
                if (!gateway.canContinue()) return
                val item = tasks.optJSONObject(i) ?: continue
                val task = item.optJSONObject("taskBaseInfo") ?: item
                val scene = task.optString("sceneCode")
                val type = task.optString("taskType")
                if (scene.isBlank() || type.isBlank()) continue
                val title = OrchardActivityProtocol.objectValue(task.opt("bizInfo"))?.optString("title").orEmpty().ifBlank { type }
                rememberTask(kind, "$scene/$type", title)
                val rights = item.optJSONObject("taskRights") ?: task
                val times = rights.optInt("rightsTimes", 0)
                val limit = rights.optInt("rightsTimesLimit", 0)
                val state = task.optString("taskStatus")
                val award = task.optInt("awardCount", 0) - task.optInt("alreadyReceiveAwardCount", 0)
                val key = "$scene/$type#$times:$state:${task.optInt("alreadyReceiveAwardCount", 0)}"
                if (state == "FINISHED") {
                    if (kind == "play" && award <= 0) continue
                    if (!attempted.add("award:$key")) continue
                    val response = request(if (kind == "play") OrchardActivityProtocol.claimOptional(scene, type, award) else OrchardActivityProtocol.claimDraw(scene, type)) ?: return
                    // 领奖失败不会污染任务黑名单；后续重新读取服务端，避免无证据标记已领取。
                    if (OrchardActivityProtocol.success(response)) log("农场活动🎖️[$title]领取奖励")
                    acted = true
                    continue
                }
                if (state != "TODO" && !(limit > times && state in setOf("", "RECEIVED", "AWARDED"))) continue
                if (limit > 0 && times >= limit) continue
                if (title in blacklist || type in blacklist || "$scene/$type" in blacklist) continue
                if (transactionTask("$type ${task.optString("groupId")} $scene $title")) continue
                if (kind == "play") {
                    // 中间档可能到累计末次才更新权利次数；每次成功后重查，仍同档则只补本档剩余次数。
                    val remaining = if (limit > 0) (limit - times).coerceIn(1, 20) else 1
                    val count = playStageAttempts[key] ?: 0
                    if (key in failedPlayStages || count >= remaining) continue
                    playStageAttempts[key] = count + 1
                } else if (!attempted.add("finish:$key")) continue
                // 抽抽乐权利次数在里程碑全部完成前可能保持不变，按剩余次数连续完成后再查列表。
                val completions = if (kind == "draw" && limit > 0) (limit - times).coerceIn(1, 20) else 1
                for (completion in 0 until completions) {
                    var response = request(OrchardActivityProtocol.finish(scene, type)) ?: return
                    if (!OrchardActivityProtocol.success(response) && response.optString("code", response.optString("resultCode")) == "400000040") {
                        response = request(OrchardActivityProtocol.finishAlternative(scene, type, uid)) ?: return
                    }
                    if (!OrchardActivityProtocol.success(response) && response.optString("code", response.optString("resultCode")) == "400000040") {
                        response = request(OrchardActivityProtocol.farmAlternative(scene, type)) ?: return
                    }
                    acted = true
                    if (!OrchardActivityProtocol.success(response)) {
                        if (kind == "play") failedPlayStages.add(key)
                        val code = response.optString("code", response.optString("resultCode"))
                        if (code in setOf("400000040", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT")) blacklistTask(kind, "$scene/$type", title)
                        // 任何失败都停止本阶段余下动作，下一轮只查询，不盲重放。
                        break
                    }
                    gateway.pause(500)
                }
            }
            // 同一阶段只执行一组有界动作；下一轮没有新的阶段或可领奖动作就立即退出。
            if (!acted) return
            gateway.pause(1200)
        }
    }

    suspend fun claimSpreadStage(index: JSONObject): Boolean {
        val stage = index.optJSONObject("spreadManureActivity")?.optJSONObject("spreadManureStage") ?: return false
        if (stage.optString("status") != "FINISHED") return false
        val scene = stage.optString("sceneCode")
        val type = stage.optString("taskType")
        if (scene.isBlank() || type.isBlank()) return false
        val claim = OrchardActivityProtocol.call("com.alipay.antiep.receiveTaskAward", OrchardActivityProtocol.normal(scene).put("taskType", type).put("ignoreLimit", false))
        val response = request(claim) ?: return false
        if (OrchardActivityProtocol.success(response)) { log("芭芭农场🎁丰收礼包#获得[${response.optInt("incAwardCount")}g肥料]"); return true }
        val refreshed = request(OrchardActivityProtocol.call("com.alipay.antfarm.orchardIndex", OrchardActivityProtocol.normal())) ?: return false
        if (!OrchardActivityProtocol.success(refreshed)) return false
        val next = refreshed.optJSONObject("spreadManureActivity")?.optJSONObject("spreadManureStage") ?: return false
        return next.optString("status") in setOf("RECEIVED", "AWARDED") || (next.optString("taskType") != type && next.optString("taskType").isNotBlank())
    }

    private fun transactionTask(value: String): Boolean {
        val text = value.lowercase()
        return listOf("充值", "购买", "下单", "捐款", "租赁", "履约", "shouqian", "xiaofei", "chongzhi", "goumai", "dingdan", "purchase", "payment", "recharge", "transaction", "game_cz", "juankuan", "choukuan", "kuaidi", "huishou").any(text::contains) || Regex("支付(?!宝)|(?<![a-z])(pay|charge|order)(?![a-z])|_cz[0-9]").containsMatchIn(text)
    }
}
