# Sesame-M 有而当前 Sesame-VN 仍缺少的功能

核对日期：2026-11-13。参考本地 Sesame-M `MIUIX-api102 / fbe7e474`，VN 比较起点为 `codex/dev / 0b6b795d`，并计入 0.10.0 版本纳入的六范围补齐代码与 M 限流试用改动。不是远端最新版本对比。

当前仍有 **28 组差异：9 组完整业务缺失、14 组已有业务缺口、5 组工具与配置增强**。下表按模块注册、配置、运行入口和实际 RPC 核对；同名业务和仅有包装方法不视为完整支持。源码可达不代表服务端当前有效，尚无支付宝真机验证。

本地已补齐、从缺失表移除的范围：

| 范围 | 当前已接入能力 |
| --- | --- |
| 金豆夺宝 | 独立模块、双入口签到与任务、矿工、乐园、换豆与限额、商城目录和兑换 |
| 神奇物种 | V2 刮卡、抽卡道具时机、万能卡和赠卡精细筛选 |
| 芭芭农场 | 阿肥寻宝记、悬浮球游戏停留、施肥阶段礼包、乐园限定任务 |
| 小鸡大厨／AI 厨房 | 食材任务、领奖、库存查询及三食材组合制作 |
| 海洋 AI 摸鱼 | 默认鱼开通画鱼、评价点赞能量 |
| 森林寻宝 | 好友及强制助力、任务和抽奖独立控制、分享标识与任务名单 |

这些实现纳入 0.10.0 版本源码；配置入口与执行边界见 [功能说明](D:/project/Sesame-VN/docs/sesame-m-feature-parity.md)。

## 完整业务缺失（9组）

| 功能 | M 已实现的行为 | M 源码入口 | VN 核对边界 |
| --- | --- | --- | --- |
| 爱心鸡结号 S2 | 竞赛捐蛋、等级和任务领奖、周日定时追榜。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antFarm/AntFarm.java:1283) | VN 只有普通公益捐蛋，无 donationCompetition、receiveDonationLevelReward、listCompetitionTask。 |
| 绿植浏览奖励 | 拉取绿植浏览任务、商品流浏览，领取能量、水滴或积分。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antForest/GreenPlantBrowse.java:58) | VN 无 GUANG_TASK、queryPlantTaskList、queryPlantFeedsItemList，森林集市是另一业务。 |
| 绿色租赁逛街产能量 | 检查可用机会，再通过绿色租赁浏览生成能量。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antForest/AntForestV2.java:2192) | VN 无 creditapollon、rent.green、checkUserSecondSceneChance。 |
| 过期能量回收签到 | 查询回收弹层任务并按 currentSignKey 领取。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antForest/AntForestV2.java:2393) | VN 无 popupTask、ANTFOREST_ENERGY_SIGN，普通签到不能替代。 |
| 森林装扮保护 | 保存皮肤方案，发现变化后自动穿回或卸下失效装扮。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antForest/AntForestV2.java:6206) | VN 无 dressDetailList、listUserDressForBackpack、wearDress、takeOffDress。 |
| 网商银行福利金 | 签到及任务报名、完成、领奖；M 当前已移除权益兑换，不把兑换列入。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antMember/WelfareFund.java:69) | VN 无 AP1269301、PLAY100177545、signinPlay；现有 queryWelfareHome 属于黄金票。 |
| 我的快递“福利加” | 推荐任务、普通任务及奖励领取。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antMember/AntMember.java:2427) | VN 无快递任务入口及 alipay.promoprod.task.listQuery、applet.trigger。 |
| 碳中和马拉松／古树医生 | 能量助力及对应捐赠。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/protectEcology/ProtectEcology.java:494) | VN 旧古树保护已有，但没有 doRubickActivity、marathon、protectNewAncientTree。 |
| 普通植树与动物保护证书 | 普通种树、护林员动物保护、子证书齐全后申领金证书。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/protectEcology/ProtectEcology.java:297) | VN Reserve 仅处理 RESERVE 项目，无 applyGoldAnimalCert；同名 exchangeTree RPC 不表示流程等价。 |

## 已有功能中的缺口（14组）

| 功能 | M 多出的能力 | M 源码入口 | VN 已有能力及边界 |
| --- | --- | --- | --- |
| 新版保护地巡护 | 自动兑换证书、新版动物取能量／派遣、新旧动物派遣优先级。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antForest/AntForestV2.java:4791) | VN ForestPatrol.kt:18 已有新版摇骰、浏览任务、事件确认和动物满级换地区，旧版动物派遣也已有。 |
| 森林／庄园乐园限定任务 | 通用 TODO 完成、多阶段推进和额外领奖。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antForest/AntForestV2.java:3631) | 农场限定任务已在本地补齐。森林仍为普通游戏宝箱；庄园限时活动仍仅处理固定签到和累计开箱两种领奖任务，缺通用 TODO、多阶段推进和额外领奖。M 庄园入口 AntFarm.java:2891。 |
| 森林组队与挑战策略 | 整轮优先组队收能量、1V1 自动开启参赛开关。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antForest/AntForestV2.java:490) | VN 森林兼容组队主页，合种模块能切组队并切回；1V1 已自动领奖，缺森林整轮策略和自动开参赛。M 自动开参赛入口 AntForestV2.java:6083。 |
| 浇水／复活细分控制 | 复活能量最低值、浇水优先、强制复查当天浇水、应被好友浇水核对。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antForest/AntForestV2.java:357) | VN 已有浇水、返水、通知好友和复活，缺这些控制及预期／实际被浇次数核对。M util/Status.java:218 实际读取统计配置。 |
| 道具续用限制 | 各类连续兑换／使用的每日次数、保护罩剩余小时续用阈值。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antForest/AntForestV2.java:370) | VN 已有道具兑换、使用和保护罩续用，缺这些可配置限制；不是完全缺少续罩。 |
| 小鸡换装 | 只从指定套装中选、按配置天数换装。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antFarm/AntFarm.java:4183) | VN AntFarm.kt:4364 已有每日随机换装，无套装白名单与可配天数。 |
| 小鸡吃美食 | 自动使用的每日总量上限、赛季 cuisineType/resultLevel 参数。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antFarm/AntFarm.java:2650) | VN 已有自动和手动吃美食；无自动任务可配每日总量，RPC 仅传 cookbookId/cuisineId。赛季参数属兼容能力差异，未真机验证具体菜品影响。 |
| NPC 雇佣 | 多选 NPC、按当天未处理项轮换。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antFarm/AntFarm.java:3744) | VN AntFarm.kt:4016 已支持大表鸽、黄金鸡、农场小鸡，但为单选维持。 |
| 小鸡乐园商城 | 按 SKU 指定每日兑换次数。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antFarm/AntFarm.java:3140) | VN AntFarm.kt:976 已有 SPU 选择和兑换到服务端限制，无用户指定 SKU 每日次数。 |
| 庄园抽抽乐黑名单 | 独立抽抽乐任务黑名单及自动黑名单开关。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antFarm/AntFarm.java:3433) | VN ChouChouLe.kt:212 无该配置读取，庄园普通饲料任务的通用黑名单已存在。 |
| 黄金票 | 首页／福利浏览任务执行及任务奖励领取。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antMember/AntMember.java:606) | VN AntMember.kt:1654 已有签到、提取；任务包装虽存在但没有执行入口。 |
| 保护地／海滩兑换策略 | 自动遍历可兑换项目，按最低已有证书数补齐。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/protectEcology/ProtectEcology.java:601) | VN Reserve.java:49 与 AntOcean.java:121 已支持指定项目及数量；缺自动补最低数量策略。 |
| 运动路线与同步策略 | 跨全部主题优先选择完成次数最少路线、路线步频及最早同步时间设置。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antSports/AntSports.java:113) | VN AntSports.kt:938 已有路线选择、自定义路线和同步步数，缺这些策略选项。 |
| 健康岛奖励 | 离线奖励、活动岛完成奖励、策略球特殊奖励。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/task/antSports/AntSports.java:2131) | VN AntSports.kt:1925 已有签到、任务、气泡、自动走路建造、切岛和关卡奖励；部分奖励 RPC 未实现或未接入执行。 |

## 工具与配置增强（5组）

| 功能 | 对比 | M 源码入口 |
| --- | --- | --- |
| 自定义 RPC 每日自动执行 | 填写请求及每日次数，在整轮调度前执行；VN 当前是手动 RPC 调试和固定手动任务，缺同类每日计划列表。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/model/normal/base/BaseModel.java:170) |
| 自动配置备份与损坏恢复 | 修改前快照、每日滚动备份、保留数量配置，解析失败优先从备份恢复；VN 有手动导入导出，但没有这套自动备份恢复流程。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/data/ConfigV2.java:195) |
| 好友统计独立页面 | 周收／总收排序、搜索和点击跳转；VN FriendWatch 已记录数据，界面主要为单向好友列表，缺此独立统计页。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/ui/miuix/MiuixFriendStatsActivity.kt:92) |
| 账号列表管理增强 | 左滑删除配置、状态、备份及旧配置，并阻止宿主运行中删除；VN 已有删除配置，缺这套列表交互和运行检测。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/ui/miuix/MiuixMainActivity.kt:905) |
| 设置项依赖显示 | 父开关未启用时隐藏依赖子项，沿依赖链判断；VN 无对应通用 dependsOn/isVisible 机制。 | [源码](D:/project/Sesame-M/app/src/main/java/io/github/aw1y2z/sesame/ui/miuix/MiuixGroupFieldsActivity.kt:193) |


## 不列作缺失的项目

- VN 已有无纸阅读、旧版古树保护、阅读答答、家庭相关任务、庄园宝箱、合种、五连施肥突破、能量雨救援队、普通会员广告任务及游戏中心、绿色经营、新村核心业务。
- M 营地选肥料仅定义方法、未接入运行，不列作可用功能。
- 自动验证码处理已按用户要求移除，不列入补齐建议。
- Miuix 与 Material/Web 界面、Java/Kotlin、线程/协程等实现差异不计作业务缺失。

原始核对报告保留在 `C:/Users/shenyang/Documents/Codex/reports/2026-11-13-Sesame-M功能差异清单.md`；其中六范围的旧缺失描述已被本页取代。
