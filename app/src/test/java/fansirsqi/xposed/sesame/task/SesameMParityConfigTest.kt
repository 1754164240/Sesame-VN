package fansirsqi.xposed.sesame.task

import fansirsqi.xposed.sesame.model.ModelOrder
import fansirsqi.xposed.sesame.task.antDodo.AntDodo
import fansirsqi.xposed.sesame.task.antFarm.AntFarm
import fansirsqi.xposed.sesame.task.antForest.AntForest
import fansirsqi.xposed.sesame.task.antOcean.AntOcean
import fansirsqi.xposed.sesame.task.goldenbeans.GoldenBeans
import org.junit.Assert.*
import org.junit.Test

class SesameMParityConfigTest {
    @Test fun `金豆模块注册且所有业务开关关闭及兑换名单为空`() {
        assertTrue(ModelOrder.allConfig.contains(GoldenBeans::class.java))
        val fields = GoldenBeans().fields
        for (key in listOf("goldenBeansVisitGarden", "goldenBeansAutoTask", "goldenBeansSign", "goldenBeansGameDraw",
            "goldenBeansAutoMine", "goldenBeansCollectReward", "AutoGoldenBeansTaskList", "goldenBeansAutoManureExchange",
            "goldenBeansAutoSesameExchange", "goldenBeansMallExchange")) {
            assertEquals(key, false, fields[key]?.value)
        }
        assertEquals(emptySet<String>(), fields["GoldenBeansTaskList"]?.value)
        assertEquals(emptyMap<String, Int>(), fields["GoldenBeansMallItemList"]?.value)
    }

    @Test fun `AI厨房独立于旧厨房且制作默认关闭名单为空`() {
        val fields = AntFarm().fields
        assertNotNull(fields["kitchen"])
        assertEquals(false, fields["aiKitchen"]?.value)
        assertEquals(false, fields["aiKitchenCook"]?.value)
        assertEquals(emptySet<String>(), fields["AntKitchenTaskList"]?.value)
    }

    @Test fun `物种与摸鱼新增消费开关关闭而旧配置仍保留`() {
        val dodo = AntDodo().fields!!
        for (key in listOf("scratchCard", "universalDetailedFilter", "giftDetailedFilter")) assertEquals(key, false, dodo[key]?.value)
        assertNotNull(dodo["usePropUNIVERSALCARDType"])
        assertNotNull(dodo["autoGenerateBook"])
        val ocean = AntOcean().fields!!
        assertEquals(false, ocean["aiFishAutoDraw"]?.value)
        assertEquals(false, ocean["aiFishAppraise"]?.value)
        assertNotNull(ocean["aiFish"])
    }

    @Test fun `森林寻宝沿用总开关并将助力兑换名单独立控制`() {
        val fields = AntForest().fields
        assertEquals(false, fields["forestChouChouLe"]?.value)
        for (key in listOf("ForestHuntHelp", "NORMALForestHuntHelp", "ACTIVITYForestHuntHelp", "AutoAntForestHuntTaskList")) assertEquals(key, false, fields[key]?.value)
        assertEquals("", fields["ForestHuntShareIds"]?.value)
        assertEquals(emptySet<String>(), fields["AntForestHuntTaskList"]?.value)
        assertEquals(true, fields["ForestHuntTasks"]?.value)
        assertEquals(true, fields["ForestHuntDraw"]?.value)
    }
}
