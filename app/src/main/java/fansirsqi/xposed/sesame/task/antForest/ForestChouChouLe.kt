package fansirsqi.xposed.sesame.task.antForest

import fansirsqi.xposed.sesame.data.Status
import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.hook.RequestManager
import fansirsqi.xposed.sesame.task.TaskStatus
import fansirsqi.xposed.sesame.util.GlobalThreadPools.sleepCompat
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ResChecker
import fansirsqi.xposed.sesame.util.maps.UserMap
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.CancellationException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 森林寻宝任务处理类 (每天自动执行, 完成后标记)
 */
data class ForestHuntOptions(
    val doTasks: Boolean = true,
    val draw: Boolean = true,
    val assist: Boolean = false,
    val forceNormal: Boolean = false,
    val forceActivity: Boolean = false,
    val shareIds: Set<String> = emptySet(),
    val blacklist: Set<String> = emptySet(),
    val autoBlacklist: Boolean = false
)

class ForestChouChouLe(
    private val options: ForestHuntOptions = ForestHuntOptions(),
    private val active: () -> Boolean = { true },
    private val onTask: (String) -> Unit = {},
    private val onBlocked: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "ForestChouChouLe"
        private const val SOURCE = "task_entry"

        // 场景代码常量
        private const val SCENE_NORMAL = "ANTFOREST_NORMAL_DRAW"
        private const val SCENE_ACTIVITY = "ANTFOREST_ACTIVITY_DRAW"
        private const val MAX_TASK_FAIL_COUNT = 3

        /**
         * 抽奖场景数据类
         */
        private data class Scene(
            val id: String,
            val code: String,
            val name: String,
            val flag: String
        ) {
            val taskCode get() = "${code}_TASK"
        }

        // 扩展函数：简化 JSON 解析和检查
        private fun String.toJson(): JSONObject? = runCatching { JSONObject(this) }.getOrNull()
        private fun JSONObject.check(): Boolean = ResChecker.checkRes(TAG, this)

        fun shouldSkipFailedTask(failedCount: Int): Boolean {
            return failedCount >= MAX_TASK_FAIL_COUNT
        }

        // 动态获取抽奖场景配置
        private fun getScenes(): List<Scene> {
            val defaultScenes = listOf(
                Scene("2025112701", SCENE_NORMAL, "森林寻宝", "forest::chouChouLe::normal::completed"),
                Scene("20251024", SCENE_ACTIVITY, "森林寻宝IP", "forest::chouChouLe::activity::completed")
            )

            return runCatching {
                val scenes = mutableListOf<Scene>()
                // 使用普通场景查询
                val response = AntForestRpcCall.enterDrawActivityopengreen("", SCENE_NORMAL, SOURCE).toJson() ?: return@runCatching defaultScenes

                if (response.optBoolean("success", false)) {
                    val drawSceneGroups = response.optJSONArray("drawSceneGroups") ?: return@runCatching defaultScenes

                    for (i in 0 until drawSceneGroups.length()) {
                        val sceneGroup = drawSceneGroups.optJSONObject(i) ?: continue
                        val drawActivity = sceneGroup.optJSONObject("drawActivity") ?: continue

                        val activityId = drawActivity.optString("activityId")
                        val sceneCode = drawActivity.optString("sceneCode")
                        val name = sceneGroup.optString("name", "未知活动")

                        val flag = when (sceneCode) {
                            SCENE_NORMAL -> "forest::chouChouLe::normal::completed"
                            SCENE_ACTIVITY -> "forest::chouChouLe::activity::completed"
                            else -> "forest::chouChouLe::${sceneCode.lowercase(Locale.getDefault())}::completed"
                        }
                        scenes.add(Scene(activityId, sceneCode, name, flag))
                    }
                }
                if (scenes.isEmpty()) defaultScenes else scenes
            }.getOrElse {
                Log.printStackTrace(TAG, "获取抽奖场景配置失败, 使用默认配置", it)
                defaultScenes
            }
        }
    }

    private val taskTryCount = ConcurrentHashMap<String, AtomicInteger>()
    private val captureWaitTasks = ConcurrentHashMap.newKeySet<String>()
    private val runUid = UserMap.currentUid
    private val blocked = options.blacklist.toMutableSet()

    private fun checkActive() {
        if (!active() || runUid.isNullOrBlank() || UserMap.currentUid != runUid ||
            ApplicationHook.offline || RequestManager.isVerificationPaused() || Thread.currentThread().isInterrupted) {
            throw CancellationException("森林寻宝已停止或切换账号")
        }
    }

    private inline fun rpc(block: () -> String?): JSONObject? {
        checkActive()
        val result = block().orEmpty().toJson()
        checkActive()
        return result
    }

    private fun skipScene(s: Scene) = options.doTasks && options.draw && !options.assist &&
        !options.forceNormal && !options.forceActivity && Status.hasFlagToday(s.flag)

    suspend fun chouChouLe() {
        runCatching {
            checkActive()
            val scenes = getScenes()
            checkActive()
            if (scenes.all { skipScene(it) }) {
                Log.record("⏭️ 今天所有森林寻宝任务已完成, 跳过执行")
                return
            }

            Log.record("开始处理森林寻宝, 共 ${scenes.size} 个场景")
            scenes.forEach {
                processScene(it)
                sleepCompat(100L)
            }
        }.onFailure { if (it is CancellationException) throw it else Log.printStackTrace(TAG, "执行异常", it) }
    }

    private suspend fun processScene(s: Scene) = runCatching {
        checkActive()
        if (skipScene(s)) {
            Log.record("⏭️ ${s.name} 今天已完成, 跳过")
            return@runCatching
        }

        Log.record("👉 开始处理: ${s.name}")

        // 1. 检查活动有效期
        val enterResp = rpc { AntForestRpcCall.enterDrawActivityopengreen(s.id, s.code, SOURCE) }
        if (enterResp == null || !enterResp.check()) return@runCatching

        val drawActivity = enterResp.optJSONObject("drawActivity")
        if (drawActivity != null) {
            val now = System.currentTimeMillis()
            val startTime = drawActivity.optLong("startTime")
            val endTime = drawActivity.optLong("endTime")
            if (now !in startTime..endTime) {
                Log.record("⛔ ${s.name} 活动不在有效期内, 跳过")
                return@runCatching
            }
        }

        processAssist(s)

        // 2. 任务、抽奖和助力分别受配置控制。
        if (options.doTasks) processTasksLoop(s)

        // 3. 执行抽奖
        if (options.draw) processLottery(s)

        // 4. 最终检查完成状态
        if (options.doTasks && options.draw) checkCompletion(s)

    }.onFailure { if (it is CancellationException) throw it else Log.printStackTrace(TAG, "${s.name} 处理异常", it) }

    private suspend fun processAssist(s: Scene) {
        val force = if (s.code == SCENE_NORMAL) options.forceNormal else if (s.code == SCENE_ACTIVITY) options.forceActivity else false
        if ((!options.assist && !force) || options.shareIds.isEmpty()) return
        val taskType = if (s.code == SCENE_NORMAL) "FOREST_NORMAL_DRAW_SHARE" else if (s.code == SCENE_ACTIVITY) "FOREST_ACTIVITY_DRAW_SHARE" else return
        val list = rpc { AntForestRpcCall.listTaskopengreen(s.taskCode, SOURCE) }
        if (list == null || !list.check()) return
        val tasks = list.optJSONArray("taskInfoList") ?: return
        var p2pScene = ""
        var targetBlocked = false
        for (i in 0 until tasks.length()) {
            val base = tasks.optJSONObject(i)?.optJSONObject("taskBaseInfo") ?: continue
            val biz = base.optJSONObject("bizInfo") ?: runCatching { JSONObject(base.optString("bizInfo")) }.getOrNull() ?: continue
            val name = biz.optString("title", base.optString("taskType"))
            if (name.isNotBlank()) { checkActive(); onTask(name) }
            if (base.optString("taskType") != taskType) continue
            if (ForestHuntAssist.shareTaskBlocked(taskType, name, blocked)) { targetBlocked = true; continue }
            if (options.assist) p2pScene = ForestHuntAssist.p2pScene(base)
        }
        if (targetBlocked) return
        if (p2pScene.isBlank() && force) {
            if (taskType in blocked) return
            p2pScene = if (s.code == SCENE_NORMAL) "FOREST_NORMAL_20250829_SHARE" else "FOREST_NORMAL_20251024_SHARE"
        }
        if (p2pScene.isBlank()) return
        val assist = ForestHuntAssist(
            request = { method, body -> JSONObject(RequestManager.requestString(method, JSONArray().put(body).toString(), 1, 0)) },
            checkpoint = { checkActive() }, hasFlag = Status::hasFlagToday, mark = Status::setFlagToday,
            currentUserId = runUid.orEmpty()
        )
        val helped = assist.run(s.id, p2pScene, taskType, options.shareIds)
        if (helped > 0) Log.forest("${s.name} 助力成功${helped}位好友")
    }

    /**
     * 循环处理任务列表
     */
    private fun processTasksLoop(s: Scene) {
        repeat(3) { loop ->
            Log.record("${s.name} 第 ${loop + 1} 轮任务检查")
            val tasksResp = rpc { AntForestRpcCall.listTaskopengreen(s.taskCode, SOURCE) } ?: return@repeat
            if (!tasksResp.check()) return@repeat

            val taskList = tasksResp.optJSONArray("taskInfoList") ?: return@repeat
            var hasChange = false

            for (i in 0 until taskList.length()) {
                checkActive()
                val task = taskList.optJSONObject(i) ?: continue
                if (processSingleTask(s, task)) {
                    hasChange = true
                }
            }

            if (!hasChange) {
                Log.record("${s.name} 本轮无任务状态变更, 结束任务循环")
                return
            }
            if (loop < 2) sleepCompat(100L)
        }
    }

    /**
     * 执行抽奖逻辑
     */
    private fun processLottery(s: Scene) {
        val enterResp = rpc { AntForestRpcCall.enterDrawActivityopengreen(s.id, s.code, SOURCE) } ?: return
        if (!enterResp.check()) return

        val drawAsset = enterResp.optJSONObject("drawAsset") ?: return
        var balance = drawAsset.optInt("blance", 0)
        val total = drawAsset.optInt("totalTimes", 0)

        Log.record("${s.name} 剩余抽奖次数: $balance / $total")

        var retry = 0
        // 最多抽50次，防止死循环
        while (balance > 0 && retry < 50) {
            retry++
            Log.record("${s.name} 第 $retry 次抽奖")

            val before = balance
            val drawResp = rpc { AntForestRpcCall.drawopengreen(s.id, s.code, SOURCE, runUid) }
            if (drawResp == null || !drawResp.check()) {
                break
            }

            balance = drawResp.optJSONObject("drawAsset")?.optInt("blance", -1) ?: -1
            if (balance < 0 || balance >= before) break
            val prize = drawResp.optJSONObject("prizeVO")
            if (prize != null) {
                val name = prize.optString("prizeName", "未知奖品")
                val num = prize.optInt("prizeNum", 1)
                Log.forest("${s.name} 🎁 [获得: $name * $num] 剩余次数: $balance")
            }

            if (balance > 0) sleepCompat(100L)
        }
    }

    /**
     * 检查是否所有任务都已完成，并设置 Flag
     */
    private fun checkCompletion(s: Scene) {
        val resp = rpc { AntForestRpcCall.listTaskopengreen(s.taskCode, SOURCE) } ?: return
        if (!resp.check()) return

        val taskList = resp.optJSONArray("taskInfoList") ?: return
        var total = 0
        var completed = 0
        var allDone = true

        for (i in 0 until taskList.length()) {
            val task = taskList.optJSONObject(i) ?: continue
            val baseInfo = task.optJSONObject("taskBaseInfo") ?: continue

            val taskType = baseInfo.optString("taskType")
            val taskStatus = baseInfo.optString("taskStatus")
            val bizInfoStr = baseInfo.optString("bizInfo")
            val taskName = if (bizInfoStr.isNotEmpty()) {
                JSONObject(bizInfoStr).optString("title", taskType)
            } else taskType

            if (isBlockedTask(taskType, taskName)) continue

            total++
            if (taskStatus == TaskStatus.RECEIVED.name) {
                completed++
            } else {
                allDone = false
                Log.record("${s.name} 未完成: $taskName [$taskStatus]")
            }
        }

        Log.record("${s.name} 进度: $completed / $total")
        val drawState = rpc { AntForestRpcCall.enterDrawActivityopengreen(s.id, s.code, SOURCE) }
        val noDraws = drawState?.takeIf { it.check() }?.optJSONObject("drawAsset")?.optInt("blance", -1) == 0
        if (allDone && noDraws) {
            checkActive()
            Status.setFlagToday(s.flag)
            val msg = if (total > 0) "全部完成" else "无有效任务"
            Log.record("✅ ${s.name} $msg ($completed/$total)")
        } else {
            Log.record("⚠️ ${s.name} 未全部完成")
        }
    }

    /**
     * 判断任务是否在屏蔽列表中
     */
    private fun isBlockedTask(taskType: String, taskName: String): Boolean {
        return taskType in blocked || taskName in blocked || ForestDrawTaskPolicy.actionFor(taskType, taskName) ==
            ForestDrawTaskAction.WAIT_FOR_CAPTURE
    }

    /**
     * 处理单个任务分发
     * @return 任务状态是否有变更
     */
    private fun processSingleTask(s: Scene, task: JSONObject): Boolean {
        val baseInfo = task.optJSONObject("taskBaseInfo") ?: return false
        val bizInfoStr = baseInfo.optString("bizInfo")
        val bizInfo = if (bizInfoStr.isNotEmpty()) JSONObject(bizInfoStr) else JSONObject()

        val taskName = bizInfo.optString("title", "未知任务")
        val taskCode = baseInfo.optString("sceneCode")
        val taskStatus = baseInfo.optString("taskStatus")
        val taskType = baseInfo.optString("taskType")

        checkActive()
        if (taskName.isNotBlank()) onTask(taskName)

        if (isBlockedTask(taskType, taskName)) return false

        Log.record("${s.name} 任务: $taskName [$taskStatus]")

        val taskKey = "${s.code}:$taskCode:$taskType"
        return when (taskStatus) {
            TaskStatus.TODO.name -> handleTodoTask(s, taskName, taskCode, taskType, taskKey)
            TaskStatus.FINISHED.name -> handleFinishedTask(s, taskName, taskCode, taskType, taskKey)
            TaskStatus.RECEIVED.name -> {
                taskTryCount.remove(taskKey)
                false
            }
            else -> false
        }
    }

    private fun handleTodoTask(
        s: Scene,
        name: String,
        code: String,
        type: String,
        taskKey: String
    ): Boolean {
        val action = ForestDrawTaskPolicy.actionFor(type, name)
        if (action == ForestDrawTaskAction.WAIT_FOR_CAPTURE) {
            if (captureWaitTasks.add(taskKey)) {
                Log.record(TAG, "${s.name} 任务等待补充抓包，已跳过: $name [$type]")
            }
            return false
        }

        val attemptedCount = taskTryCount[taskKey]?.get() ?: 0
        if (shouldSkipFailedTask(attemptedCount)) {
            Log.record(TAG, "${s.name} 跳过失败过多任务: $name")
            return false
        }
        val currentAttempt = taskTryCount.computeIfAbsent(taskKey) { AtomicInteger(0) }.incrementAndGet()
        checkActive()

        val result = when (action) {
            ForestDrawTaskAction.EXCHANGE_VITALITY -> {
                Log.record("${s.name} 兑换活力值: $name")
                AntForestRpcCall.exchangeTimesFromTaskopengreen(s.id, s.code, SOURCE, code, type)
            }
            ForestDrawTaskAction.FINISH_XLIGHT -> {
                Log.record("${s.name} 执行任务(模拟耗时): $name")
                sleepCompat(100L)
                checkActive()
                AntForestRpcCall.finishTask4Chouchoule(type, code)
            }
            ForestDrawTaskAction.FINISH_STANDARD -> {
                Log.record("${s.name} 执行任务(模拟耗时): $name")
                sleepCompat(100L)
                checkActive()
                AntForestRpcCall.finishTaskopengreen(type, code)
            }
            ForestDrawTaskAction.WAIT_FOR_CAPTURE -> return false
        }

        val resJson = result.toJson()
        checkActive()
        return if (resJson != null && resJson.check()) {
            Log.forest("${s.name} 🧾 $name")
            true
        } else {
            val retryable = resJson == null || ForestDrawTaskPolicy.isRetryableFailure(
                resultCode = resJson.optString("resultCode"),
                resultDescription = resJson.optString(
                    "resultDesc",
                    resJson.optString("memo", resJson.optString("desc"))
                )
            )
            if (!retryable) {
                taskTryCount[taskKey]?.set(MAX_TASK_FAIL_COUNT)
                if (options.autoBlacklist) { blocked += name; onBlocked(name) }
                val code = resJson.optString("resultCode")
                val description = resJson.optString("resultDesc").ifBlank {
                    resJson.optString("memo").ifBlank { resJson.optString("desc") }
                }
                Log.error(TAG, "${s.name} 任务不可重试，已停止: $name [$code] $description")
            } else {
                Log.error(TAG, "${s.name} 任务失败($currentAttempt): $name")
            }
            false
        }
    }

    private fun handleFinishedTask(
        s: Scene,
        name: String,
        code: String,
        type: String,
        taskKey: String
    ): Boolean {
        Log.record("${s.name} 领取奖励: $name")
        sleepCompat(100L)
        val res = rpc { AntForestRpcCall.receiveTaskAwardopengreen(SOURCE, code, type) }
        return if (res != null && res.check()) {
            taskTryCount.remove(taskKey)
            Log.forest("${s.name} 🧾 $name 奖励领取成功")
            true
        } else {
            Log.error(TAG, "${s.name} 奖励领取失败: $name")
            false
        }
    }
}
