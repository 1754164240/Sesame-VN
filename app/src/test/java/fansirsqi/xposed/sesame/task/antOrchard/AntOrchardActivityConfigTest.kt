package fansirsqi.xposed.sesame.task.antOrchard

import org.junit.Assert.*
import org.junit.Test

class AntOrchardActivityConfigTest {
    @Test
    fun `新增活动开关默认关闭且两类任务名单独立为空`() {
        val fields = AntOrchard().fields
        for (key in listOf("orchardChouChouLe", "receiveOrchardGameStay", "receiveSpreadManureStage", "orchardOptionalPlay", "autoOrchardDrawBlacklist", "autoOrchardPlayBlacklist")) {
            assertEquals(key, false, fields[key]?.value)
        }
        assertEquals(emptySet<String>(), fields["orchardDrawBlacklist"]?.value)
        assertEquals(emptySet<String>(), fields["orchardPlayBlacklist"]?.value)
        assertNotSame(fields["orchardDrawBlacklist"]?.value, fields["orchardPlayBlacklist"]?.value)
    }
}
