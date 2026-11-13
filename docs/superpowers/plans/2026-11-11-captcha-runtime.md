# 拼图验证进程内处理实施计划

> 历史方案：2026-11-13 已按用户要求移除自动解题、取图与手势实现，仅保留独立验证接口监听、任务暂停和手动通过后的真实回执续跑。当前行为以 [安全验证处理](../../captcha-runtime.md) 为准。下文为原始实施记录，其中“不提交”等限制已由后续提交、推送授权替代。

> 执行方式：使用 superpowers:executing-plans 在当前会话实施，按任务验证，最后独立审核。不提交代码。

**目标：** 将验证发现升级为独立接口监听和页面观察，加入本地拼图识别、进程内触摸和真实通过后的异步恢复。

**架构：** 验证事件进入串行协调器，会话策略负责账号、暂停令牌、挑战版本和请求关联；页面适配器负责可见窗口、取图与坐标；独立驱动执行触摸。抓包关闭仍可工作，不接入 Shizuku/root/无障碍。

**技术：** Kotlin、ModernXposedRuntime、Android WebView/PixelCopy/MotionEvent、协程、JUnit。

**设计：** C:/Users/shenyang/.codex/visualizations/2026/11/11/01a1c82d-c543-7f20-8538-201e97b6819f/拼图滑块自动拖动实现方案.md，用户最新修订取消所有权限辅助输入路线。

## 全局约束

- 中文注释，UTF-8 无 BOM，保留已有未提交修改与横幅，不提交/推送。
- 复用当前 codex/dev 工作区，已有修复是本功能的依赖，不另建缺少这些修复的工作树。
- 自动拖动默认关闭；独立监听常驻；每题一次、每次暂停最多两题；用户触摸立即接管。
- 仅真实 pass 且会话匹配才自动恢复，页面消失和外层 00000 均不足以证明通过。
- 普通日志不含请求全文、令牌、cookie、bizNo 或题图；未知页面降级人工。
- 业务 1009 保留为安全停机兜底，接入同一协调器，独立监听/页面观察成为主发现机制。

## 审核重点

- 响应异步晚到、重复和刷新乱序：旧请求不得解除新挑战暂停。
- 人工快速完成：串行处理发现、请求关联和通过，避免尚未暂停就恢复。
- 用户切换账号或重新生成暂停令牌：退出等待后再次检查，不跨会话恢复。
- 页面销毁、后台、锁屏或用户触摸：停止输入并清理按下状态。
- 缺少可用拼图模板或位移映射：停止自动操作，不以固定比例盲拖。

## 任务 1：可测试的协议和会话策略

文件：新增 hook/captcha/CaptchaProtocol.kt、CaptchaSessionPolicy.kt、CaptchaCallbackStore.kt 及同包测试。

接口：协议解析输出 Request/Challenge/Passed；策略提供 beginChallenge、bindRequest、acceptPass、claimAttempt、isCurrent；callback 存储按对象身份关联并有容量/过期限制。

- [x] 写测试：00000 的 captcha 不算通过；requestData 包装解析；同会话 pass 接受一次；旧挑战/账号/令牌/过期拒绝；刷新乱序；两题限制；异步 callback 保留与过期清理。
- [x] 运行目标单测，确认因实现缺失而失败。
- [x] 实现纯 Kotlin 核心，运行目标单测通过。

## 任务 2：识图与坐标计算

文件：新增 hook/captcha/PuzzleSolver.kt、CaptchaGeometry.kt 及测试。

接口：PuzzleSolver.solve(background, piece) 返回候选位置和置信度；几何函数计算图像到视图坐标和通过一次持续手势中的小位移观测确定移动比例。

- [x] 写测试：透明模板、低对比度、重复候选拒绝、空图/尺寸异常、位移比例不确定拒绝、多分辨率及越界拒绝。
- [x] 观察目标单测失败，实现边缘轮廓匹配与有边界的坐标映射，再运行通过。

## 任务 3：独立 Hook、页面处理和恢复闭环

文件：新增 CaptchaRpcObserver.kt、CaptchaCoordinator.kt、CaptchaPageAdapter.kt、CaptchaGestureDriver.kt 与页面探针资源；修改 ApplicationHook.kt、RequestManager.kt、RpcRecoveryPolicy.kt、BaseModel.kt、新旧 RpcBridge。

接口：CaptchaCoordinator.install(application, loader)、onBusinessVerificationRequired(method)、isAutomating；RequestManager 暴露当前暂停令牌和带有效性校验的异步恢复核心；页面探针返回拼图资源/位置，触摸驱动独占操作窗口。

- [x] 增加集成契约测试，确认独立安装、默认关闭、无权限辅助输入和异步恢复要求当前不满足。
- [x] 实现接口观察，callback 在实际响应时消费，动态加载失败可重试，捕获范围仅验证方法。
- [x] 实现页面观察与取图；可读取当前页面已有 canvas/图片时获取透明模板，截图只在内存；受保护/跨域不可读或图形未知时交人工。
- [x] 实现局部坐标触摸，持续手势中根据移动前后片段实际位移校准比例，再完成拖动。无法观测比例立即取消。
- [x] 异步等待旧任务退出，再校验令牌/账号/会话后恢复，保留其他阻断原因；复核并保留历史“移除旧滑块服务”中仍然有效的断言。
- [x] 运行目标测试和 Debug 编译，修复失败。

## 任务 4：回归、审核与交付

- [x] 运行完整单测和 assembleDebug，记录现有 ChouChouLeSchedulePolicyTest 编译阻断及临时排除方式，不隐瞒该限制。
- [x] 独立审核本次增量，处理关键问题，必要时增加回归测试。
- [x] 检查 diff、UTF-8 无 BOM 和 APK 内容，输出 APK 与使用说明。
- [x] 说明当前无真机联调证据，取图/触摸/通过率待手机验证，不宣称已经自动通过支付宝验证。
