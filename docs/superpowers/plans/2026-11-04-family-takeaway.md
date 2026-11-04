# 家庭限时外卖红包与巡护确认失败处理

> 使用 superpowers:executing-plans 在当前工作区实施；不提交、不推送。

## 已确认行为

- 用户明确要求探索确认失败也继续摇骰。确认失败、缺少事件编号、确认请求异常均记录日志，随后重新查询实际次数再继续；不重试确认请求、不伪报动物获取成功。其他巡护错误仍遵循原停止规则。
- 新增抓包 webhook.db 44/45/47：listFamilyTask 返回 FAMILY_SHANGOU 为 TODO 且 extend.canDo=true；doFarmTask 参数为 bizKey=FAMILY_SHANGOU、requestType=NORMAL、sceneCode=ANTFARM、source=H5、taskSceneCode=ANTFARM_FAMILY_TASK；随后列表直接为 RECEIVED，未观察到独立领奖接口。
- 在家庭选项增加“领取外卖红包任务（限时）”，默认不选。开启家庭且选择该项后查询任务，仅处理此任务；已领取、不可执行、过期及缺失任务均跳过。完成后刷新列表确认已领取，不臆造额外领奖调用，不改变其他家庭任务行为。

## 实施步骤

- [x] 修改巡护回归测试，先验证确认失败、异常和缺失编号仍继续摇骰的失败。
- [x] 调整 ForestPatrol 确认处理，刷新最新次数后继续。
- [x] 新增 AntFarmFamilyTakeawayTaskTest，验证家庭请求契约、自动领取复查、已领取/不可执行/过期/错误响应路径；先运行确认新模块缺失。
- [x] 新增 AntFarmFamilyTakeawayTask，接入 AntFarmFamily 和 AntFarm 家庭选项。
- [x] 执行单测与 Debug APK 构建，独立审查并检查 UTF-8 无 BOM 与差异。

## 验证边界

本地验证不代表支付宝真机验证。已知原有 ChouChouLeSchedulePolicyTest 引用缺失类型，标准单测被其阻塞；继续使用 build 下临时构建脚本仅排除此文件，不修改仓库配置。

## 验证结果

- 巡护新增3种确认失败路径断言先失败；实现后18项巡护测试全部通过。
- 家庭限时任务新增5项测试通过；以抓包参数验证完整请求和刷新状态。
- 独立只读审查未发现重要问题。
- 使用既有临时构建脚本排除原有损坏的 ChouChouLeSchedulePolicyTest，其余187项测试全部通过；Debug APK 构建成功，日志 build/family-takeaway-validation.log。
- 标准全量测试的已知缺失类阻塞仍存在，未修改该无关文件；未真机联调，未提交未推送。
