package fansirsqi.xposed.sesame.task.antOrchard

import com.fasterxml.jackson.core.type.TypeReference
import fansirsqi.xposed.sesame.entity.MapperEntity
import fansirsqi.xposed.sesame.util.DataStore
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.maps.UserMap

/** 任务目录按账号及业务隔离，选项来自已实际查询到的服务端任务。 */
object OrchardActivityTaskCatalog {
    private fun key(uid: String, kind: String) = "orchard_activity_catalog::$uid::$kind"

    fun remember(uid: String, kind: String, id: String, title: String) {
        if (uid != UserMap.currentUid) return
        try {
            val values = DataStore.getOrCreate(key(uid, kind), object : TypeReference<LinkedHashMap<String, String>>() {})
            if (values[id] != title) {
                values[id] = title
                DataStore.put(key(uid, kind), values)
            }
        } catch (e: Exception) {
            Log.printStackTrace("农场活动", "保存任务目录失败", e)
        }
    }

    fun options(kind: String): List<MapperEntity> {
        val uid = UserMap.currentUid ?: return emptyList()
        return try {
            DataStore.getOrCreate(key(uid, kind), object : TypeReference<LinkedHashMap<String, String>>() {})
                .map { (key, title) -> OrchardActivityTaskEntity(key, title) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private class OrchardActivityTaskEntity(taskId: String, title: String) : MapperEntity() {
        init { id = taskId; name = title }
    }
}
