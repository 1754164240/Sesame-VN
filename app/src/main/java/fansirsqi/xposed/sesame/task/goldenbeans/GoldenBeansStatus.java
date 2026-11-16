package fansirsqi.xposed.sesame.task.goldenbeans;

import org.json.JSONObject;

/** 日状态与动作共用账号日期账本，读写前都验证会话身份。 */
final class GoldenBeansStatus {
    static boolean hasFlagToday(String key) { synchronized (GoldenBeansJournal.class) { return GoldenBeansJournal.child(GoldenBeansJournal.read(), "flags").optBoolean(key, false); } }
    static void flagToday(String key) {
        synchronized (GoldenBeansJournal.class) {
            try { JSONObject root = GoldenBeansJournal.read(); GoldenBeansJournal.child(root, "flags").put(key, true); GoldenBeansJournal.write(root); }
            catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        }
    }
    static int getIntFlagToday(String key) { synchronized (GoldenBeansJournal.class) { return GoldenBeansJournal.child(GoldenBeansJournal.read(), "counts").optInt(key, 0); } }
    static void setIntFlagToday(String key, int value) {
        synchronized (GoldenBeansJournal.class) {
            try { JSONObject root = GoldenBeansJournal.read(); GoldenBeansJournal.child(root, "counts").put(key, value); GoldenBeansJournal.write(root); }
            catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        }
    }
}
