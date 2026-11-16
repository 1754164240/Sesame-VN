package fansirsqi.xposed.sesame.task.goldenbeans;

import org.json.JSONObject;
import java.util.UUID;

/** 持久化动作执行器：发送前落身份，恢复只查证，明确拒绝才允许新动作。 */
public final class GoldenBeansActions {
    public enum State { CONFIRMED, WAITING, REJECTED }
    public static final class Result {
        final State state;
        final int credit;
        private Result(State state, int credit) { this.state = state; this.credit = credit; }
    }
    public interface Probe { Result check(JSONObject action) throws Exception; }
    public interface Send { Result send(JSONObject action) throws Exception; }
    public static Result confirmed(int credit) { return new Result(State.CONFIRMED, Math.max(credit, 0)); }
    public static Result unknown() { return new Result(State.WAITING, 0); }
    public static Result rejected() { return new Result(State.REJECTED, 0); }
    public static boolean explicitRejection(JSONObject response) {
        if (response == null || GoldenBeansSupport.ok(response)) return false;
        String code = response.optString("resultCode", response.optString("code", ""));
        return "ILLEGAL_PARAMETER".equals(code) || "PARAM_ILLEGAL".equals(code) || "INVALID_PARAM".equals(code)
                || "NO_QUOTA".equals(code) || "QUOTA_NOT_ENOUGH".equals(code) || "NOT_ELIGIBLE".equals(code)
                || "INSUFFICIENT_BALANCE".equals(code) || "400000040".equals(code);
    }
    static JSONObject pending(String key) { return GoldenBeansJournal.child(GoldenBeansJournal.read(), "actions").optJSONObject(key); }
    static void checkpoint(JSONObject action) {
        synchronized (GoldenBeansJournal.class) {
            try { JSONObject root = GoldenBeansJournal.read(); GoldenBeansJournal.child(root, "actions").put(action.getString("key"), action); GoldenBeansJournal.write(root); }
            catch (Exception error) { GoldenBeansRuntime.rethrowIfStopped(error); throw new IllegalStateException(error); }
        }
    }
    public State execute(String key, String counter, JSONObject details, Probe probe, Send send) {
        synchronized (GoldenBeansJournal.class) {
            try {
                JSONObject root = GoldenBeansJournal.read();
                JSONObject actions = GoldenBeansJournal.child(root, "actions");
                JSONObject action = actions.optJSONObject(key);
                boolean recovery = action != null;
                if (action != null && "CONFIRMED".equals(action.optString("state"))) return State.CONFIRMED;
                if (action == null) {
                    action = new JSONObject(details.toString()).put("key", key).put("actionId", UUID.randomUUID().toString()).put("state", "PENDING");
                    actions.put(key, action);
                    GoldenBeansJournal.write(root);
                }
                Result result;
                if (recovery) result = probe.check(action);
                else {
                    result = send.send(action);
                    if (result.state == State.WAITING) result = probe.check(action);
                }
                root = GoldenBeansJournal.read();
                actions = GoldenBeansJournal.child(root, "actions");
                if (result.state == State.REJECTED) actions.remove(key);
                if (result.state == State.CONFIRMED) {
                    action.put("state", "CONFIRMED"); actions.put(key, action);
                    if (counter != null && !counter.isEmpty()) {
                        JSONObject counts = GoldenBeansJournal.child(root, "counts");
                        counts.put(counter, (int) Math.min((long) counts.optInt(counter, 0) + result.credit, Integer.MAX_VALUE));
                    }
                }
                GoldenBeansJournal.write(root);
                return result.state;
            } catch (Exception error) {
                GoldenBeansRuntime.rethrowIfStopped(error);
                return State.WAITING;
            }
        }
    }
}
