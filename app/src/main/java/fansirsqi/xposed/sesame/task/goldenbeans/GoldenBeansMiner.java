package fansirsqi.xposed.sesame.task.goldenbeans;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fansirsqi.xposed.sesame.util.Log;

/**
 * 金猫矿工：按服务端下发的可抓取次数逐次抓取金豆。
 * <p>
 * 每轮抓取后都以响应里的 taskProgress 为准推进剩余次数；一旦服务端未推进次数、
 * 或要求观看广告，就立刻停止，避免无效重复请求。
 */
public final class GoldenBeansMiner {

    private GoldenBeansMiner() {
    }

    public static void run(int interval) {
        try {
            JSONObject indexJo = GoldenBeansSupport.parse(goldenbeansRpcCall.minerHome());
            if (!GoldenBeansSupport.ok(indexJo)) {
                GoldenBeansRuntime.record("金猫矿工⚠️首页查询失败[" + GoldenBeansSupport.describe(indexJo) + "]");
                return;
            }
            if (!indexJo.has("enabled")) {
                GoldenBeansRuntime.record("金猫矿工⚠️响应缺少enabled");
                return;
            }
            if (!indexJo.optBoolean("enabled", false)) {
                GoldenBeansRuntime.record("金猫矿工⏸️服务端未启用");
                return;
            }
            JSONObject minerInfo = indexJo.optJSONObject("minerInfo");
            if (minerInfo == null) {
                GoldenBeansRuntime.record("金猫矿工⚠️响应缺少矿工信息");
                return;
            }
            JSONObject taskProgress = minerInfo.optJSONObject("taskProgress");
            if (taskProgress == null || !taskProgress.has("canGrab") || !taskProgress.has("remainingTimes")) {
                GoldenBeansRuntime.record("金猫矿工⚠️响应缺少可抓取状态");
                return;
            }
            if (!taskProgress.optBoolean("canGrab", false)) {
                GoldenBeansRuntime.record("金猫矿工⏸️今日无可抓取次数");
                return;
            }

            // 已经抓取过的格子不再重复抓取
            Set<String> grabbedItemIds = new HashSet<>();
            JSONObject progress = minerInfo.optJSONObject("progress");
            JSONArray alreadyGrabbed = progress != null ? progress.optJSONArray("grabbedItemIds") : null;
            if (alreadyGrabbed != null) {
                for (int i = 0; i < alreadyGrabbed.length(); i++) {
                    String itemId = alreadyGrabbed.optString(i, "").trim();
                    if (!itemId.isEmpty()) {
                        grabbedItemIds.add(itemId);
                    }
                }
            }

            JSONObject currentLevel = minerInfo.optJSONObject("currentLevel");
            JSONArray items = currentLevel != null ? currentLevel.optJSONArray("items") : null;
            if (items == null) {
                GoldenBeansRuntime.record("金猫矿工⚠️首页缺少items");
                return;
            }
            List<String> beanItemIds = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String itemId = item.optString("itemId", "").trim();
                if ("BEAN".equals(item.optString("type", "")) && !itemId.isEmpty()
                        && !grabbedItemIds.contains(itemId)) {
                    beanItemIds.add(itemId);
                }
            }

            int candidateIndex = 0;
            int remainingTimes = taskProgress.optInt("remainingTimes", 0);
            boolean canGrab = taskProgress.optBoolean("canGrab", false);
            int grabbedTimes = 0;
            while (canGrab && remainingTimes > 0 && grabbedTimes < 128) {
                String itemId = candidateIndex < beanItemIds.size() ? beanItemIds.get(candidateIndex) : "";
                String expectedResult = itemId.isEmpty() ? "EMPTY" : "BEAN";
                String grabKey = "grab::" + itemId + "::" + remainingTimes;
                JSONObject details = new JSONObject().put("beforeRemaining", remainingTimes);
                GoldenBeansSupport.pause(interval);
                GoldenBeansActions.State state = new GoldenBeansActions().execute(grabKey, "", details, action -> {
                    JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.minerHome());
                    if (!GoldenBeansSupport.ok(response)) return GoldenBeansActions.unknown();
                    JSONObject miner = response.optJSONObject("minerInfo");
                    JSONObject updated = miner == null ? null : miner.optJSONObject("taskProgress");
                    if (updated != null && updated.has("remainingTimes") && updated.optInt("remainingTimes") < action.optInt("beforeRemaining")) {
                        action.put("receipt", new JSONObject().put("taskProgress", updated));
                        GoldenBeansActions.checkpoint(action);
                        return GoldenBeansActions.confirmed(0);
                    }
                    return GoldenBeansActions.unknown();
                }, action -> {
                    JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.grabBean(expectedResult, itemId));
                    if (GoldenBeansActions.explicitRejection(response)) return GoldenBeansActions.rejected();
                    if (GoldenBeansSupport.ok(response)) {
                        action.put("receipt", response);
                        GoldenBeansActions.checkpoint(action);
                        JSONObject progressAfter = response.optJSONObject("taskProgress");
                        if (progressAfter != null && progressAfter.optInt("remainingTimes", Integer.MAX_VALUE) < action.optInt("beforeRemaining")) return GoldenBeansActions.confirmed(0);
                    }
                    return GoldenBeansActions.unknown();
                });
                if (state != GoldenBeansActions.State.CONFIRMED) return;
                JSONObject saved = GoldenBeansActions.pending(grabKey);
                JSONObject grabResponse = saved == null ? null : saved.optJSONObject("receipt");
                if (grabResponse == null) return;
                goldenbeansRpcCall.pullBySource(goldenbeansRpcCall.MINER_PAGE_SOURCE, "JAR_INFO");
                if (grabResponse.optBoolean("needAd", false)) {
                    GoldenBeansRuntime.record("金猫矿工⚠️需观看广告#待人工处理");
                    return;
                }
                if ("BEAN".equals(expectedResult)) {
                    candidateIndex++;
                }
                JSONObject updated = grabResponse.optJSONObject("taskProgress");
                if (updated == null || !updated.has("canGrab") || !updated.has("remainingTimes")) {
                    GoldenBeansRuntime.record("金猫矿工⚠️响应缺少可抓取状态");
                    return;
                }
                int updatedRemaining = updated.optInt("remainingTimes", remainingTimes);
                if (updatedRemaining >= remainingTimes) {
                    GoldenBeansRuntime.record("金猫矿工⚠️次数未推进[" + remainingTimes + "→" + updatedRemaining + "]");
                    return;
                }
                remainingTimes = updatedRemaining;
                canGrab = updated.optBoolean("canGrab", false);
                grabbedTimes++;
                GoldenBeansRuntime.record("金猫矿工⛏️抓取[第" + grabbedTimes + "次]"
                        + GoldenBeansSupport.awardText(grabResponse));
            }
            GoldenBeansRuntime.record("金猫矿工⛏️完成#共抓取[" + grabbedTimes + "]次");
        } catch (Throwable th) {
            GoldenBeansRuntime.rethrowIfStopped(th);
            GoldenBeansRuntime.record(GoldenBeansSupport.TAG, "runMiner err:");
            GoldenBeansRuntime.error(GoldenBeansSupport.TAG, th);
        }
    }
}
