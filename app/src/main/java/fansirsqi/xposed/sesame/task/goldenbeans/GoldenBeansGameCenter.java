package fansirsqi.xposed.sesame.task.goldenbeans;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;


import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.task.goldenbeans.GoldenBeansStatus;

/**
 * 金豆乐园奖励处理：有抽奖次数时优先抽奖，次数用尽后按游戏权益做上报。
 * <p>
 * 每轮都重新拉取快照，只有服务端状态确实推进（抽奖次数变化或权益次数增加）才继续；
 * 若上报后状态没有变化则保留待确认动作，下轮先查证，避免不确定结果时重复上报。
 */
public final class GoldenBeansGameCenter {

    /** 收敛轮次上限，防止服务端状态回环导致死循环 */
    private static final int MAX_ROUND = 64;

    private GoldenBeansGameCenter() {
    }

    /**
     * @return 是否已无剩余可自动推进的乐园奖励
     */
    public static boolean run(int interval) {
        try {
            Set<String> attempted = new HashSet<>();
            Set<String> failed = new HashSet<>();
            for (int round = 0; round < MAX_ROUND; round++) {
                JSONObject snapshot = GoldenBeansSupport.parse(goldenbeansRpcCall.fetchGameList());
                if (!GoldenBeansSupport.ok(snapshot)) {
                    GoldenBeansRuntime.record("金豆乐园⚠️列表查询失败[" + GoldenBeansSupport.describe(snapshot) + "]");
                    return false;
                }
                JSONObject drawRights = GoldenBeansSupport.findObject(snapshot, "gameCenterDrawRights");
                int quotaCanUse = drawRights != null ? Math.max(drawRights.optInt("quotaCanUse", 0), 0) : 0;
                int usedQuota = drawRights != null ? Math.max(drawRights.optInt("usedQuota", 0), 0) : 0;
                int quotaLimit = drawRights != null ? Math.max(drawRights.optInt("quotaLimit", 0), 0) : 0;

                // 有可用抽奖次数：优先抽奖
                if (quotaCanUse > 0) {
                    String drawKey = "draw::" + usedQuota + "::" + quotaCanUse;
                    JSONObject details = new JSONObject().put("beforeQuota", quotaCanUse).put("beforeUsed", usedQuota);
                    GoldenBeansSupport.pause(interval);
                    GoldenBeansActions.State result = new GoldenBeansActions().execute(drawKey, "", details, action -> {
                        JSONObject after = GoldenBeansSupport.parse(goldenbeansRpcCall.fetchGameList());
                        if (!GoldenBeansSupport.ok(after)) return GoldenBeansActions.unknown();
                        JSONObject rights = GoldenBeansSupport.findObject(after, "gameCenterDrawRights");
                        return rights != null && (rights.optInt("quotaCanUse", action.optInt("beforeQuota")) < action.optInt("beforeQuota")
                                || rights.optInt("usedQuota", action.optInt("beforeUsed")) > action.optInt("beforeUsed"))
                                ? GoldenBeansActions.confirmed(0) : GoldenBeansActions.unknown();
                    }, action -> {
                        JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.drawLottery());
                        return GoldenBeansActions.explicitRejection(response) ? GoldenBeansActions.rejected() : GoldenBeansActions.unknown();
                    });
                    if (result != GoldenBeansActions.State.CONFIRMED) return false;
                    continue;
                }

                // 无抽奖次数：尝试游戏权益上报
                int remainingDraws = Math.max(quotaLimit - usedQuota - quotaCanUse, 0);
                Map<String, GameCandidate> candidates = collect(snapshot);
                // 额度状态（可用/已用/上限）每轮恒定，候选明细属过程数据；不单独打，
                // 真正抽中时下面的「金豆乐园🎰抽奖[第N次]#获得…」才是结果
                GameCandidate candidate = null;
                for (GameCandidate item : candidates.values()) {
                    if ((item.hasPendingReward() || remainingDraws > 0)
                            && GoldenBeansGameReporter.matchAppId(item.appId) != null
                            && !failed.contains(item.key())
                            && !attempted.contains(item.key() + ":" + remainingDraws + ":" + item.rightTimes)) {
                        candidate = item;
                        break;
                    }
                }

                if (candidate == null) {
                    if (quotaLimit > 0 && usedQuota >= quotaLimit) {
                        GoldenBeansRuntime.record(GoldenBeansSupport.TAG, "金豆乐园🎰今日次数已用尽[" + usedQuota + "/" + quotaLimit + "]");
                    } else {
                        GoldenBeansRuntime.record(GoldenBeansSupport.TAG, "金豆乐园🎰无可自动推进项#次数[" + usedQuota + "/" + quotaLimit + "]");
                    }
                    return true;
                }

                attempted.add(candidate.key() + ":" + remainingDraws + ":" + candidate.rightTimes);
                GoldenBeansGameReporter gameTask = GoldenBeansGameReporter.matchAppId(candidate.appId);
                if (gameTask == null) {
                    continue;
                }
                int remaining = Math.max(candidate.remainingRewards(), remainingDraws);
                GoldenBeansRuntime.record("金豆乐园🎮游玩[" + gameTask.getTitle() + "]#目标[" + remaining + "]");
                GoldenBeansSupport.pause(interval);
                // 金豆乐园场景必须使用专用上报渠道 "goldenbean"，
                // 否则游戏服会接受上报（code=1）但支付宝侧权益不推进，表现为"砸蛋/游戏任务无效"
                final GameCandidate selected = candidate;
                // 动作身份只绑定此游戏权益阶段，全局抽奖变化不能产生新的上报身份。
                String actionKey = "game::" + selected.key() + "::" + selected.rightTimes;
                JSONObject details = new JSONObject().put("candidate", selected.key()).put("beforeRights", selected.rightTimes);
                GoldenBeansActions.State state = new GoldenBeansActions().execute(actionKey, "", details,
                        GoldenBeansGameCenter::gameProgress, action -> {
                            gameTask.reportSync("金豆乐园:" + gameTask.getTitle(), remaining,
                                    goldenbeansRpcCall.GAME_CHANNEL, action.getString("actionId"),
                                    () -> gameProgress(action).state == GoldenBeansActions.State.CONFIRMED);
                            return gameTask.isRejected() ? GoldenBeansActions.rejected() : GoldenBeansActions.unknown();
                        });
                if (state == GoldenBeansActions.State.CONFIRMED) continue;
                if (state == GoldenBeansActions.State.REJECTED) failed.add(selected.key());
                else return false;
            }
            GoldenBeansRuntime.record("金豆乐园⚠️达到收敛轮次上限[" + MAX_ROUND + "]");
            return false;
        } catch (Throwable th) {
            GoldenBeansRuntime.rethrowIfStopped(th);
            GoldenBeansRuntime.record(GoldenBeansSupport.TAG, "runGameCenterFlow err:");
            GoldenBeansRuntime.error(GoldenBeansSupport.TAG, th);
            return false;
        }
    }

    private static GoldenBeansActions.Result gameProgress(JSONObject action) {
        try {
            JSONObject snapshot = GoldenBeansSupport.parse(goldenbeansRpcCall.fetchGameList());
            if (!GoldenBeansSupport.ok(snapshot)) throw new IllegalStateException("游戏权益回查失败");
            GameCandidate candidate = collect(snapshot).get(action.optString("candidate"));
            if (candidate != null && candidate.rightTimes > action.optInt("beforeRights")) return GoldenBeansActions.confirmed(0);
            // 只认同一 candidate 的权益增加，不能把其它游戏或抽奖的全局次数变化归给本动作。
            return GoldenBeansActions.unknown();
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static Map<String, GameCandidate> collect(JSONObject response) {
        Map<String, GameCandidate> candidates = new LinkedHashMap<>();
        scan(response, candidates);
        return candidates;
    }

    private static void scan(Object source, Map<String, GameCandidate> candidates) {
        if (source instanceof JSONObject) {
            JSONObject obj = (JSONObject) source;
            String appId = obj.optString("appId", "").trim();
            JSONArray benefits = obj.optJSONArray("deliveryBenefitList");
            if (!appId.isEmpty() && benefits != null) {
                for (int i = 0; i < benefits.length(); i++) {
                    JSONObject benefit = benefits.optJSONObject(i);
                    if (benefit == null
                            || !"IEP_REQUEST".equalsIgnoreCase(benefit.optString("benefitType", ""))) {
                        continue;
                    }
                    String tracer = benefit.optString("iepTaskTracer", "");
                    String taskId = benefit.optString("iepTaskId", "").trim();
                    if (taskId.isEmpty()) {
                        taskId = GoldenBeansSupport.tracerField(tracer, "taskType");
                    }
                    int rightTimesLimit = benefit.optInt("rightTimesLimit", 0);
                    if (taskId.isEmpty() || rightTimesLimit <= 0) {
                        continue;
                    }
                    String taskStatus = benefit.optString("taskStatus", "").trim();
                    if (taskStatus.isEmpty()) {
                        taskStatus = GoldenBeansSupport.tracerField(tracer, "taskStatus");
                    }
                    GameCandidate candidate = new GameCandidate();
                    candidate.appId = appId;
                    candidate.taskId = taskId;
                    candidate.taskStatus = taskStatus;
                    candidate.rightTimes = Math.max(benefit.optInt("rightTimes", 0), 0);
                    candidate.rightTimesLimit = rightTimesLimit;
                    if (!candidates.containsKey(candidate.key())) {
                        candidates.put(candidate.key(), candidate);
                    }
                }
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                scan(obj.opt(keys.next()), candidates);
            }
        } else if (source instanceof JSONArray) {
            JSONArray array = (JSONArray) source;
            for (int i = 0; i < array.length(); i++) {
                scan(array.opt(i), candidates);
            }
        }
    }

    /** 金豆乐园中可上报的游戏权益候选 */
    private static final class GameCandidate {
        private String appId = "";
        private String taskId = "";
        private String taskStatus = "";
        private int rightTimes;
        private int rightTimesLimit;

        private String key() {
            return appId + ":" + taskId;
        }

        private boolean hasPendingReward() {
            return !"RECEIVED".equalsIgnoreCase(taskStatus) && rightTimes < rightTimesLimit;
        }

        private int remainingRewards() {
            return Math.max(rightTimesLimit - rightTimes, 0);
        }
    }
}
