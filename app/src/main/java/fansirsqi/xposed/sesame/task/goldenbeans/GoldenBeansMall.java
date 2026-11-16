package fansirsqi.xposed.sesame.task.goldenbeans;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.maps.IdMapManager;
import fansirsqi.xposed.sesame.util.maps.GoldenBeansMallItemMap;

/** 商城动态目录与按日兑换，单轮最多五单，未确认的下单不再次发送。 */
public final class GoldenBeansMall {
    private static final int PAGE_SIZE = 20;
    private static final int MAX_EXCHANGE_PER_RUN = 5;
    private GoldenBeansMall() {}

    public static boolean run(int interval, Map<String, Integer> selected, boolean enabled) {
        try {
            JSONArray items = fetchItems(interval);
            if (items == null) return false;
            syncCatalogue(items);
            if (!enabled || selected == null || selected.isEmpty()) return true;
            int exchanged = 0;
            for (int i = 0; i < items.length() && exchanged < MAX_EXCHANGE_PER_RUN; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || !selected.containsKey(item.optString("spuName"))) continue;
                String name = item.optString("spuName");
                int limit = Math.max(selected.get(name) == null ? 0 : selected.get(name), 0);
                int left = item.optInt("dayLeft", -1);
                while (exchanged < MAX_EXCHANGE_PER_RUN && left != 0) {
                    if (!exchange(interval, item, limit)) break;
                    exchanged++;
                    if (left > 0) item.put("dayLeft", --left);
                }
            }
            if (exchanged > 0) GoldenBeansRuntime.record("金豆商城🛒本轮兑换[" + exchanged + "]次");
            return true;
        } catch (Throwable error) {
            GoldenBeansRuntime.rethrowIfStopped(error);
            GoldenBeansRuntime.error("金豆商城失败", error);
            return false;
        }
    }

    /** 页码有界，重复商品页立即结束，避免服务端忽略分页时空转。 */
    private static JSONArray fetchItems(int interval) throws Exception {
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int page = 0; page < 10; page++) {
            GoldenBeansSupport.pause(interval);
            JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.mallItems(page * PAGE_SIZE, PAGE_SIZE));
            if (!GoldenBeansSupport.ok(response)) return null;
            JSONArray list = response.optJSONArray("itemInfoVOList");
            if (list == null && response.optJSONObject("data") != null) list = response.optJSONObject("data").optJSONArray("itemInfoVOList");
            if (list == null) return null;
            int added = 0;
            for (int i = 0; i < list.length(); i++) {
                JSONObject spu = list.optJSONObject(i);
                if (spu == null) continue;
                JSONArray skus = spu.optJSONArray("skuModelList");
                JSONObject sku = skus == null ? null : skus.optJSONObject(0);
                if (sku == null) continue;
                String spuId = spu.optString("spuId", "");
                String skuId = sku.optString("skuId", "");
                if (spuId.isEmpty() || skuId.isEmpty() || !seen.add(spuId + "::" + skuId)) continue;
                JSONObject price = sku.optJSONObject("price");
                JSONObject money = sku.optJSONObject("moneyPrice");
                int cents = price == null ? -1 : price.optInt("cent", -1);
                JSONObject item = new JSONObject().put("spuName", spu.optString("spuName", ""))
                        .put("spuId", spuId).put("skuId", skuId)
                        .put("cost", cents < 0 ? -1 : (int) (((long) cents + 99) / 100))
                        .put("needMoney", money != null && money.optInt("cent", 0) > 0)
                        .put("dayLeft", sku.optInt("userDayLeftAmount", -1));
                result.put(item); added++;
            }
            if (list.length() < PAGE_SIZE || added == 0) break;
        }
        return result;
    }

    private static void syncCatalogue(JSONArray items) {
        GoldenBeansMallItemMap map = IdMapManager.getInstance(GoldenBeansMallItemMap.class);
        map.load(GoldenBeansRuntime.uid());
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("spuName", "");
            if (!name.isEmpty()) map.add(name, name);
        }
        GoldenBeansRuntime.check();
        map.save(GoldenBeansRuntime.uid());
    }

    static boolean exchange(int interval, JSONObject item, int dailyLimit) throws Exception {
        String name = item.optString("spuName", "");
        String counter = "goldenBeans::mallExchange::" + name;
        int done = GoldenBeansStatus.getIntFlagToday(counter);
        String key = "mall::" + item.optString("spuId") + "::" + item.optString("skuId") + "::" + done;
        if (GoldenBeansActions.pending(key) != null) {
            return new GoldenBeansActions().execute(key, counter, new JSONObject(),
                    action -> confirmOrder(interval, action), action -> GoldenBeansActions.unknown()) == GoldenBeansActions.State.CONFIRMED;
        }
        int jars = jarCount(interval);
        if (!GoldenBeansPolicy.canExchange(item.optInt("cost", -1), jars, item.optBoolean("needMoney", false),
                item.optInt("dayLeft", -1), dailyLimit, done)) return false;
        GoldenBeansSupport.pause(interval);
        return new GoldenBeansActions().execute(key, counter, new JSONObject(item.toString()),
                action -> confirmOrder(interval, action), action -> {
                    JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.mallExchange(action.getString("spuId"), action.getString("skuId"), action.getString("actionId")));
                    JSONObject data = response == null ? null : response.optJSONObject("data");
                    JSONObject result = response != null && response.has("canBuy") ? response : data;
                    if (GoldenBeansActions.explicitRejection(response) || (result != null && result.has("canBuy") && !result.optBoolean("canBuy"))) return GoldenBeansActions.rejected();
                    if (!GoldenBeansSupport.ok(response) || result == null || !result.optBoolean("canBuy", false)) return GoldenBeansActions.unknown();
                    String order = response.optString("orderNo", "");
                    if (order.isEmpty() && data != null) order = data.optString("orderNo", "");
                    if (!order.isEmpty()) {
                        // 本次已受理回执中的订单号与固定请求身份一起持久化。
                        action.put("orderNo", order);
                        action.put("receiptAccepted", true);
                        GoldenBeansActions.checkpoint(action);
                        return GoldenBeansActions.confirmed(1);
                    }
                    return GoldenBeansActions.unknown();
                }) == GoldenBeansActions.State.CONFIRMED;
    }

    private static GoldenBeansActions.Result confirmOrder(int interval, JSONObject action) throws Exception {
        if (action.optBoolean("receiptAccepted", false) && !action.optString("orderNo", "").isEmpty()) return GoldenBeansActions.confirmed(1);
        GoldenBeansSupport.pause(interval);
        JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.mallOrders(1, 20));
        if (!GoldenBeansSupport.ok(response)) return GoldenBeansActions.unknown();
        JSONArray orders = response.optJSONArray("orderInfos");
        if (orders == null && response.optJSONObject("data") != null) orders = response.optJSONObject("data").optJSONArray("orderInfos");
        return matchOrder(action, orders);
    }

    static GoldenBeansActions.Result matchOrder(JSONObject action, JSONArray orders) {
        if (orders == null) return GoldenBeansActions.unknown();
        for (int i = 0; i < orders.length(); i++) {
            JSONObject order = orders.optJSONObject(i);
            if (order == null) continue;
            String id = order.optString("orderNo", order.optString("orderId", ""));
            boolean sameAction = action.optString("actionId").equals(order.optString("requestId", ""))
                    || (!action.optString("orderNo", "").isEmpty() && action.optString("orderNo").equals(id));
            boolean sameProduct = action.optString("spuId").equals(order.optString("spuId", ""))
                    && action.optString("skuId").equals(order.optString("skuId", ""));
            if (!sameAction || !sameProduct) continue;
            String state = order.optString("orderStatus", order.optString("status", ""));
            if ("FAILED".equals(state) || "CLOSED".equals(state) || "CANCELLED".equals(state)) return GoldenBeansActions.rejected();
            if ("SUCCESS".equals(state) || "FINISHED".equals(state) || "COMPLETED".equals(state)) return GoldenBeansActions.confirmed(1);
        }
        return GoldenBeansActions.unknown();
    }

    private static int jarCount(int interval) throws Exception {
        GoldenBeansSupport.pause(interval);
        JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.home());
        if (!GoldenBeansSupport.ok(response)) return -1;
        JSONObject jar = GoldenBeansSupport.findObject(response, "jarInfo");
        return jar == null ? -1 : jar.optInt("jarCount", -1);
    }
}
