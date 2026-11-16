package fansirsqi.xposed.sesame.task.goldenbeans;

import org.json.JSONArray;
import org.json.JSONObject;
import fansirsqi.xposed.sesame.model.modelFieldExt.SelectModelField;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.data.Config;
import fansirsqi.xposed.sesame.util.maps.GoldenBeansTaskListMap;
import fansirsqi.xposed.sesame.util.maps.IdMapManager;



/**
 * 金豆夺宝的入口日常与任务列表处理。
 * <p>
 * 任务列表只处理 sceneCode 与本入口一致的任务；需真实付款、换豆承接类任务直接跳过；
 * 其余 TODO 任务走服务端完成契约，失败且命中不可重试错误时自动加入黑名单。
 */
public final class GoldenBeansTasks {

    /** 任务状态：已完成待领取 */
    private static final String STATUS_FINISHED = "FINISHED";
    /** 任务状态：已完成待领取（服务端另一套命名） */
    private static final String STATUS_TO_RECEIVE = "TO_RECEIVE";
    /** 任务状态：已领取 */
    private static final String STATUS_RECEIVED = "RECEIVED";
    /** 任务状态：已结束 */
    private static final String STATUS_DONE = "DONE";
    /** 任务状态：待完成 */
    private static final String STATUS_TODO = "TODO";

    private final SelectModelField blacklist;
    private final boolean autoBlacklist;

    /**
     * @param blacklist     黑名单配置字段，可为 null
     * @param autoBlacklist 是否自动维护默认黑白名单
     */
    public GoldenBeansTasks(SelectModelField blacklist, boolean autoBlacklist) {
        this.blacklist = blacklist;
        this.autoBlacklist = autoBlacklist;
    }

    /** 本次签到是否失败（失败时本入口计为未完成） */
    private boolean signFailed;

    /**
     * 处理单个入口。
     *
     * @return 该入口是否已无待推进任务
     */
    public boolean processEntry(GoldenBeansEntry entry, int interval,
                                boolean signEnabled, boolean popupEnabled, boolean taskEnabled) {
        try {
            JSONObject indexJo = GoldenBeansSupport.parse(
                    goldenbeansRpcCall.homeOf(entry.bizType, entry.source));
            if (!GoldenBeansSupport.ok(indexJo)) {
                GoldenBeansRuntime.record("金豆[" + entry.alias + "]主页⚠️查询失败["
                        + GoldenBeansSupport.describe(indexJo) + "]");
                return false;
            }

            // 签到/弹窗属于该入口的"待推进项"：失败时本入口即为未完成，否则当天不会再重试
            boolean resolved = true;

            if (signEnabled) {
                JSONObject signedSync = doSign(indexJo, entry, interval);
                if (signedSync != null) {
                    indexJo = signedSync;
                }
                if (signFailed) {
                    resolved = false;
                }
            }

            if (popupEnabled && !clickPopup(indexJo, entry, interval)) {
                resolved = false;
            }

            if (taskEnabled) {
                return runTaskList(entry, interval) && resolved;
            }
            return resolved;
        } catch (Throwable th) {
            GoldenBeansRuntime.rethrowIfStopped(th);
            GoldenBeansRuntime.record(GoldenBeansSupport.TAG, "processEntry err:");
            GoldenBeansRuntime.error(GoldenBeansSupport.TAG, th);
            return false;
        }
    }

    /**
     * 每日签到：从主页 signInfo 中找出今日未签到的 signKey 并提交。
     *
     * @return 签到后的同步响应，供后续弹窗与任务使用
     */
    /** 金豆签到当日完成标记：按入口区分（农场 / 炼金各自的签到列表互不相通） */
    private static String signFlag(GoldenBeansEntry entry) {
        return "goldenBeans::sign::" + entry.bizType;
    }

    private JSONObject doSign(JSONObject indexJo, GoldenBeansEntry entry, int interval) {
        signFailed = false;
        if (GoldenBeansStatus.hasFlagToday(signFlag(entry))) {
            return null;
        }
        try {
            if (GoldenBeansSupport.todaySigned(indexJo)) {
                GoldenBeansStatus.flagToday(signFlag(entry));
                return null;
            }
            JSONObject signInfo = indexJo.optJSONObject("signInfo");
            if (signInfo == null) {
                signFailed = true;
                GoldenBeansRuntime.record("金豆[" + entry.alias + "]签到⚠️未返回签到信息");
                return null;
            }
            JSONArray signList = signInfo.optJSONArray("signList");
            if (signList == null) {
                signFailed = true;
                GoldenBeansRuntime.record("金豆[" + entry.alias + "]签到⚠️签到列表为空");
                return null;
            }
            for (int i = 0; i < signList.length(); i++) {
                JSONObject sign = signList.optJSONObject(i);
                if (sign == null || !sign.optBoolean("today", false) || sign.optBoolean("signed", false)) {
                    continue;
                }
                String signKey = sign.optString("signKey", "").trim();
                if (signKey.isEmpty()) {
                    signFailed = true;
                    GoldenBeansRuntime.record("金豆[" + entry.alias + "]签到⚠️缺少服务端密钥");
                    return null;
                }
                JSONObject signResponse = GoldenBeansSupport.parse(
                        goldenbeansRpcCall.checkInOf(entry.bizType, entry.source, signKey));
                if (!GoldenBeansSupport.ok(signResponse)) {
                    signFailed = true;
                    GoldenBeansRuntime.record("金豆[" + entry.alias + "]签到⚠️失败["
                            + GoldenBeansSupport.describe(signResponse) + "]");
                    return null;
                }
                GoldenBeansSupport.pause(interval);
                JSONObject syncResponse = GoldenBeansSupport.parse(
                        goldenbeansRpcCall.pullOf(entry.bizType, entry.source,
                                "JAR_INFO", "SIGN", "MARKETING_POPUP", "TASK_LIST"));
                if (GoldenBeansSupport.todaySigned(syncResponse)) {
                    int awardCount = GoldenBeansSupport.awardCount(signResponse);
                    if (awardCount <= 0) {
                        awardCount = sign.optInt("awardCount", 0);
                    }
                    int continuousDays = sign.optInt("currentContinuousCount",
                            sign.optInt("continuousCount", 0));
                    String dayInfo = continuousDays > 0 ? "[第" + continuousDays + "天]" : "";
                    String awardText = awardCount > 0 ? "#获得[" + awardCount + "豆]" : "";
                    // 服务端回读已确认今日签到：落当日标记，后续运行不再查询/提交该入口签到
                    GoldenBeansStatus.flagToday(signFlag(entry));
                    GoldenBeansRuntime.record("金豆[" + entry.alias + "]签到📅" + dayInfo + awardText);
                } else {
                    // 已提交签到但服务端未确认：视为未完成，下轮重试（重试只会得到"已签到"，无副作用）
                    signFailed = true;
                    GoldenBeansRuntime.record("金豆[" + entry.alias + "]签到⚠️未通过服务端状态确认");
                }
                return syncResponse;
            }
            for (int i = 0; i < signList.length(); i++) {
                JSONObject sign = signList.optJSONObject(i);
                if (sign != null && sign.optBoolean("today", false) && sign.optBoolean("signed", false)) {
                    // 本轮主页 signInfo 里今日项已 signed=true：服务端回读确认，落当日标记
                    GoldenBeansStatus.flagToday(signFlag(entry));
                    break;
                }
            }
            GoldenBeansRuntime.record("金豆[" + entry.alias + "]签到📅今日已签到");
        } catch (Throwable th) {
            GoldenBeansRuntime.rethrowIfStopped(th);
            signFailed = true;
            GoldenBeansRuntime.record(GoldenBeansSupport.TAG, "doSign err:");
            GoldenBeansRuntime.error(GoldenBeansSupport.TAG, th);
        }
        return null;
    }

    /** 营销弹窗触发（浏览类动作）
     *
     * @return 触发是否成功；失败时该入口不计入"已完成"，下轮还会重试
     */
    private boolean clickPopup(JSONObject indexJo, GoldenBeansEntry entry, int interval) {
        try {
            JSONObject marketingTask = indexJo.optJSONObject("marketingPopupTask");
            if (marketingTask == null) {
                return true;
            }
            String taskId = marketingTask.optString("taskId", "").trim();
            String triggerType = marketingTask.optString("triggerType", "").trim();
            if (triggerType.isEmpty()) {
                triggerType = goldenbeansRpcCall.TRIGGER_MARKETING_POPUP;
            }
            if (taskId.isEmpty()) {
                GoldenBeansRuntime.record("金豆[" + entry.alias + "]弹窗⚠️缺少服务端任务标识");
                return true;
            }
            String popupKey = "popup::" + entry.bizType + "::" + taskId;
            final String trigger = triggerType;
            return new GoldenBeansActions().execute(popupKey, "", new JSONObject(), action -> {
                JSONObject sync = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(entry.bizType, entry.source, "MARKETING_POPUP"));
                if (!GoldenBeansSupport.ok(sync)) return GoldenBeansActions.unknown();
                JSONObject popup = sync.optJSONObject("marketingPopupTask");
                boolean absent = !sync.has("marketingPopupTask") || popup == null;
                return absent || !taskId.equals(popup.optString("taskId", "")) || popup.optBoolean("finished", false)
                        ? GoldenBeansActions.confirmed(0) : GoldenBeansActions.unknown();
            }, action -> {
                JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.fireOf(entry.bizType, entry.source, taskId, trigger));
                return GoldenBeansActions.explicitRejection(response) ? GoldenBeansActions.rejected() : GoldenBeansActions.unknown();
            }) == GoldenBeansActions.State.CONFIRMED;
        } catch (Throwable th) {
            GoldenBeansRuntime.rethrowIfStopped(th);
            GoldenBeansRuntime.record(GoldenBeansSupport.TAG, "clickPopup err:");
            GoldenBeansRuntime.error(GoldenBeansSupport.TAG, th);
            return false;
        }
    }

    /**
     * 任务列表处理：领取已完成任务奖励、完成服务端允许直接完成的任务。
     *
     * @return 该入口是否已无待推进任务
     */
    private boolean runTaskList(GoldenBeansEntry entry, int interval) {
        boolean unresolved = false;
        try {
            JSONObject syncJo = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(
                    entry.bizType, entry.source, "FARM_TASK", "TASK_LIST"));
            if (!GoldenBeansSupport.ok(syncJo)) {
                GoldenBeansRuntime.record("金豆[" + entry.alias + "]任务⚠️列表查询失败["
                        + GoldenBeansSupport.describe(syncJo) + "]");
                return false;
            }
            JSONArray taskList = syncJo.optJSONArray("taskList");
            if (taskList == null) {
                GoldenBeansRuntime.record("金豆[" + entry.alias + "]任务⚠️未返回任务列表");
                return false;
            }

            int total = 0;
            int handled = 0;
            boolean changed = false;
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                // 两个入口的任务场景不同，只处理属于当前入口的任务
                if (!entry.taskSceneCode.equals(task.optString("sceneCode", "").trim())) {
                    continue;
                }
                total++;
                String taskId = task.optString("taskId", "").trim();
                String taskStatus = task.optString("taskStatus", "").trim().toUpperCase();
                String actionType = task.optString("actionType", "").trim();
                String blacklistKey = blacklistKey(task);
                String taskName = displayName(task);

                // 黑名单任务跳过；已完成待领取的仍执行领奖，兼容以标题为键的旧黑名单项
                if (isBlacklisted(blacklistKey, taskName)) {
                    if (STATUS_FINISHED.equals(taskStatus) || STATUS_TO_RECEIVE.equals(taskStatus)) {
                        GoldenBeansSupport.pause(interval);
                        // 领奖失败要计入未完成，下轮还会重试（与下面非黑名单分支保持一致）
                        if (claimAward(entry, taskId, taskName)) {
                            changed = true;
                        } else {
                            unresolved = true;
                        }
                    }
                    // 黑名单跳过静默：条目由用户在配置页维护，内容恒定，不打日志
                    continue;
                }

                if (GoldenBeansPolicy.rewardConfirmed(taskStatus)) {
                    handled++;
                    continue;
                }

                if (STATUS_FINISHED.equals(taskStatus) || STATUS_TO_RECEIVE.equals(taskStatus)) {
                    GoldenBeansSupport.pause(interval);
                    if (claimAward(entry, taskId, taskName)) {
                        handled++;
                        changed = true;
                    } else {
                        unresolved = true;
                    }
                    continue;
                }

                if (STATUS_TODO.equals(taskStatus)) {
                    // 支付及交易类不自动申报，仅在开启自动黑名单时登记。
                    if (isPayTask(taskId) || isTransactionTask(taskId)) {
                        markBlacklist(taskId);
                        GoldenBeansRuntime.record("金豆[" + entry.alias + "]任务⏭️[" + taskName
                                + "]交易/支付类#跳过自动申报");
                        continue;
                    }
                    if (goldenbeansRpcCall.TASK_TYPE_EXCHANGE.equals(taskId)) {
                        GoldenBeansRuntime.record(GoldenBeansSupport.TAG, "金豆[" + entry.alias + "]任务⏭️[" + taskName + "]"
                                + (entry == GoldenBeansEntry.ALCHEMY ? "芝麻粒换豆处理" : "肥料换豆处理"));
                        continue;
                    }
                    GoldenBeansSupport.pause(interval);
                    // 多次任务（rightsTimesLimit>1）一次补齐：逐档取不同的策略键，否则第一档落下
                    // 当日标记后后续档位全被挡下、任务停在第一档
                    int rightsTimes = task.optInt("rightsTimes", 0);
                    int rightsTimesLimit = task.optInt("rightsTimesLimit", 0);
                    int remain = Math.min(rightsTimesLimit > rightsTimes ? rightsTimesLimit - rightsTimes : 1, 64);
                    boolean anyDone = false;
                    for (int cnt = 0; cnt < remain; cnt++) {
                        // 每一档都确认明确终态或权益次数增加，受理但未确认时立即停止续发。
                        if (!finishTask(entry, taskId, taskName, rightsTimes + cnt, cnt == remain - 1)) {
                            break; // 做不了就收手，不再空转剩余次数
                        }
                        anyDone = true;
                        GoldenBeansSupport.pause(interval);
                    }
                    if (anyDone) {
                        handled++;
                        changed = true;
                    } else {
                        GoldenBeansRuntime.record("金豆[" + entry.alias + "]任务⚠️[" + taskName + "]完成失败["
                                + (actionType.isEmpty() ? "UNKNOWN" : actionType) + "]");
                        unresolved = true;
                    }
                    continue;
                }

                GoldenBeansRuntime.record("金豆[" + entry.alias + "]任务⚠️[" + taskName + "]未知状态[" + taskStatus + "]");
                unresolved = true;
            }

            // 再拉列表领取本轮新完成任务的奖励。
            int claimed = claimPendingRewards(entry, interval);
            if (claimed > 0) changed = true;
            if (claimed < 0) unresolved = true;

            // 仅在**本轮有新动作**时打统计并重拉列表：total/handled 是列表快照（已领数跨轮恒定），
            // 每轮照打等于重复播报同一组数字；本轮无动作时静默
            if (changed) {
                GoldenBeansRuntime.record("金豆[" + entry.alias + "]任务🗂️共[" + total + "个]#完成[" + handled + "个]");
                GoldenBeansSupport.pause(interval);
                goldenbeansRpcCall.pullOf(entry.bizType, entry.source, "FARM_TASK", "TASK_LIST");
            }
        } catch (Throwable th) {
            GoldenBeansRuntime.rethrowIfStopped(th);
            GoldenBeansRuntime.record(GoldenBeansSupport.TAG, "runTaskList err:");
            GoldenBeansRuntime.error(GoldenBeansSupport.TAG, th);
            return false;
        }
        return !unresolved;
    }

    private boolean isBlacklisted(String blacklistKey, String taskName) {
        if (blacklist == null || blacklist.getValue() == null) {
            return false;
        }
        return blacklist.getValue().contains(blacklistKey) || blacklist.getValue().contains(taskName);
    }

    /**
     * 黑名单键：使用服务端稳定标识 taskId（缺失回退 groupId），
     * 避免展示标题变化导致黑名单匹配失效。
     */
    private String blacklistKey(JSONObject task) {
        String taskId = task.optString("taskId", "").trim();
        if (!taskId.isEmpty()) {
            return taskId;
        }
        String groupId = task.optString("groupId", "").trim();
        if (!groupId.isEmpty()) {
            return groupId;
        }
        return displayName(task);
    }

    /** 任务展示名：优先服务端标题（含 taskDisplayConfig.title） */
    private String displayName(JSONObject task) {
        String title = task.optString("title", "").trim();
        if (title.isEmpty()) {
            JSONObject displayConfig = task.optJSONObject("taskDisplayConfig");
            if (displayConfig != null) {
                title = displayConfig.optString("title", "").trim();
            }
        }
        if (title.isEmpty()) {
            title = task.optString("taskId", "").trim();
        }
        return title;
    }

    /** 支付类任务需要用户真实付款，无法自动完成 */
    private boolean isPayTask(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return false;
        }
        String upper = taskId.toUpperCase();
        return upper.contains("ZHIFU") || upper.contains("_PAY");
    }

    /** 提交后必须复核服务端任务状态，成功响应本身不代表完成。 */
    private boolean finishTask(GoldenBeansEntry entry, String taskId, String taskName, int stage, boolean lastStage) {
        if (taskId.isEmpty()) return false;
        String key = "finish::" + entry.bizType + "::" + taskId + "::" + stage;
        try {
            JSONObject existing = findTask(entry, taskId);
            if (terminal(existing)) return true;
            return new GoldenBeansActions().execute(key, "", new JSONObject().put("stage", stage), action -> {
                JSONObject task = findTask(entry, taskId);
                return terminal(task) || (task != null && task.optInt("rightsTimes", 0) > action.optInt("stage"))
                        ? GoldenBeansActions.confirmed(0) : GoldenBeansActions.unknown();
            }, action -> {
                JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.submitTaskOf(entry.bizType, entry.source, entry.taskSceneCode, taskId, action.getString("actionId")));
                if (response != null && "400000040".equals(response.optString("resultCode"))) {
                    // 主接口明确拒绝后才调用参考协议的 H5 入口，结果仍以列表为准。
                    JSONObject fallback = GoldenBeansSupport.parse(goldenbeansRpcCall.doFarmTask(taskId, entry.taskSceneCode));
                    return GoldenBeansActions.explicitRejection(fallback) ? GoldenBeansActions.rejected() : GoldenBeansActions.unknown();
                }
                if (GoldenBeansActions.explicitRejection(response)) {
                    if (!retryable(response)) markBlacklist(taskId);
                    return GoldenBeansActions.rejected();
                }
                return GoldenBeansActions.unknown();
            }) == GoldenBeansActions.State.CONFIRMED;
        } catch (Exception error) {
            GoldenBeansRuntime.rethrowIfStopped(error);
            return false;
        }
    }

    private static boolean terminal(JSONObject task) {
        if (task == null) return false;
        String state = task.optString("taskStatus", "");
        return "FINISHED".equals(state) || "TO_RECEIVE".equals(state) || GoldenBeansPolicy.rewardConfirmed(state);
    }

    private JSONObject findTask(GoldenBeansEntry entry, String taskId) throws Exception {
        JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(entry.bizType, entry.source, "FARM_TASK", "TASK_LIST"));
        if (!GoldenBeansSupport.ok(response)) return null;
        JSONArray tasks = response.optJSONArray("taskList");
        if (tasks == null) return null;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task != null && taskId.equals(task.optString("taskId")) && entry.taskSceneCode.equals(task.optString("sceneCode"))) return task;
        }
        return null;
    }

    private boolean claimAward(GoldenBeansEntry entry, String taskId, String taskName) {
        if (taskId.isEmpty()) return false;
        String key = "reward::" + entry.bizType + "::" + taskId;
        return new GoldenBeansActions().execute(key, "", new JSONObject(), action -> {
            JSONObject task = findTask(entry, taskId);
            return task != null && GoldenBeansPolicy.rewardConfirmed(task.optString("taskStatus", ""))
                    ? GoldenBeansActions.confirmed(0) : GoldenBeansActions.unknown();
        }, action -> {
            JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.claimAwardOf(entry.bizType, entry.source, entry.taskSceneCode, taskId));
            return GoldenBeansActions.explicitRejection(response) ? GoldenBeansActions.rejected() : GoldenBeansActions.unknown();
        }) == GoldenBeansActions.State.CONFIRMED;
    }

    private int claimPendingRewards(GoldenBeansEntry entry, int interval) throws Exception {
        JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(entry.bizType, entry.source, "FARM_TASK", "TASK_LIST"));
        if (!GoldenBeansSupport.ok(response)) return -1;
        JSONArray tasks = response.optJSONArray("taskList");
        int claimed = 0;
        boolean failed = false;
        if (tasks == null) return -1;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task == null || !entry.taskSceneCode.equals(task.optString("sceneCode"))) continue;
            String state = task.optString("taskStatus", "");
            if ("FINISHED".equals(state) || "TO_RECEIVE".equals(state)) {
                GoldenBeansSupport.pause(interval);
                if (claimAward(entry, task.optString("taskId", ""), displayName(task))) claimed++;
                else failed = true;
            }
        }
        return failed ? -1 : claimed;
    }

    /** 仅明确的不可执行错误自动拉黑，限流、网络和服务繁忙留待下一轮。 */
    private boolean retryable(JSONObject response) {
        String code = response.optString("resultCode", response.optString("code", ""));
        return code.isEmpty() || "102".equals(code) || "1009".equals(code) || "400000040".equals(code)
                || "SYSTEM_ERROR".equals(code) || "TIMEOUT".equals(code) || "FREQUENCY_LIMIT".equals(code);
    }

    private void markBlacklist(String taskId) {
        if (!autoBlacklist || blacklist == null || taskId.isEmpty()) return;
        GoldenBeansRuntime.check();
        if (blacklist.getValue().add(taskId)) Config.save(GoldenBeansRuntime.uid(), true);
    }

    private static boolean isTransactionTask(String id) {
        String key = id.toLowerCase(java.util.Locale.ROOT);
        for (String part : new String[]{"xiadan", "zhifu", "goumai", "jiaofei", "huankuan", "chongzhi", "kuaidi", "huishou", "jiudian", "juankuan", "choukuan"}) if (key.contains(part)) return true;
        return java.util.regex.Pattern.compile("(?<![a-z])(pay|charge|zulin|zuche|dache|jipiao|yuebao)(?![a-z])|game_cz|_cz[0-9]").matcher(key).find();
    }

    /** 拉取两个入口动态列表，配置默认为空，启用自动黑名单后才写预置项。 */
    public boolean initTaskListMap() {
        GoldenBeansTaskListMap map = IdMapManager.getInstance(GoldenBeansTaskListMap.class);
        map.load(GoldenBeansRuntime.uid());
        try {
            boolean complete = true;
            for (GoldenBeansEntry entry : GoldenBeansEntry.ALL) {
                JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(entry.bizType, entry.source, "FARM_TASK", "TASK_LIST"));
                if (!GoldenBeansSupport.ok(response)) { complete = false; continue; }
                JSONArray tasks = response.optJSONArray("taskList");
                if (tasks == null) { complete = false; continue; }
                for (int i = 0; i < tasks.length(); i++) {
                    JSONObject task = tasks.optJSONObject(i);
                    if (task == null || !entry.taskSceneCode.equals(task.optString("sceneCode"))) continue;
                    String key = blacklistKey(task);
                    if (!key.isEmpty()) map.add(key, displayName(task));
                    if (isTransactionTask(key) || "TEST_PUSH_SUBSCRIBE".equals(key)) markBlacklist(key);
                }
            }
            GoldenBeansRuntime.check();
            return map.save(GoldenBeansRuntime.uid()) && complete;
        } catch (Throwable error) {
            GoldenBeansRuntime.rethrowIfStopped(error);
            GoldenBeansRuntime.error("金豆同步任务列表失败", error);
            return false;
        }
    }
}
