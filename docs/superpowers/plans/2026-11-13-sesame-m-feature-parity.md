# Sesame-M 选定功能补齐实施计划

**目标：** 根据用户选定范围，补齐金豆夺宝、神奇物种、芭芭农场、小鸡大厨／AI 厨房，以及海洋 AI 摸鱼和森林寻宝的缺失能力。

**参考：** 本地 `D:/project/Sesame-M`（MIUIX-api102），以及已核对的 `2026-11-13-Sesame-M功能差异清单.md`。只迁移用户选定范围，不包含爱心鸡结号、会员福利金、生态保护或其他未选功能。

**技术与约束：** 适配 VN 的 ModelTask、ModelFields、RequestManager、Status、UserMap 与现有协程取消机制。新业务默认关闭、列表默认空；不引入自动验证码、Shizuku 或额外云模型。UTF-8 无 BOM，中文注释。保留现有 M 限流试用改动。此次没有新增提交／推送要求，不自动提交。

## 验收范围

1. 金豆夺宝完整模块：两个入口签到、任务及营销奖励、任务名单、金猫矿工、乐园抽奖及游戏权益、肥料／芝麻粒换豆和每日上限、商城清单同步及按日次数兑换、领奖后同步；模块注册、设置入口及动态列表可用。
2. 神奇物种：物种 V2 可刮卡；抽卡道具每日／最后一天使用；万能卡按图鉴、收集、勋章、等级筛选和未曾获得优先；赠卡同类筛选，保留既有帮抽及自动合成能力。
3. 芭芭农场：阿肥寻宝记／抽抽乐及黑名单；首页悬浮球游戏时长任务；施肥阶段丰收礼包；农场乐园限定任务与多阶段奖励。保留常规任务、砸蛋、摇钱树和五连突破。
4. 小鸡大厨：独立 AI 厨房开关、食材任务／领奖、自动黑名单及列表、查询分数和库存、自动组合食材做美食，尊重库存和服务端限制；旧厨房不被替换。
5. 海洋 AI 摸鱼：默认鱼时开通画鱼、查询待评价鱼并点赞获得能量；保留解救、已有任务和摸鱼。
6. 森林寻宝：独立做任务／抽奖／助力控制，普通与活动场强制助力、shareId 列表、独立任务名单，关联当前活动并验证助力回执；保留既有两场活动和任务失败控制。

## 实施与验证

- [x] 先建立各模块的协议／策略回归用例，确认缺失行为导致失败。
- [x] 金豆夺宝与注册、配置适配完成。
- [x] 神奇物种缺失及细分能力完成。
- [x] 芭芭农场缺失及限定任务完成。
- [x] AI 厨房完成并接入庄园。
- [x] 海洋 AI 摸鱼增强完成。
- [x] 森林寻宝增强完成。
- [x] 检查默认设置、账号隔离、取消／离线／验证暂停、次数与库存边界、失败不标完成、禁止循环无进展。
- [x] 更新仓库及应用内 CHANGELOG；本地完整可运行单测、Debug APK、签名、编码及差异检查。
- [x] 按六项范围逐项复核代码可达性和回归证据，不用单一测试通过代替完整范围验收。

## 验证限制

原有 `ChouChouLeSchedulePolicyTest.kt` 引用缺失类型，必要时沿用临时 init script 排除并明确报告，不能修改构建配置掩盖。没有真机时不宣称支付宝服务端接受或能量实际到账；测试使用离线构造响应和可注入协议入口，不向真实账号执行操作。

## 实施记录

2026-11-13：核对当前工作区，已有四个限流试用修改保持原样；用户设置完整目标并要求继续实施。按模块划分文件所有权，避免并行覆盖同一文件。

2026-11-13：六范围实现完成，经各模块复核、修复复核及最终集成复核。金豆已进入 ModelOrder 与独立 ModelGroup；其他业务均从原模块的新增开关进入。动态任务目录、商品目录、图标和账号配置提供器已接入。原限流修改及放行后记录时间的修复保留；未提交、推送或发布。

## 最终范围审计

| 范围 | 代码与行为证据 | 离线回归 |
| --- | --- | --- |
| 金豆夺宝 | GoldenBeans → Runner → 双入口任务、矿工、游戏、换豆、商城；Journal 按账号和日期隔离，发前落动作身份，确认与额度原子保存；未知动作跨轮只回查 | GoldenBeansPolicyTest 8 项；GoldenBeansExecutionTest 15 项，覆盖真实 Tasks、Mall、Exchange、营销和游戏链 |
| 神奇物种 | AntDodo 开关进入 V2 待刮／即时刮卡、道具时机、万能卡和赠卡筛选；本轮去重并逐次回查库存 | DodoCardPolicyTest 6 项 |
| 芭芭农场 | AntOrchard → OrchardActivityWorkflow 与 GameStay；抽奖动态场景、悬浮球双来源、施肥后刷新领取、乐园阶段回查均可达 | OrchardActivityWorkflowTest 12 项；AntOrchardActivityConfigTest 1 项 |
| AI 厨房 | AntFarm → AiKitchenTask → Runner；新旧厨房独立，领奖参数、三种食材组合、饲料与服务端停止状态生效 | AiKitchenRunnerTest 8 项 |
| 海洋 AI 摸鱼 | AntOcean 两开关独立调用 AiFishEnhancement；开通使用状态接口根 fishStatus 并回查，空评价用户回退 fish.userId | AiFishEnhancementTest 8 项；原 AntOceanAiFishTest 现 12 项 |
| 森林寻宝 | AntForest → ForestChouChouLe → ForestHuntAssist；任务、抽奖、助力分开，prodPlayParam 参数解析、当前活动绑定、黑名单限制强制助力 | ForestHuntAssistTest 4 项 |
| 公共与配置 | ModelTask 捕获当前 Job 身份；新增顶层业务默认关闭，森林任务／抽奖子项保留旧行为，名单默认空 | ExecutionGuardTest 2 项；SesameMParityConfigTest 4 项 |

复核发现的协议及恢复问题均已修复并复核：农场累计多次才推进的阶段；森林助力参数层级；海洋根状态与空串回退；厨房领奖失败和 canCook=false；金豆跨日与持久化、H5 来源、受理不代表完成、商城订单关联及游戏跨轮防重。最终新增的游戏回归先在旧实现失败（15 项中 1 项失败），再在稳定动作键和按同一游戏权益确认的实现上通过。没有将无关全局抽奖次数的变化视作该游戏进度。

## 最终验证证据

JDK 21.0.11，执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --init-script "$env:TEMP\sesame-log-investigation.gradle" --no-configuration-cache --console=plain
```

- 最终日志：`%TEMP%/sesame-parity-final-verified-build.log`，退出码 0，`BUILD SUCCESSFUL`。
- XML 汇总：292 项、0 失败、0 错误、0 跳过。原有 `ChouChouLeSchedulePolicyTest.kt` 因缺失类型，通过临时 init script 从测试编译中排除；因此这不是未作排除的全部原始测试通过，项目构建配置未为此修改。
- Debug APK 构建成功，apksigner 验证通过（v2、1 个签名者）。
- 试用包：`app/build/outputs/trial/Sesame-VN-M-features-trial.apk`，41,109,394 字节；与构建原包 SHA-256 一致：`E20BA47F338E3065DFD0204E63F3F32F38C9C8CAC43050D0B6B72BA1A6B6CFF7`。
- 改动文本均检查为 UTF-8 无 BOM；`git diff --check` 通过，新增源码注释未发现纯英文说明，凭据模式扫描无命中。
- ADB 设备列表为空，没有执行真实账号操作，未验证服务端接受、活动资格及奖励实际到账。

## 协议限制与交付

换豆响应丢失且没有可关联到账回执时，余额快照不能证明是本动作所得；本账号当日保留待确认并只查不重换。商城查询缺少能关联当前动作及商品的字段时同样保留未知状态，不将其他订单误记为成功。日账本按日期隔离。这些边界不会虚记完成或额度，但可能使当日该项后续动作暂停，需结合真实回执排查。

功能入口及默认配置见 `docs/sesame-m-feature-parity.md`；当前尚缺的 28 组差异见 `docs/sesame-m-remaining-features.md`。本轮代码与文档保留在 `codex/dev` 工作区，未提交或推送。

## 后续版本交付

2026-11-16：用户授权升级版本并提交推送此前改动。应用版本由 0.9.9 升级为 0.10.0，versionCode 继续由 Git 提交数生成；同步更新仓库及应用内变更记录。上文 APK、哈希和未提交说明均为 2026-11-13 实施完成时的历史快照。
