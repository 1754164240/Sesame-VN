package fansirsqi.xposed.sesame.task.goldenbeans

import fansirsqi.xposed.sesame.model.ModelFields
import fansirsqi.xposed.sesame.model.ModelGroup
import fansirsqi.xposed.sesame.task.ModelTask
import kotlinx.coroutines.runInterruptible

/** 金豆业务同步处理器绑定当前协程，停止任务时中断等待并阻止后续请求。 */
class GoldenBeans : ModelTask() {
    private val runner = GoldenBeansRunner()
    override fun getName() = "金豆夺宝"
    override fun getIcon() = "ConsumeGold.svg"
    override fun getGroup() = ModelGroup.GOLDENBEANS
    override fun getFields(): ModelFields = runner.fields
    override suspend fun runSuspend() {
        val guard = captureExecutionGuard()
        runInterruptible {
            try {
                GoldenBeansRuntime.begin(guard)
                runner.run()
            } finally { GoldenBeansRuntime.end() }
        }
    }
}
