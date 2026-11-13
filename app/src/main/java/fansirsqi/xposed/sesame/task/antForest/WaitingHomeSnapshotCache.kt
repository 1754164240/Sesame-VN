package fansirsqi.xposed.sesame.task.antForest

import org.json.JSONObject

/** 同一恢复批次按好友复用主页，仅接受成功且未暂停的响应。 */
class WaitingHomeSnapshotCache(
    private val isPaused: () -> Boolean,
    private val queryHome: (String, String) -> String?
) {
    private val snapshots = mutableMapOf<String, JSONObject?>()

    suspend fun getWhenAvailable(
        userId: String,
        fromTag: String,
        waitForResume: suspend () -> Unit
    ): JSONObject? {
        while (true) {
            if (isPaused()) {
                waitForResume()
                // 验证暂停期间主页可能变化，恢复后重新查询。
                snapshots.clear()
                continue
            }
            val home = get(userId, fromTag)
            if (!isPaused()) return home
        }
    }

    fun get(userId: String, fromTag: String): JSONObject? {
        if (isPaused()) return null
        if (!snapshots.containsKey(userId)) {
            val response = queryHome(userId, fromTag)
            if (isPaused()) return null
            val home = runCatching { JSONObject(response.orEmpty()) }.getOrNull()
            // 失败也在本批次缓存，防止同一好友的每个球重复查询失败接口。
            snapshots[userId] = home?.takeIf { it.optBoolean("success", false) }
        }
        return snapshots[userId]
    }
}
