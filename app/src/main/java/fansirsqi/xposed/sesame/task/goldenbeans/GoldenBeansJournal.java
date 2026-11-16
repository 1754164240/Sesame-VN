package fansirsqi.xposed.sesame.task.goldenbeans;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;
import java.util.function.BiConsumer;

/** 金豆专用账号日账本，动作确认与额度同一快照原子落盘，不依赖公共 Status 的清日时序。 */
public final class GoldenBeansJournal {
    private static Function<String, String> testRead;
    private static BiConsumer<String, String> testWrite;
    private GoldenBeansJournal() {}
    public static void installForTest(Function<String, String> read, BiConsumer<String, String> write) { testRead = read; testWrite = write; }
    public static void clearTestStorage() { testRead = null; testWrite = null; }
    private static String key() { return GoldenBeansRuntime.uid() + "/" + GoldenBeansRuntime.day(); }
    private static File file() {
        File file = fansirsqi.xposed.sesame.util.Files.getTargetFileofUser(GoldenBeansRuntime.uid(), "GoldenBeans-" + GoldenBeansRuntime.day() + ".json");
        if (file == null) throw new IllegalStateException("金豆账号存储不可用");
        return file;
    }
    static synchronized JSONObject read() {
        GoldenBeansRuntime.check();
        try {
            String text;
            if (testRead != null) text = testRead.apply(key());
            else { File target = file(); text = target.isFile() ? new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8) : ""; }
            return text == null || text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception error) { throw new IllegalStateException("金豆账本读取失败，停止发送动作", error); }
    }
    static synchronized void write(JSONObject value) {
        GoldenBeansRuntime.check();
        try {
            if (testWrite != null) { testWrite.accept(key(), value.toString()); return; }
            File target = file();
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("金豆目录不可写");
            File temporary = new File(target.getPath() + ".tmp");
            Files.write(temporary.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
            GoldenBeansRuntime.check();
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception error) {
            GoldenBeansRuntime.rethrowIfStopped(error);
            throw new IllegalStateException("金豆账本保存失败，停止发送动作", error);
        }
    }
    static JSONObject child(JSONObject root, String key) {
        JSONObject value = root.optJSONObject(key);
        if (value == null) { value = new JSONObject(); try { root.put(key, value); } catch (Exception error) { throw new IllegalStateException(error); } }
        return value;
    }
}
