package fansirsqi.xposed.sesame.task.antFarm

import org.json.JSONObject

/** 家庭限时外卖红包任务，完成后的奖励状态以任务列表为准。 */
internal class AntFarmFamilyTakeawayTask(
    private val request: (String, JSONObject) -> JSONObject,
    private val log: (String) -> Unit
) {
    fun run(): Boolean {
        val task = queryTask() ?: return false
        if (task.optString("taskStatus") == "RECEIVED") {
            log("家庭任务🏠领取外卖红包任务（限时）#已领取，跳过")
            return true
        }
        if (task.optString("taskStatus") != "TODO" || task.optString("bizKey") != TASK_ID) return false
        if (task.has("taskEntityExpireTime") && task.optLong("taskEntityExpireTime") <= System.currentTimeMillis()) return false
        val extend = task.optJSONObject("extend") ?: try {
            JSONObject(task.optString("extend", "{}"))
        } catch (_: Exception) {
            return false
        }
        if (!extend.optBoolean("canDo")) return false

        val response = request("com.alipay.antfarm.doFarmTask", params()
            .put("bizKey", TASK_ID).put("taskSceneCode", "ANTFARM_FAMILY_TASK"))
        if (!response.optBoolean("success")) {
            log("家庭任务🏠领取外卖红包任务（限时）#完成失败：${response.optString("memo", response.optString("resultDesc"))}")
            return false
        }
        // 抓包中完成接口返回 FINISHED，刷新列表后直接 RECEIVED，无需另行领奖。
        val refreshed = queryTask() ?: return false
        if (refreshed.optString("taskStatus") == "RECEIVED") {
            log("家庭任务🏠领取外卖红包任务（限时）#已领取")
            return true
        }
        log("家庭任务🏠领取外卖红包任务（限时）#尚未确认领取，等待下次查询")
        return false
    }

    private fun queryTask(): JSONObject? {
        val response = request("com.alipay.antfarm.listFamilyTask", params().put("timeZoneId", "Asia/Shanghai"))
        if (!response.optBoolean("success")) {
            log("家庭任务🏠领取外卖红包任务（限时）#查询失败")
            return null
        }
        val tasks = response.optJSONArray("familyTasks") ?: return null
        return (0 until tasks.length()).mapNotNull { tasks.optJSONObject(it) }
            .firstOrNull { it.optString("taskId") == TASK_ID }
    }

    private fun params(): JSONObject = JSONObject()
        .put("requestType", "NORMAL").put("sceneCode", "ANTFARM").put("source", "H5")

    private companion object {
        const val TASK_ID = "FAMILY_SHANGOU"
    }
}
