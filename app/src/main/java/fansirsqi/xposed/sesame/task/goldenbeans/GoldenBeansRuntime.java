package fansirsqi.xposed.sesame.task.goldenbeans;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.IntConsumer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import fansirsqi.xposed.sesame.hook.ApplicationHook;
import fansirsqi.xposed.sesame.hook.RequestManager;
import fansirsqi.xposed.sesame.util.maps.UserMap;

/** 捕获账号、协程和日期。跨日旧轮次也必须停止，不能把旧请求记入新日。 */
public final class GoldenBeansRuntime {
    private static final ThreadLocal<Session> SESSION = new ThreadLocal<>();
    private static Supplier<String> testUid;
    private static Supplier<String> testDay;
    private static BooleanSupplier testAvailable;
    private static IntConsumer testSleep;
    private static final class Session {
        final String uid;
        final String day;
        final BooleanSupplier active;
        Session(BooleanSupplier active) { this.uid = currentUid(); this.day = currentDay(); this.active = active; }
    }
    public static void installForTest(Supplier<String> uid, Supplier<String> day, BooleanSupplier available, IntConsumer sleep) {
        testUid = uid; testDay = day; testAvailable = available; testSleep = sleep;
    }
    public static void clearTestEnvironment() { testUid = null; testDay = null; testAvailable = null; testSleep = null; }
    private static String currentUid() { return testUid != null ? testUid.get() : UserMap.INSTANCE.getCurrentUid(); }
    private static String currentDay() { return testDay != null ? testDay.get() : new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()); }
    public static void begin(BooleanSupplier active) { SESSION.set(new Session(active)); check(); }
    public static void end() { SESSION.remove(); }
    public static void check() {
        Session s = SESSION.get();
        boolean available = testAvailable != null ? testAvailable.getAsBoolean() : !ApplicationHook.offline && !RequestManager.isVerificationPaused();
        if (s == null || !GoldenBeansPolicy.shouldContinue(s.uid, currentUid(),
                s.active.getAsBoolean() && !Thread.currentThread().isInterrupted(), !available, false)
                || !s.day.equals(currentDay())) throw new CancellationException("金豆任务账号、日期或执行身份已失效");
    }
    public static String uid() { check(); return SESSION.get().uid; }
    public static String day() { check(); return SESSION.get().day; }
    public static void pause(int millis) {
        check();
        if (testSleep != null) testSleep.accept(millis);
        else try { Thread.sleep(Math.max(millis, 200)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException("金豆等待已取消"); }
        check();
    }
    public static void rethrowIfStopped(Throwable error) {
        if (error instanceof CancellationException) throw (CancellationException) error;
        check();
    }
    static void record(String message) { if (testUid == null) fansirsqi.xposed.sesame.util.Log.record(message); }
    static void record(String tag, String message) { if (testUid == null) fansirsqi.xposed.sesame.util.Log.record(tag, message); }
    static void error(String message, Throwable error) { if (testUid == null) fansirsqi.xposed.sesame.util.Log.printStackTrace(message, error); }

}
