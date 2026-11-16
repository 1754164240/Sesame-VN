package fansirsqi.xposed.sesame.task

import fansirsqi.xposed.sesame.model.ModelFields
import fansirsqi.xposed.sesame.model.ModelGroup
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class ExecutionGuardTest {
    private fun task() = object : ModelTask() {
        override fun getName() = "执行守卫测试"
        override fun getIcon() = ""
        override fun getGroup() = ModelGroup.OTHER
        override fun getFields() = ModelFields()
        override fun check() = true
    }

    @Test fun `未启动或已经取消的任务不能通过Java执行守卫`() {
        val task = task()
        assertFalse(task.captureExecutionGuard().asBoolean)
        val field = ModelTask::class.java.getDeclaredField("currentTaskJob").apply { isAccessible = true }
        val job = Job()
        field.set(task, job)
        val guard = task.captureExecutionGuard()
        assertTrue(guard.asBoolean)
        job.cancel()
        assertFalse(guard.asBoolean)
    }

    @Test fun `旧轮次守卫不能借新轮次活动状态继续运行`() {
        val task = task()
        val field = ModelTask::class.java.getDeclaredField("currentTaskJob").apply { isAccessible = true }
        val old = Job()
        field.set(task, old)
        val guard = task.captureExecutionGuard()
        field.set(task, Job())
        assertTrue(old.isActive)
        assertFalse(guard.asBoolean)
        assertTrue(task.captureExecutionGuard().asBoolean)
    }
}
