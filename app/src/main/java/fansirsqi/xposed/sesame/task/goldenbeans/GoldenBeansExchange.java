package fansirsqi.xposed.sesame.task.goldenbeans;

import org.json.JSONObject;
import fansirsqi.xposed.sesame.util.Log;

/** 两种换豆共用持久化动作，已到账回执与每日额度同次提交，未知结果只回查不重放。 */
public final class GoldenBeansExchange {
    private static final String MANURE = "goldenBeans::manureExchangeBeanAmount";
    private static final String SESAME = "goldenBeans::sesameExchangeBeanAmount";
    private GoldenBeansExchange() {}
    public static void exchangeManure(int interval, int dailyLimit) { exchange(GoldenBeansEntry.FARM, interval, dailyLimit, MANURE); }
    public static void exchangeSesame(int interval, int dailyLimit) { exchange(GoldenBeansEntry.ALCHEMY, interval, dailyLimit, SESAME); }

    private static void exchange(GoldenBeansEntry entry, int interval, int limit, String counter) {
        try {
            int done = GoldenBeansStatus.getIntFlagToday(counter);
            String key = "exchange::" + entry.bizType + "::" + done;
            JSONObject pending = GoldenBeansActions.pending(key);
            if (pending != null) {
                new GoldenBeansActions().execute(key, counter, new JSONObject(), action -> reconcile(entry, action), action -> GoldenBeansActions.unknown());
                return;
            }
            JSONObject snapshot = GoldenBeansSupport.parse(goldenbeansRpcCall.homeOf(entry.bizType, entry.source));
            if (!GoldenBeansSupport.ok(snapshot)) return;
            JSONObject info = snapshot.optJSONObject("manureExchangeInfo");
            if (info == null || !info.optBoolean("pageOpened", false)) return;
            if (entry == GoldenBeansEntry.FARM && (!info.optBoolean("farmOpened", false) || !info.optBoolean("taobaoBinding", false))) return;
            int amount = GoldenBeansPolicy.exchangeAmount(info.optInt("currentManure", 0), info.optInt("effectiveExchangeManure", 0),
                    info.optInt("beanReward", entry == GoldenBeansEntry.FARM ? 1 : 0), info.optInt("remainQuota", 0),
                    info.optInt("minExchangeAmount", 0), limit, done);
            if (amount <= 0) return;
            JSONObject details = new JSONObject().put("amount", amount).put("beforeInfo", new JSONObject(info.toString()));
            GoldenBeansSupport.pause(interval);
            GoldenBeansActions.State result = new GoldenBeansActions().execute(key, counter, details,
                    action -> reconcile(entry, action), action -> {
                        JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.exchangeBeanOf(entry.bizType, entry.source, action.getInt("amount")));
                        if (GoldenBeansActions.explicitRejection(response)) return GoldenBeansActions.rejected();
                        if (GoldenBeansSupport.ok(response) && response.optInt("beanDelta", 0) > 0) {
                            // 先保留回执；额度和动作完成由执行器原子写入，恢复不会重复增加。
                            action.put("receipt", response);
                            GoldenBeansActions.checkpoint(action);
                            return GoldenBeansActions.confirmed(response.optInt("beanDelta"));
                        }
                        return GoldenBeansActions.unknown();
                    });
            if (result == GoldenBeansActions.State.CONFIRMED) {
                GoldenBeansRuntime.record("金豆[" + entry.alias + "]换豆已确认#日累计[" + GoldenBeansStatus.getIntFlagToday(counter) + "]");
                GoldenBeansSupport.pause(interval);
                goldenbeansRpcCall.pullOf(entry.bizType, entry.source, "JAR_INFO", "EXCHANGE_MANURE", "TASK_LIST");
            }
        } catch (Exception error) {
            GoldenBeansRuntime.rethrowIfStopped(error);
        }
    }

    private static GoldenBeansActions.Result reconcile(GoldenBeansEntry entry, JSONObject action) throws Exception {
        JSONObject receipt = action.optJSONObject("receipt");
        if (GoldenBeansSupport.ok(receipt) && receipt.optInt("beanDelta", 0) > 0) return GoldenBeansActions.confirmed(receipt.optInt("beanDelta"));
        JSONObject response = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(entry.bizType, entry.source, "JAR_INFO", "EXCHANGE_MANURE"));
        if (!GoldenBeansSupport.ok(response)) return GoldenBeansActions.unknown();
        // 当前协议仅返回余额及额度快照，不能把其它来源的余额变化归给本动作。
        // 没有本动作到账回执时继续保留待确认，禁止重新兑换。
        return GoldenBeansActions.unknown();
    }
}
