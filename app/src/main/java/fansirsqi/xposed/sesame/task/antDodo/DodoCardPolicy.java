package fansirsqi.xposed.sesame.task.antDodo;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 神奇物种的响应校验与卡片选择，不依赖账号或网络。 */
public final class DodoCardPolicy {
    private DodoCardPolicy() {}

    public static boolean accepted(String response) {
        try {
            JSONObject root = new JSONObject(response);
            if (root.has("success") && !root.optBoolean("success")) return false;
            return root.optBoolean("success") || "SUCCESS".equals(root.optString("resultCode"));
        } catch (Exception ignored) { return false; }
    }

    public static boolean shouldUseDraw(int timing, long endTime, long now) {
        return timing == 0 || (endTime > now && endTime - now <= 86_400_000L);
    }

    public static boolean matchesBook(JSONObject book, int status, int collected, int medal) {
        if (book == null) return false;
        String bookStatus = book.optString("bookStatus");
        if ("GOING".equals(bookStatus)) bookStatus = "DOING";
        String collectedStatus = book.optString("bookCollectedStatus");
        String medalStatus = book.optString("medalGenerationStatus");
        return matches(bookStatus, status, new String[]{"ALL", "END", "DOING"})
                && matches(collectedStatus, collected, new String[]{"ALL", "NOT_COMPLETED", "COMPLETED"})
                && matches(medalStatus, medal, new String[]{"ALL", "CAN_NOT_GENERATE", "CAN_GENERATE", "GENERATED"});
    }

    private static boolean matches(String actual, int selected, String[] choices) {
        if (selected < 0 || selected >= choices.length) return false;
        boolean known = false;
        for (int i = 1; i < choices.length; i++) if (choices[i].equals(actual)) known = true;
        return known && (selected == 0 || choices[selected].equals(actual));
    }

    public static JSONObject selectUniversal(JSONArray cards, int minStar, boolean preferNeverOwned) {
        JSONObject best = null;
        int bestCount = Integer.MAX_VALUE;
        int bestStar = -1;
        boolean bestOwned = true;
        for (int i = 0; cards != null && i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            JSONObject animal = card == null ? null : card.optJSONObject("animal");
            JSONObject detail = card == null ? null : card.optJSONObject("collectDetail");
            int star = card == null ? 0 : card.optInt("star", 0);
            if (animal == null || animal.optString("animalId").isEmpty() || detail == null
                    || !detail.has("count") || detail.optInt("count", -1) < 0 || star < minStar) continue;
            int count = detail.optInt("count");
            boolean owned = detail.optBoolean("hasCollected", true);
            boolean samePriority = !preferNeverOwned || owned == bestOwned;
            if (best == null || (preferNeverOwned && bestOwned && !owned)
                    || (samePriority && (count < bestCount || (count == bestCount && star > bestStar)))) {
                best = animal;
                bestCount = count;
                bestStar = star;
                bestOwned = owned;
            }
        }
        return best;
    }

    public static List<JSONObject> giftCards(JSONArray cards, int minStar) {
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; cards != null && i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            JSONObject detail = card == null ? null : card.optJSONObject("collectDetail");
            JSONObject animal = card == null ? null : card.optJSONObject("animal");
            if (detail != null && detail.optInt("count", 0) > 0 && card.optInt("star", 0) >= minStar
                    && animal != null && !animal.optString("animalId").isEmpty()) result.add(card);
        }
        return result;
    }

    public static List<JSONObject> scratchCards(JSONObject data) {
        List<JSONObject> result = new ArrayList<>();
        JSONObject animal = data == null ? null : data.optJSONObject("animal");
        JSONObject detail = data == null ? null : data.optJSONObject("collectDetail");
        JSONArray cards = detail == null ? null : detail.optJSONArray("cardList");
        if (animal == null || animal.optString("animalId").isEmpty() || animal.optString("bookId").isEmpty()) return result;
        Set<String> seen = new HashSet<>();
        for (int i = 0; cards != null && i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            String id = card == null ? "" : card.optString("cardInstanceId");
            if (!id.isEmpty() && card.optBoolean("scratchable") && !card.optBoolean("scratched") && seen.add(id)) result.add(card);
        }
        return result;
    }
}
