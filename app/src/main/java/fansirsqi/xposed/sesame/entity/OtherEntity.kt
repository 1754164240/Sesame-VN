package fansirsqi.xposed.sesame.entity

import fansirsqi.xposed.sesame.task.antDodo.AntDodo
import fansirsqi.xposed.sesame.task.other.credit2101.Credit2101

class OtherEntity(id: String, name: String) : MapperEntity() {
    init {
        this.id = id
        this.name = name
    }
}

object OtherEntityProvider {
    @JvmStatic
    fun listEcoLifeOptions(): List<OtherEntity> = listOf(
        OtherEntity("tick", "绿色行动🍃"),
        OtherEntity("plate", "光盘行动💽")
    )

    @JvmStatic
    fun listHealthcareOptions(): List<OtherEntity> = listOf(
        OtherEntity("FEEDS", "绿色医疗💉"),
        OtherEntity("BILL", "电子小票🎫")
    )

    @JvmStatic
    fun farmFamilyOption():List<OtherEntity> = listOf(
        OtherEntity("familySign", "每日签到📅"),
        OtherEntity("assignRights", "使用顶梁柱特权👷‍♂️"),
        OtherEntity("familyClaimReward", "领取奖励🏆️"),
        OtherEntity("familyTakeawayRedPacket", "领取外卖红包任务（限时）"),
        OtherEntity("feedFamilyAnimal", "帮喂小鸡🐔"),
        OtherEntity("eatTogetherConfig", "请吃美食🍲"),
        OtherEntity("deliverMsgSend", "道早安🌞"),
        OtherEntity("walkDonate", "捐步🚶‍♂️"),
        OtherEntity("ExchangeFamilyDecoration", "兑换装饰物品🧱"),
        OtherEntity("shareToFriends", "好友分享🙆‍♂️|下方配置排除列表"),
    )

    @JvmStatic
    fun listPropGroupOptions(): List<OtherEntity> = listOf(
        OtherEntity(AntDodo.PropGroupType.COLLECT_ANIMAL, "当前图鉴抽卡券 🎴"),
        OtherEntity(AntDodo.PropGroupType.ADD_COLLECT_TO_FRIEND_LIMIT, "好友卡抽卡券 👥"),
        OtherEntity(AntDodo.PropGroupType.UNIVERSAL_CARD, "万能卡 🃏")
    )


    // 信用2101任务列表
    @JvmStatic
    fun listCreditTaskOptions(): List<OtherEntity> = listOf(
        OtherEntity(Credit2101.TaskType.AUTO_OPEN_CHEST, "自动开宝箱 🎁"),
        OtherEntity(Credit2101.TaskType.AUTO_SIGN_IN, "自动签到 📅"),
        OtherEntity(Credit2101.TaskType.DAILY_TASKS, "自动完成任务 👷‍♂️"),
        OtherEntity(Credit2101.TaskType.UPGRADE_TALENT, "自动升级天赋 ⚡"),
        OtherEntity(Credit2101.TaskType.CHAPTER_TASKS, "图鉴章节合成 📖")
    )
    //信用2101事件列表
    @JvmStatic
    fun listCreditOptions(): List<OtherEntity> = listOf(
        OtherEntity(Credit2101.EventType.MINI_GAME_ELIMINATE, "消除小游戏 🎮"),
        OtherEntity(Credit2101.EventType.MINI_GAME_COLLECTYJ, "收集小游戏 🏺"),
        OtherEntity(Credit2101.EventType.MINI_GAME_MATCH3, "击杀小游戏 🧩"),
        OtherEntity(Credit2101.EventType.GOLD_MARK, "金色印记 🟡"),
        OtherEntity(Credit2101.EventType.BLACK_MARK, "黑色印记 ⚫"),
        OtherEntity(Credit2101.EventType.SPACE_TIME_GATE, "时空之门 🌀")
    )

}
