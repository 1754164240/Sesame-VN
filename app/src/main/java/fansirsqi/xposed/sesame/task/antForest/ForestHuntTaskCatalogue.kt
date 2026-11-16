package fansirsqi.xposed.sesame.task.antForest

import com.fasterxml.jackson.core.type.TypeReference
import fansirsqi.xposed.sesame.entity.OtherEntity
import fansirsqi.xposed.sesame.util.DataStore

/** 只同步服务端任务标题，供寻宝独立黑名单选择，不缓存账号或分享参数。 */
object ForestHuntTaskCatalogue {
    private const val KEY = "forest_hunt_task_catalogue"
    private fun titles(): Set<String> = runCatching {
        DataStore.getOrCreate(KEY, object : TypeReference<Set<String>>() {})
    }.getOrDefault(emptySet())

    fun list(): List<OtherEntity> = titles().sorted().map { OtherEntity(it, it) }

    fun remember(title: String) {
        if (title.isBlank()) return
        val current = titles()
        if (title !in current) DataStore.put(KEY, current + title)
    }
}
