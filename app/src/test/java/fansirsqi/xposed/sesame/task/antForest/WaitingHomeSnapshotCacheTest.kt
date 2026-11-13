package fansirsqi.xposed.sesame.task.antForest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking

class WaitingHomeSnapshotCacheTest {
    @Test
    fun `同一好友多个球恢复时只查询一次主页`() {
        var queries = 0
        val snapshots = WaitingHomeSnapshotCache({ false }) { _, _ ->
            queries++
            """{"success":true,"now":1000,"usingUserProps":[]}"""
        }

        repeat(4) { assertEquals(1000L, snapshots.get("friend", "PKContest")?.getLong("now")) }
        snapshots.get("other", "PKContest")
        assertEquals(2, queries)
    }

    @Test
    fun `验证失败及空主页不能当作无保护罩恢复`() {
        listOf(
            """{"success":false,"resultCode":"RPC_VERIFICATION_REQUIRED","resultDesc":"触发安全验证，请人工验证后继续"}""",
            """{"error":3000,"errorMessage":"系统出错，正在排查","errorNo":3,"errorTip":"3000"}""",
            "{}", "", "null"
        ).forEach { response ->
            val snapshots = WaitingHomeSnapshotCache({ false }) { _, _ -> response }
            assertNull(response, snapshots.get("friend", "PKContest"))
        }
    }

    @Test
    fun `暂停期间以及查询途中触发暂停均不能恢复任务`() {
        var paused = true
        var queries = 0
        val snapshots = WaitingHomeSnapshotCache({ paused }) { _, _ ->
            queries++
            paused = true
            """{"success":true,"now":1000,"usingUserProps":[]}"""
        }

        assertNull(snapshots.get("friend", "PKContest"))
        assertEquals(0, queries)
        paused = false
        assertNull(snapshots.get("friend", "PKContest"))
        assertEquals(1, queries)
    }

    @Test
    fun `失败主页在同一恢复批次不会逐球重复请求`() {
        var queries = 0
        val snapshots = WaitingHomeSnapshotCache({ false }) { _, _ ->
            queries++
            """{"success":false,"resultCode":"FAIL"}"""
        }

        repeat(4) { assertNull(snapshots.get("friend", "PKContest")) }
        assertEquals(1, queries)
    }

    @Test
    fun `恢复中遇到验证挂起当前任务并在解除后重新查询`() = runBlocking {
        var paused = false
        var queries = 0
        var waits = 0
        val snapshots = WaitingHomeSnapshotCache({ paused }) { _, _ ->
            queries++
            if (queries == 1) {
                paused = true
                """{"success":false,"resultCode":"RPC_VERIFICATION_REQUIRED"}"""
            } else {
                """{"success":true,"now":2000,"usingUserProps":[]}"""
            }
        }

        val result = snapshots.getWhenAvailable("friend", "PKContest") {
            waits++
            paused = false
        }
        assertEquals(2000L, result?.getLong("now"))
        assertEquals(2, queries)
        assertEquals(1, waits)
    }

    @Test
    fun `初始已经暂停时等待解除而不是结束恢复批次`() = runBlocking {
        var paused = true
        var queries = 0
        val snapshots = WaitingHomeSnapshotCache({ paused }) { _, _ ->
            queries++
            """{"success":true,"now":3000}"""
        }
        val result = snapshots.getWhenAvailable("friend", "PKContest") { paused = false }
        assertEquals(3000L, result?.getLong("now"))
        assertEquals(1, queries)
    }
}
