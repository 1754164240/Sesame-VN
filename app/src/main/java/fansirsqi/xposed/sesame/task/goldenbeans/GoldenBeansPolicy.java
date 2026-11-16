package fansirsqi.xposed.sesame.task.goldenbeans;

/** 金豆库存、额度与服务端确认的纯策略，使用长整型避免汇率乘法溢出。 */
public final class GoldenBeansPolicy {
    private GoldenBeansPolicy() {}
    public static int exchangeAmount(int current, int effective, int rate, int quota, int minimum, int daily, int done) {
        if (rate <= 0 || minimum <= 0) return 0;
        long server = Math.min((long) quota, Math.min((long) current * rate, (long) effective * rate));
        long remaining = daily > 0 ? Math.max((long) daily - Math.max(done, 0), 0) : Long.MAX_VALUE;
        long amount = Math.max(0, Math.min(server, remaining));
        return amount < minimum ? 0 : (int) Math.min(amount, Integer.MAX_VALUE);
    }
    public static boolean canExchange(int cost, int jars, boolean cash, int dayLeft, int limit, int done) {
        return cost >= 0 && jars >= cost && !cash && dayLeft != 0 && (limit <= 0 || done < limit);
    }
    public static boolean rewardConfirmed(String state) {
        return "RECEIVED".equals(state) || "DONE".equals(state) || "HAS_RECEIVED".equals(state);
    }
    public static boolean shouldContinue(String started, String current, boolean active, boolean offline, boolean paused) {
        return started != null && !started.isEmpty() && started.equals(current) && active && !offline && !paused;
    }
}
