package fansirsqi.xposed.sesame.task.antOcean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class AntOceanAiFishTest {

    @Test
    fun `AI摸鱼开关属于神奇海洋且默认关闭`() {
        val fields = requireNotNull(AntOcean().fields)

        assertTrue(fields.containsKey("aiFish"))
        assertFalse(fields["aiFish"]?.value as Boolean)
    }

    @Test
    fun `主页解析被抓状态且未知结构不推定成功`() {
        val snapshot = AntOcean.parseAiFishHome(home("CAPTURED", 3, 7))
        val unknown = AntOcean.parseAiFishHome("""{"success":true}""")

        assertTrue(snapshot.isRecognized)
        assertEquals("CAPTURED", snapshot.fishStatus)
        assertEquals(3, snapshot.remainTouchChance)
        assertEquals(7, snapshot.touchTotal)
        assertFalse(unknown.isRecognized)
        assertNull(unknown.fishStatus)
    }

    @Test
    fun `任务解析限制等待时间并稳定选择救援任务`() {
        val snapshot = AntOcean.parseAiFishTasks(
            taskResponse(
                task("RESCUE_Z", "TODO", 90, "VISIT_FLOAT_BALL", AntOcean.AI_FISH_RESCUE_SCENE),
                task("RESCUE_A", "TODO", 10, "VISIT_FLOAT_BALL", AntOcean.AI_FISH_RESCUE_SCENE),
                task("RESCUE_ZERO", "TODO", 0, "VISIT_FLOAT_BALL", AntOcean.AI_FISH_RESCUE_SCENE),
                task("RESCUE_OTHER", "TODO", 15, "OTHER", AntOcean.AI_FISH_RESCUE_SCENE)
            )
        )

        assertTrue(snapshot.isRecognized)
        assertEquals(60, snapshot.tasks.first().waitSeconds)
        assertEquals("RESCUE_A", AntOcean.selectAiFishRescueTask(snapshot)?.taskType)
    }

    @Test
    fun `被抓时等待后使用专用接口找回且不调用通用任务接口`() {
        val gateway = FakeGateway(
            homes = dequeOf(home("CAPTURED", 3, 0), home("CAN_TOUCH", 0, 0), home("CAN_TOUCH", 0, 0)),
            rescueTasks = taskResponse(
                task("RESCUE_15S", "TODO", 15, "VISIT_FLOAT_BALL", AntOcean.AI_FISH_RESCUE_SCENE)
            ),
            mainTasks = dequeOf(taskResponse())
        )

        val result = AntOcean.AiFishRunner(gateway).run()

        assertTrue(result.isRescued)
        assertEquals(listOf(16_000L), gateway.waits)
        assertEquals(1, gateway.rescueCalls)
        assertTrue(gateway.finishCalls.isEmpty())
        assertTrue(gateway.receiveCalls.isEmpty())
    }

    @Test
    fun `救援状态未推进时停止主任务和摸鱼`() {
        val gateway = FakeGateway(
            homes = dequeOf(home("CAPTURED", 3, 0), home("CAPTURED", 3, 0)),
            rescueTasks = taskResponse(
                task("RESCUE_5S", "TODO", 5, "VISIT_FLOAT_BALL", AntOcean.AI_FISH_RESCUE_SCENE)
            )
        )

        val result = AntOcean.AiFishRunner(gateway).run()

        assertFalse(result.isRescued)
        assertTrue(gateway.mainListCalls == 0)
        assertEquals(0, gateway.touchCalls)
    }

    @Test
    fun `完成所有主任务并逐步回查领取奖励`() {
        val gateway = FakeGateway(
            homes = dequeOf(home("CAN_TOUCH", 0, 0), home("CAN_TOUCH", 0, 0)),
            mainTasks = dequeOf(
                taskResponse(
                    task("daily_add_touch_fish", "FINISHED", 0),
                    task("AIFISH_SHJF", "TODO", 5)
                ),
                taskResponse(
                    task("daily_add_touch_fish", "RECEIVED", 0),
                    task("AIFISH_SHJF", "TODO", 5)
                ),
                taskResponse(
                    task("daily_add_touch_fish", "RECEIVED", 0),
                    task("AIFISH_SHJF", "FINISHED", 5)
                ),
                taskResponse(
                    task("daily_add_touch_fish", "RECEIVED", 0),
                    task("AIFISH_SHJF", "RECEIVED", 5)
                ),
                taskResponse(
                    task("daily_add_touch_fish", "RECEIVED", 0),
                    task("AIFISH_SHJF", "RECEIVED", 5)
                )
            )
        )

        val result = AntOcean.AiFishRunner(gateway).run()

        assertEquals(listOf(5_000L), gateway.waits)
        assertEquals(listOf("ANTAIFISH|AIFISH_SHJF"), gateway.finishCalls)
        assertEquals(
            listOf("ANTAIFISH|daily_add_touch_fish", "ANTAIFISH|AIFISH_SHJF"),
            gateway.receiveCalls
        )
        assertEquals(1, result.completedTaskCount)
        assertEquals(2, result.receivedRewardCount)
    }

    @Test
    fun `单个任务抛出异常时继续处理后续任务`() {
        val gateway = FakeGateway(
            homes = dequeOf(home("CAN_TOUCH", 0, 0), home("CAN_TOUCH", 0, 0)),
            mainTasks = dequeOf(
                taskResponse(task("TASK_A", "TODO", 0), task("TASK_B", "TODO", 0)),
                taskResponse(task("TASK_A", "TODO", 0), task("TASK_B", "RECEIVED", 0)),
                taskResponse(task("TASK_A", "TODO", 0), task("TASK_B", "RECEIVED", 0))
            ),
            throwingFinishTasks = setOf("TASK_A")
        )

        val result = AntOcean.AiFishRunner(gateway).run()

        assertEquals(listOf("ANTAIFISH|TASK_A", "ANTAIFISH|TASK_B"), gateway.finishCalls)
        assertEquals(1, result.completedTaskCount)
    }

    @Test
    fun `保卫向日葵任务每天只完成一次`() {
        val taskList = taskResponse(task("AIFISH_ZHUANHUA_BWXRK", "TODO", 0))
        val gateway = FakeGateway(
            homes = dequeOf(
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0),
                home("CAN_TOUCH", 0, 0)
            ),
            mainTasks = dequeOf(
                taskList,
                taskResponse(task("AIFISH_ZHUANHUA_BWXRK", "RECEIVED", 0))
            ),
            defaultMainTasks = taskList
        )

        AntOcean.AiFishRunner(gateway).run()
        AntOcean.AiFishRunner(gateway).run()

        assertEquals(
            listOf("ANTAIFISH|AIFISH_ZHUANHUA_BWXRK"),
            gateway.finishCalls
        )
    }

    @Test
    fun `每日一次任务回查未推进时允许下轮重试`() {
        val gateway = FakeGateway(
            homes = dequeOf(home("CAN_TOUCH", 0, 0), home("CAN_TOUCH", 0, 0)),
            defaultMainTasks = taskResponse(task("AIFISH_ZHUANHUA_BWXRK", "TODO", 0))
        )
        AntOcean.AiFishRunner(gateway).run()
        AntOcean.AiFishRunner(gateway).run()
        assertEquals(2, gateway.finishCalls.size)
    }

    @Test
    fun `摸鱼响应无进展时立即停止`() {
        val gateway = FakeGateway(
            homes = dequeOf(home("CAN_TOUCH", 1, 0), home("CAN_TOUCH", 1, 0)),
            mainTasks = dequeOf(taskResponse()),
            touches = dequeOf(home("CAN_TOUCH", 1, 0))
        )

        val result = AntOcean.AiFishRunner(gateway).run()

        assertEquals(1, gateway.touchCalls)
        assertEquals(0, result.touchCount)
    }

    @Test
    fun `摸鱼单轮最多执行二十次`() {
        val touches = ArrayDeque<String>()
        for (count in 1..20) {
            touches.add(home("CAN_TOUCH", 25 - count, count))
        }
        val gateway = FakeGateway(
            homes = dequeOf(home("CAN_TOUCH", 25, 0), home("CAN_TOUCH", 25, 0)),
            mainTasks = dequeOf(taskResponse()),
            touches = touches
        )

        val result = AntOcean.AiFishRunner(gateway).run()

        assertEquals(20, gateway.touchCalls)
        assertEquals(20, result.touchCount)
    }

    @Test
    fun `动作响应仅接受明确成功结构`() {
        assertTrue(AntOcean.isAiFishActionAccepted("""{"success":true}"""))
        assertTrue(AntOcean.isAiFishActionAccepted("""{"resultCode":"SUCCESS"}"""))
        assertTrue(AntOcean.isAiFishActionAccepted("""{"resData":{"code":"100000000"}}"""))
        assertFalse(AntOcean.isAiFishActionAccepted("""{"success":false}"""))
        assertFalse(AntOcean.isAiFishActionAccepted("not-json"))
    }

    private class FakeGateway(
        private val homes: ArrayDeque<String> = ArrayDeque(),
        private val rescueTasks: String = taskResponse(),
        private val mainTasks: ArrayDeque<String> = ArrayDeque(),
        private val touches: ArrayDeque<String> = ArrayDeque(),
        private val throwingFinishTasks: Set<String> = emptySet(),
        private val defaultMainTasks: String = taskResponse()
    ) : AntOcean.AiFishGateway {
        val waits = mutableListOf<Long>()
        val finishCalls = mutableListOf<String>()
        val receiveCalls = mutableListOf<String>()
        var rescueCalls = 0
        var touchCalls = 0
        var mainListCalls = 0
        private val completedTodayTaskTypes = mutableSetOf<String>()

        override fun queryStatus() = """{"success":true}"""

        override fun queryHome(): String = homes.pollFirst() ?: home("CAN_TOUCH", 0, 0)

        override fun listTasks(sceneCode: String): String {
            if (sceneCode == AntOcean.AI_FISH_RESCUE_SCENE) return rescueTasks
            mainListCalls++
            return mainTasks.pollFirst() ?: defaultMainTasks
        }

        override fun finishTask(sceneCode: String, taskType: String): String {
            finishCalls += "$sceneCode|$taskType"
            if (taskType in throwingFinishTasks) {
                error("模拟任务异常")
            }
            return """{"success":true}"""
        }

        override fun receiveTaskAward(sceneCode: String, taskType: String): String {
            receiveCalls += "$sceneCode|$taskType"
            return """{"success":true}"""
        }

        override fun hasCompletedToday(taskType: String): Boolean =
            taskType in completedTodayTaskTypes

        override fun markCompletedToday(taskType: String) {
            completedTodayTaskTypes += taskType
        }

        override fun rescueFish(): String {
            rescueCalls++
            return """{"success":true}"""
        }

        override fun touchFish(): String {
            touchCalls++
            return touches.pollFirst() ?: """{"success":false}"""
        }

        override fun waitMillis(millis: Long) {
            waits += millis
        }
    }

    companion object {
        private fun dequeOf(vararg values: String) = ArrayDeque(values.toList())

        private fun home(status: String, remain: Int, total: Int): String =
            """{"success":true,"myFish":{"interactVO":{"fishInteractStatus":"$status","remainTouchChance":$remain,"touchTotal":$total}}}"""

        private fun taskResponse(vararg tasks: String): String =
            """{"success":true,"taskInfoList":[${tasks.joinToString(",") }]}"""

        private fun task(
            taskType: String,
            status: String,
            waitSeconds: Int,
            playType: String = "VISIT_FLOAT_BALL",
            sceneCode: String = "ANTAIFISH"
        ): String = """
            {
              "taskBaseInfo": {
                "bizInfo": "{\"taskTitle\":\"$taskType\"}",
                "prodPlayParam": "{\"timeCount\":$waitSeconds}",
                "sceneCode": "$sceneCode",
                "taskStatus": "$status",
                "taskType": "$taskType",
                "taskProdPlayType": "$playType"
              },
              "taskRights": {"awardCount": 1, "directReceiveAward": false}
            }
        """.trimIndent()
    }
}
