package fansirsqi.xposed.sesame.task.antFarm

import fansirsqi.xposed.sesame.entity.OtherEntityProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class AntFarmFamilyOptionsTest {
    @Test
    fun `设置页实际家庭列表包含唯一的限时外卖红包任务`() {
        val options = OtherEntityProvider.farmFamilyOption()
            .filter { it.id == "familyTakeawayRedPacket" }
        assertEquals(1, options.size)
        assertEquals("领取外卖红包任务（限时）", options.single().name)
    }
}
