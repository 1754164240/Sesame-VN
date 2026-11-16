package fansirsqi.xposed.sesame.task.antForest

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.security.MessageDigest

/** 分享回调和活动确认均成功后才记录助力，失败不冒充已完成。 */
internal class ForestHuntAssist(
    private val request: suspend (String, JSONObject) -> JSONObject,
    private val checkpoint: suspend () -> Unit,
    private val pause: suspend () -> Unit = { delay(1500) },
    private val hasFlag: (String) -> Boolean,
    private val mark: (String) -> Unit,
    private val currentUserId: String
) {
    suspend fun run(activityId: String, scene: String, taskType: String, shareIds: Set<String>): Int {
        if (activityId.isBlank() || scene.isBlank()) return 0
        var count = 0
        for (input in shareIds.take(200)) {
            checkpoint()
            val shareId = expandShareId(input, taskType) ?: continue
            val hash = MessageDigest.getInstance("SHA-256").digest(shareId.toByteArray()).joinToString("") { "%02x".format(it) }
            val flag = "forest::huntHelp::$activityId::$taskType::$hash"
            if (hasFlag(flag)) continue
            val recalled = call("com.alipay.antiep.shareComponentRecall", JSONObject()
                .put("iepShareChannelType", "qrcode").put("requestType", "RPC").put("sceneCode", scene)
                .put("shareId", shareId).put("source", "chouchoule"))
            if (!success(recalled)) continue
            val inviter = recalled.optJSONObject("inviterInfoVo")?.optString("userId").orEmpty()
            if (!inviter.matches(Regex("[0-9]{16}")) || inviter == currentUserId) continue
            pause()
            val result = call("com.alipay.antiep.confirmShareRecall", JSONObject()
                .put("beSharedBizExtInfo", JSONObject().put("drawActivityId", activityId).put("inviterUid", inviter))
                .put("requestType", "RPC").put("sceneCode", scene).put("shareId", shareId)
                .put("source", "chouchoule").put("userId", inviter))
            if (success(result)) { checkpoint(); mark(flag); count++ }
        }
        return count
    }

    private suspend fun call(method: String, body: JSONObject): JSONObject {
        checkpoint()
        val response = request(method, body)
        checkpoint()
        return response
    }

    companion object {
        fun p2pScene(taskBase: JSONObject): String {
            fun objectValue(value: Any?): JSONObject? = when (value) {
                is JSONObject -> value
                is String -> runCatching { JSONObject(value) }.getOrNull()
                else -> null
            }
            val parameters = objectValue(taskBase.opt("prodPlayParam")) ?: objectValue(taskBase.opt("bizInfo"))
                ?.let { objectValue(it.opt("prodPlayParam")) }
            return parameters?.optString("p2pSceneCode").orEmpty()
        }

        fun shareTaskBlocked(taskType: String, title: String, blacklist: Set<String>) = taskType in blacklist || title in blacklist

        private fun success(value: JSONObject) = value.optBoolean("success", false) || value.optBoolean("isSuccess", false)

        fun expandShareId(input: String, taskType: String): String? {
            val value = input.trim()
            if (taskType !in setOf("FOREST_NORMAL_DRAW_SHARE", "FOREST_ACTIVITY_DRAW_SHARE")) return null
            if (!value.matches(Regex("[A-Za-z0-9_-]+"))) return null
            if (value.length in 22..27) {
                val scenePart = if (taskType == "FOREST_NORMAL_DRAW_SHARE") "YINZOkxgPDkvWvkwkQXSDbZ" else "bxgpIW643h4FnWRjs9uZzng"
                return value + "4O7FEYDgn0xx0OehP5jt9" + scenePart + "77VUJcjlcZsjGio6MsAtmwxkxkx"
            }
            return value.takeIf { it.length in 60..256 && it.indexOf("4O7FEYDgn") in 22..27 }
        }
    }
}
