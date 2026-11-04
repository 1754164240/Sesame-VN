# 新版保护地巡护实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 自动兑换步数、完成任务和摇骰子，全部动物满级后切换保护地。

**Architecture:** 新增可注入 RPC、等待与日志的 ForestPatrol，由 AntForest 原开关调用。按真实抓包构造请求，所有状态由服务端复查。

**Tech Stack:** Kotlin、org.json、JUnit 4、Gradle。

**Spec:** docs/superpowers/specs/2026-11-04-monopoly-patrol-design.md

## Global Constraints

- UTF-8 无 BOM，代码注释中文。
- 不提交或推送，保留无关文件。
- 仅以各动物 level 和 maxLevel 判断满级。
- 不臆造抓包中没有的地图升级接口。

## Review Focus

- 一种动物未满级、等级缺失或列表为空时不得提前切换。
- 多地区全部满级时不得往返切换。
- 领奖假成功不能引发无限兑换，必须查实际次数。
- 事件确认失败不得继续摇骰子。
- 不支持的任务及异常响应不能导致资源消耗或死循环。

### Task 1: 新版巡护执行与测试

**Files:** 新建 ForestPatrol.kt 和 ForestPatrolTest.kt，位于现有 antForest 主源码及测试目录。

**Interfaces:** ForestPatrol(request: (String, JSONObject) -> JSONObject, pause: (Long) -> Unit, log: (String) -> Unit).run(): Unit。

- [x] 写回归测试覆盖任务兑换、事件确认、耗尽次数、动物满级切换、空数据、失败和循环保护。
- [x] 运行聚焦测试，确认新功能缺失的失败。
- [x] 实现抓包参数、任务处理、骰子循环和保护地完成检查。
- [x] 聚焦测试通过。

### Task 2: 接入森林并验证

**Files:** 修改 AntForest.kt 的 queryUserPatrol 入口。

- [x] 原开关接入 ForestPatrol，沿用 requestString、线程等待和森林日志。
- [x] 执行 :app:testDebugUnitTest 和 :app:assembleDebug；记录环境限制。
- [x] 检查差异、UTF-8 无 BOM、空白和请求契约，不创建提交。

## 实施记录

- 已在当前 codex/dev 分支复用干净工作区实施，未提交、未推送。
- 新增16个巡护用例；审查新增的4项失败断言先复现失败，再修正为通过。
- 标准全量命令 :app:testDebugUnitTest 被原有 ChouChouLeSchedulePolicyTest 引用不存在的 ChouChouLeScheduleAction / ChouChouLeSchedulePolicy 阻塞，未修改无关测试。
- 临时构建脚本 build/monopoly-test.init.gradle 仅排除上述已损坏测试，命令：.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug -I build/monopoly-test.init.gradle --no-configuration-cache --console=plain。
- 其余180个测试通过（含巡护16个），Debug APK 构建成功。构建日志保存在 build/monopoly-filtered-validation.log。
- 独立只读审查发现的“满级当前地图继续消耗次数”和“任务失败继续摇骰”均已修复并复核；浏览任务先生成流水再等待，与抓包时序一致。
- 未做支付宝真机联调。当前地图满级但同一保护地其他地图未完成时记录日志并保留骰子，需服务端自动进入下一地图或补充真实切图接口后继续；不臆造请求。

后续用户变更：确认探索失败也继续摇骰；实现和验证记录见 2026-11-04-family-takeaway.md。
