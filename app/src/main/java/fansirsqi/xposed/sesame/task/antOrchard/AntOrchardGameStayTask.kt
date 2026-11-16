package fansirsqi.xposed.sesame.task.antOrchard

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 首页信息流和投放位的悬浮球时长任务，使用农场乐园自身事件上下文。 */
class AntOrchardGameStayTask(
    private val gateway: OrchardActivityGateway,
    private val uid: String,
    private val isDone: (String) -> Boolean = { false },
    private val markDone: (String) -> Unit = {},
    private val log: (String) -> Unit = {}
) {
    private data class StayTask(val appId: String, val name: String, val task: String, val scene: String, val seconds: Int)
    private val source = "lianyun_nc_sydb"

    private suspend fun request(method: String, body: JSONObject): JSONObject? {
        if (!gateway.canContinue()) return null
        val response = gateway.request(OrchardActivityProtocol.call(method, body))
        return response.takeIf { gateway.canContinue() }
    }

    suspend fun execute() {
        if (!gateway.canContinue()) return
        val tasks = linkedMapOf<String, StayTask>()
        fun add(app: String, name: String, benefit: JSONObject?) {
            if (benefit == null || benefit.optString("benefitType") != "FLOAT_BALL_TASK") return
            val task = benefit.optString("iepTaskId")
            val scene = benefit.optString("iepSceneCode")
            if (app.isBlank() || task.isBlank() || scene.isBlank() || isDone(app)) return
            val duration = OrchardActivityProtocol.objectValue(benefit.opt("bizInfo"))?.optInt("floatBallDuration", 60) ?: 60
            if (duration > 3600) return
            tasks.putIfAbsent(app, StayTask(app, name, task, scene, duration.coerceAtLeast(30)))
        }
        val feeds = request("com.alipay.antorchard.indexFeeds", normal().put("pageNum", 1)
            .put("filterItemList", JSONArray().put(JSONObject().put("itemType", "qianyi").put("itemIdList", JSONArray())).put(JSONObject().put("itemType", "game").put("itemIdList", JSONArray())))
            .put("commonDegradeResultVO", degrade()))?.optJSONArray("feedsInfoList") ?: JSONArray()
        for (i in 0 until feeds.length()) {
            val feed = feeds.optJSONObject(i) ?: continue
            if (feed.optString("itemType") != "game") continue
            val game = feed.optJSONObject("gameItem") ?: continue
            add(game.optString("gameItemId"), game.optString("gameName", "未知游戏"), OrchardActivityProtocol.objectValue(feed.optJSONObject("itemOutExtMap")?.opt("orchardBenefitInfo")))
        }
        val delivery = request("com.alipay.antorchard.orchardIndexDelivery", normal().put("commonDegradeResult", degrade())
            .put("enableTeamType", JSONArray().put("team")).put("inTeam", true).put("teamPositionSwitch", true).put("teamType", "help"))?.optJSONArray("indexDeliveryList") ?: JSONArray()
        for (i in 0 until delivery.length()) {
            val item = delivery.optJSONObject(i) ?: continue
            add(item.optString("contentId"), item.optJSONObject("deliveryDisplayInfo")?.optString("btnText", "未知游戏") ?: "未知游戏", item.optJSONObject("deliveryBenefitInfo"))
        }
        for (task in tasks.values.take(20)) {
            if (!gateway.canContinue()) return
            runTask(task)
        }
    }

    private fun normal() = OrchardActivityProtocol.normal().put("version", "20260721.01")

    private fun degrade() = JSONObject().put("deviceLevel", "high").put("resultReason", 0).put("resultType", 0)

    private suspend fun event(task: StayTask, event: String, elapsed: Long = 0): Boolean {
        val attributes = JSONObject().put("CH_INFO", source).put("CPS_ID", "unknown").put("GAME_VERSION", "6.5.72")
            .put("PALADINX_VERSION", "2.1.95").put("PLAY_SCENE", "NORMAL").put("SCENE_ID", "nongchangleyuan")
        if (event == "GAME_FIRST_FRAME") attributes.put("ALIVE_ENTER", "0")
        if (elapsed > 0) attributes.put("GAME_ELAPASED_TIME", elapsed)
        val eventSource = when (event) { "GAME_FIRST_FRAME" -> "paladinx_auto"; "loading_completed" -> "my.reportLoadingCompleted"; else -> "my.reportGamePlay" }
        val result = request("com.alipay.gameevent.biz.rpc.submitEvent", JSONObject().put("appId", task.appId).put("eventAttrMap", attributes)
            .put("eventId", event).put("idempotentNo", "platform_" + UUID.randomUUID()).put("source", eventSource)) ?: return false
        return OrchardActivityProtocol.success(result)
    }

    private suspend fun runTask(task: StayTask) {
        val notice = request("com.alipay.antorchard.noticeGame", normal().put("appId", task.appId)) ?: return
        if (!OrchardActivityProtocol.success(notice)) return
        val enter = request("com.alipay.gamecenteruprod.biz.rpc.v3.submitUserAction", JSONObject().put("actionCode", "enterGame").put("gameId", task.appId).put("paladinxVersion", "2.1.95").put("source", "gameFramework")) ?: return
        if (!OrchardActivityProtocol.success(enter)) return
        gateway.pause(500)
        if (!event(task, "GAME_FIRST_FRAME")) return
        gateway.pause(500)
        if (!event(task, "loading_completed")) return
        gateway.pause(3500)
        val duration = request("com.alipay.gamecenteruprod.biz.rpc.v3.submitUserPlayDurationAction", JSONObject().put("gameAppId", task.appId).put("playTime", task.seconds).put("source", source).put("statisticTag", "")) ?: return
        if (!OrchardActivityProtocol.success(duration) || !event(task, "game_play", 3500)) return
        val finish = request("com.alipay.antiep.finishTask", OrchardActivityProtocol.normal(task.scene).put("taskType", task.task).put("userId", "")
            .put("version", "0.1.2411251623.29").put("outBizNo", task.task + "_" + UUID.randomUUID())) ?: return
        if (!OrchardActivityProtocol.success(finish) || !gateway.canContinue()) return
        markDone(task.appId)
        log("农场游戏🎮完成[${task.name}]获得肥料${finish.optJSONObject("finishAwardResultVO")?.optInt("deltaAwardCount", 0) ?: 0}g")
    }
}
