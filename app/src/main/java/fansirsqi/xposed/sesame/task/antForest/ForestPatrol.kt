package fansirsqi.xposed.sesame.task.antForest

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 新版保护地巡护，所有次数和动物等级均以服务端状态为准。 */
internal class ForestPatrol(
    private val request: (String, JSONObject) -> JSONObject,
    private val pause: (Long) -> Unit,
    private val log: (String) -> Unit
) {
    private val completedRegions = mutableSetOf<String>()
    private val preparedRegions = mutableSetOf<String>()
    private val attemptedTasks = mutableSetOf<String>()
    private val attemptedAwards = mutableSetOf<String>()

    fun run() {
        var entry = monopoly("queryMonopolyEntryInfo") ?: return
        // 即使事件返还骰子或服务端数据异常，也不能无限请求。
        repeat(200) {
            val region = entry.optJSONObject("userInfo")?.optString("currentRegionCode").orEmpty()
            if (region.isBlank() || !entry.has("totalDiceCount")) {
                log("保护地巡护：缺少地区或次数信息，停止")
                return
            }

            if (allMaxLevel(entry.optJSONObject("mapInfo")?.optJSONArray("creatureList"))) {
                val maps = monopoly("queryMonopolyMapPage") ?: return
                if (regionCompleted(maps.optJSONArray("currentRegionMapList"))) {
                    completedRegions.add(region)
                    val regions = maps.optJSONArray("regionList") ?: return
                    val next = (0 until regions.length()).mapNotNull { regions.optJSONObject(it) }
                        .firstOrNull {
                            val code = it.optString("regionCode")
                            code.isNotBlank() && code != region && code !in completedRegions &&
                                it.optString("regionAvailability") == "AVAILABLE"
                        }
                    if (next == null) {
                        log("保护地巡护：可用保护地的动物已全部满级")
                        return
                    }
                    val target = next.getString("regionCode")
                    monopoly("switchMonopolyRegion", JSONObject().put("regionCode", target)) ?: return
                    entry = monopoly("queryMonopolyEntryInfo") ?: return
                    if (entry.optJSONObject("userInfo")?.optString("currentRegionCode") != target) {
                        log("保护地巡护：切换后的地区未更新，停止")
                        return
                    }
                    log("保护地巡护：动物全部满级，切换至${next.optString("regionName", target)}")
                    return@repeat
                }
                log("保护地巡护：当前地图动物已满级，保护地还有未满级动物，等待进入下一地图后继续")
                return
            }

            if (preparedRegions.add(region)) {
                monopoly("triggerHomePageProps") ?: return
                if (!completeTasks(region)) return
                // 领奖接口可能成功但权益发放失败，必须刷新实际骰子次数。
                entry = monopoly("queryMonopolyEntryInfo") ?: return
            }
            if (entry.optInt("totalDiceCount", -1) <= 0) {
                log("保护地巡护：当前保护地次数已用完")
                return
            }
            val roll = monopoly("rollDice") ?: return
            val event = roll.optJSONObject("eventInfo")
            if (event == null || !event.has("needConfirm") || !roll.has("totalDiceCount")) {
                log("保护地巡护：摇骰响应不完整，停止以免遗漏事件")
                return
            }
            log("保护地巡护：摇骰${roll.optInt("diceNumber")}点，剩余${roll.optInt("totalDiceCount")}次")
            if (event.optBoolean("needConfirm")) {
                confirmExploration(event)
            }
            entry = monopoly("queryMonopolyEntryInfo") ?: return
        }
        log("保护地巡护：达到单次运行上限，等待下次执行")
    }

    /** 确认探索失败仅记录日志，刷新次数后仍可继续摇骰。 */
    private fun confirmExploration(event: JSONObject) {
        val id = event.optString("eventId")
        if (id.isBlank()) {
            log("保护地巡护：待确认探索缺少编号，继续摇骰")
            return
        }
        try {
            val result = monopoly("eventConfirm", JSONObject()
                .put("eventId", id).put("status", "CONFIRMED")
                .put("decisionData", JSONObject().put("actionKey", "confirm").put("source", "ROLL_DICE_STEP")))
            if (result == null) {
                log("保护地巡护：确认探索失败，继续摇骰")
                return
            }
            val rewards = result.optJSONArray("eventResult") ?: JSONArray()
            for (i in 0 until rewards.length()) {
                val reward = rewards.optJSONObject(i) ?: continue
                if (reward.optString("resultType") == "CREATURE") {
                    log("保护地巡护：${reward.optString("creatureCode")}，等级${reward.optInt("creatureLevel")}")
                }
            }
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            log("保护地巡护：确认探索异常，继续摇骰：${e.message}")
        }
    }

    private fun completeTasks(region: String): Boolean {
        // 刷新列表可获取刚完成的任务及动态出现的新任务；同一任务单次只尝试一次。
        repeat(8) {
            val response = call("com.alipay.antieptask.listTaskopengreen", JSONObject()
                .put("extend", JSONObject().put("regionCode", region))
                .put("requestType", "RPC").put("sceneCode", TASK_SCENE).put("source", "ANTFOREST")
                .put("uniqueId", uniqueId()).put("zoneId", "Asia/Shanghai")) ?: return false
            val tasks = response.optJSONArray("taskInfoList")
            if (tasks == null) {
                log("保护地巡护：任务列表缺失，停止")
                return false
            }
            var changed = false
            for (i in 0 until tasks.length()) {
                val base = tasks.optJSONObject(i)?.optJSONObject("taskBaseInfo") ?: continue
                val type = base.optString("taskType")
                val scene = base.optString("sceneCode")
                if (type.isBlank() || scene != TASK_SCENE) continue
                val key = "$scene:$type"
                val name = jsonObject(base.optString("bizInfo")).optString("taskTitle", type)
                when (base.optString("taskStatus")) {
                    "FINISHED" -> if (attemptedAwards.add(key)) {
                        call("com.alipay.antieptask.receiveTaskAwardopengreen", JSONObject()
                            .put("ignoreLimit", false).put("requestType", "RPC").put("sceneCode", scene)
                            .put("source", "ANTFOREST").put("taskType", type)) ?: return false
                        log("保护地巡护：已申请领取$name，实际次数以查询结果为准")
                        changed = true
                    }
                    "TODO" -> {
                        // 步数等累计任务由真实进度完成，不走普通浏览任务接口。
                        if (base.optString("taskMode") != "NORMAL" ||
                            base.optString("taskProdPlayType") != "VISIT_FLOAT_BALL" ||
                            !attemptedTasks.add(key)) continue
                        val seconds = jsonObject(base.optString("prodPlayParam")).optInt("timeCount", 5)
                        if (seconds !in 0..60) continue
                        // 抓包中的流水时间早于完成请求，先记录任务开始时间再等待。
                        val outBizNo = "${type}_${System.currentTimeMillis()}_${uniqueId()}"
                        pause(seconds * 1000L)
                        call("com.alipay.antieptask.finishTaskopengreen", JSONObject()
                            .put("outBizNo", outBizNo)
                            .put("requestType", "H5").put("sceneCode", scene)
                            .put("source", "ANTFOREST").put("taskType", type)) ?: return false
                        log("保护地巡护：完成任务$name")
                        changed = true
                    }
                }
            }
            if (!changed) return true
        }
        log("保护地巡护：任务刷新达到上限")
        return false
    }

    private fun regionCompleted(maps: JSONArray?): Boolean {
        if (maps == null || maps.length() == 0) return false
        var hasAnimals = false
        for (i in 0 until maps.length()) {
            val map = maps.optJSONObject(i) ?: return false
            val animals = map.optJSONArray("creatureList")
            if ((animals == null || animals.length() == 0) && map.optString("mapAvailability") == "PREPARING") continue
            if (!allMaxLevel(animals)) return false
            hasAnimals = true
        }
        return hasAnimals
    }

    private fun allMaxLevel(animals: JSONArray?): Boolean {
        if (animals == null || animals.length() == 0) return false
        return (0 until animals.length()).all {
            val animal = animals.optJSONObject(it)
            val max = animal?.optInt("maxLevel", 0) ?: 0
            max > 0 && animal!!.optInt("level", 0) >= max
        }
    }

    private fun monopoly(method: String, params: JSONObject = JSONObject()): JSONObject? =
        call("alipay.antisle.monopoly.h5.$method", params.put("source", "monopoly_home_animal").put("uniqueId", uniqueId()))

    private fun call(method: String, params: JSONObject): JSONObject? {
        pause(300L)
        val response = request(method, params)
        val success = if (response.has("success")) response.optBoolean("success") else
            response.optString("resultCode") == "SUCCESS" || response.optString("code") == "100000000"
        if (!success) {
            log("保护地巡护：${method.substringAfterLast('.')}失败：${response.optString("resultDesc", response.optString("desc", "响应异常"))}")
            return null
        }
        return response
    }

    private fun jsonObject(value: String): JSONObject =
        if (value.isBlank()) JSONObject() else try { JSONObject(value) } catch (_: Exception) { JSONObject() }

    private fun uniqueId(): String = UUID.randomUUID().toString().replace("-", "")

    private companion object {
        const val TASK_SCENE = "ANTFOREST_MONOPOLY_TASK_HSDWY"
    }
}
