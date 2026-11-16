package fansirsqi.xposed.sesame.task.antOcean;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** 画鱼开通与评价能量流程，所有动作均受运行代际守卫保护。 */
final class AiFishEnhancement {
    interface Gateway {
        boolean active();
        String home();
        default String status() { return home(); }
        String draw();
        String list();
        String like(String imageId, String seasonId, String targetUserId);
        boolean limitedToday();
        void markLimited();
        void waitMillis(long millis);
        default void report(String message) {}
    }
    private final Gateway gateway;
    AiFishEnhancement(Gateway gateway) { this.gateway = gateway; }

    void run(boolean drawEnabled, boolean likeEnabled) {
        if (!gateway.active()) return;
        if (drawEnabled) {
            JSONObject home = acceptedRoot(gateway.status());
            if (home == null || !gateway.active()) return;
            String fishStatus = home.optString("fishStatus");
            if (fishStatus.isEmpty()) return;
            if ("DEFAULT_AI_FISH".equals(fishStatus)) {
                if (!gateway.active() || acceptedRoot(gateway.draw()) == null || !gateway.active()) return;
                JSONObject confirmed = acceptedRoot(gateway.status());
                if (!gateway.active() || confirmed == null || confirmed.optString("fishStatus").isEmpty()
                        || "DEFAULT_AI_FISH".equals(confirmed.optString("fishStatus"))) return;
                gateway.report("默认鱼开通画鱼成功");
            }
        }
        if (!likeEnabled || gateway.limitedToday()) return;
        Set<String> attempted = new HashSet<>();
        int count = 0;
        for (int pass = 0; pass < 10 && count < 40 && gateway.active(); pass++) {
            JSONObject list = acceptedRoot(gateway.list());
            if (list == null || !gateway.active()) return;
            int remaining = list.optInt("appraiseRewardRemainNum", -1);
            if (remaining == 0) { gateway.markLimited(); return; }
            JSONArray fishes = list.optJSONArray("fishList");
            boolean progressed = false;
            for (int i = 0; fishes != null && i < fishes.length() && count < 40; i++) {
                if (!gateway.active()) return;
                JSONObject fish = fishes.optJSONObject(i);
                JSONObject appraise = fish == null ? null : fish.optJSONObject("appraiseVO");
                if (appraise == null || !appraise.optBoolean("canAppraise")) continue;
                String image = fish.optString("imageId");
                String season = fish.optString("seasonId");
                String user = appraise.optString("receiveAppraiseUserId");
                if (user.isEmpty()) user = fish.optString("userId");
                if (image.isEmpty() || season.isEmpty() || user.isEmpty()
                        || !attempted.add(season + "|" + user + "|" + image)) continue;
                JSONObject response = acceptedRoot(gateway.like(image, season, user));
                if (response == null || !gateway.active()) return;
                count++;
                progressed = true;
                if (response.optInt("appraiseRewardRemainNum", -1) == 0) { gateway.markLimited(); return; }
                // 缺失奖励不等于服务端明确告知次数耗尽，停止本轮但不写每日完成。
                JSONArray rewards = response.optJSONArray("appraiseRewardList");
                if (rewards == null) return;
                int energy = 0;
                for (int j = 0; j < rewards.length(); j++) {
                    JSONObject reward = rewards.optJSONObject(j);
                    JSONObject ext = reward == null ? null : reward.optJSONObject("extInfo");
                    JSONObject popup = ext == null ? null : ext.optJSONObject("popup");
                    if (popup != null) energy += Math.max(0, popup.optInt("rightsNums", 0));
                }
                if (energy <= 0) return;
                gateway.report("评价点赞获得" + energy + "g能量");
                if (remaining > 0 && --remaining == 0) return;
                gateway.waitMillis(1000L);
            }
            if (!progressed) return;
        }
    }

    static JSONObject acceptedRoot(String response) {
        try {
            JSONObject root = new JSONObject(response);
            if (root.has("success") && !root.optBoolean("success")) return null;
            JSONObject value = root.optJSONObject("resData");
            if (value == null) value = root;
            if (value.has("success") && !value.optBoolean("success")) return null;
            if (!(value.optBoolean("success") || "SUCCESS".equals(value.optString("resultCode"))
                    || "100000000".equals(value.optString("code")))) return null;
            JSONObject data = value.optJSONObject("data");
            return data == null ? value : data;
        } catch (Exception ignored) { return null; }
    }
}
