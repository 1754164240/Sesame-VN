package fansirsqi.xposed.sesame.task.antForest

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.random.Random

/** 无纸阅读流程，与 Android 缓存及网络实现分离，所有外部操作前后检查任务状态。 */
internal class ReadForestRunner(
    private val request: suspend (String, JSONObject?, String?, String?, String?) -> JSONObject,
    private val readState: (String) -> String?,
    private val writeState: (String, String) -> Unit,
    private val authCode: suspend () -> String?,
    private val day: String,
    private val checkpoint: suspend () -> Unit,
    private val pause: suspend () -> Unit = { delay(Random.nextLong(400, 1201)) },
    private val reportSeconds: () -> Int = { Random.nextInt(333, 1001) },
    private val maxChapters: Int = 120,
    private val onProgress: (Int, Int) -> Unit = { _, _ -> }
) {
    enum class Result { FULL, NO_BOOKS, NO_PROGRESS, NO_GAIN, LIMIT }

    var energyGained = 0
        private set
    private var token: String? = null

    suspend fun run(): Result {
        checkpoint()
        if (readState("done") == day) return Result.FULL
        token = readState("token")?.takeIf { it.isNotBlank() }
        if (token != null) {
            val valid = try {
                call("/authorization").optInt("code") == 2000
            } catch (e: ReadForestHttpException) {
                if (e.statusCode != 401 && e.statusCode != 403) throw e
                false
            }
            if (!valid) {
                save("token", "")
                token = null
            }
        }
        if (token == null) {
            checkpoint()
            val code = authCode()
            checkpoint()
            if (code.isNullOrBlank()) throw IOException("无纸阅读授权码为空")
            val login = data(call("/authorization", JSONObject().put("auth_code", code)
                .put("app_id", ReadForestRpcCall.APP_ID).put("cid", "7001")))
            token = login.optString("token").takeIf { it.isNotBlank() }
                ?: throw IOException("无纸阅读登录未返回令牌")
            save("token", token!!)
        }
        var progress = progress() ?: return Result.NO_PROGRESS
        if (progress.first >= progress.second) return complete()

        val finished = readState("finished")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        val skipped = (0 until finished.length()).map { finished.optString(it) }.toMutableSet()
        var index = if (readState("indexDay") == day) {
            readState("index")?.let { runCatching { JSONObject(it) }.getOrNull() }
        } else null
        var fetched = false
        var chapters = 0
        while (chapters < maxChapters) {
            checkpoint()
            if (index == null) {
                index = call("/index/index?type=1")
                requireSuccess(index)
                if (index.optJSONArray("data") == null) throw IOException("书城列表缺失")
                save("index", index.toString())
                save("indexDay", day)
                fetched = true
            }
            val book = selectBook(index, skipped)
            if (book == null) {
                if (!fetched) { index = null; continue }
                return Result.NO_BOOKS
            }
            val bookId = book.getString("book_id")
            skipped += bookId
            var chapterId: String? = readState("chapter_$bookId")?.takeIf { it.isNotBlank() }
            val visited = mutableSetOf<String>()
            var noGain = 0
            while (chapters < maxChapters) {
                val chapter = data(call("/book/chapter", book = bookId, chapter = chapterId))
                val id = chapter.optString("id")
                if (id.isBlank() || id == "null" || !visited.add(id)) return Result.NO_PROGRESS
                val next = chapter.optString("next_id").takeUnless { it.isBlank() || it == "null" || it == "0" }
                pause()
                val chapterValue: Any = id.toLongOrNull() ?: id
                requireSuccess(call("/user/updateWel", JSONObject().put("book_id", bookId)
                    .put("chapter_id", chapterValue).put("time", reportSeconds()), bookId, id))
                // 最近阅读只影响展示；失败仍允许读取服务端能量进度，但不吞掉取消。
                try {
                    requireSuccess(call("/book/set_read_recently", JSONObject().put("book_id", bookId)
                        .put("chapter_id", chapterValue).put("chapter_name", chapter.optString("name")), bookId, id))
                } catch (_: IOException) { }
                chapters++
                val updated = progress(bookId, id) ?: return Result.NO_PROGRESS
                val gain = updated.first - progress.first
                if (gain > 0) { energyGained += gain; noGain = 0 } else noGain++
                progress = updated
                if (progress.first >= progress.second) return complete()
                if (next == null) {
                    finished.put(bookId)
                    save("finished", finished.toString())
                    save("chapter_$bookId", "")
                    break
                }
                save("chapter_$bookId", next)
                if (noGain >= 6) return Result.NO_GAIN
                chapterId = next
            }
        }
        return Result.LIMIT
    }

    private suspend fun call(path: String, body: JSONObject? = null, book: String? = null, chapter: String? = null): JSONObject {
        checkpoint()
        val response = try {
            request(path, body, book, chapter, token)
        } catch (e: ReadForestHttpException) {
            if (e.statusCode == 401 || e.statusCode == 403) save("token", "")
            throw e
        }
        checkpoint()
        return response
    }

    private suspend fun progress(book: String? = null, chapter: String? = null): Pair<Int, Int>? {
        val value = data(call("/user/forestPro", book = book, chapter = chapter))
        val current = value.optString("current").toIntOrNull() ?: return null
        val total = value.optString("total").toIntOrNull() ?: return null
        if (current < 0 || total <= 0) return null
        onProgress(current, total)
        return current to total
    }

    private suspend fun complete(): Result {
        save("done", day)
        return Result.FULL
    }

    private suspend fun save(key: String, value: String) {
        checkpoint()
        writeState(key, value)
    }

    private fun requireSuccess(response: JSONObject) {
        if (response.optInt("code") != 2000) throw IOException("无纸阅读接口返回失败，业务码=${response.optInt("code", -1)}")
    }

    private fun data(response: JSONObject): JSONObject {
        requireSuccess(response)
        return response.optJSONObject("data") ?: throw IOException("无纸阅读接口数据缺失")
    }

    private fun selectBook(index: JSONObject, skipped: Set<String>): JSONObject? {
        val blocks = index.optJSONArray("data") ?: return null
        for (i in 0 until blocks.length()) {
            val books = blocks.optJSONObject(i)?.optJSONArray("block_resource") ?: continue
            for (j in 0 until books.length()) {
                val book = books.optJSONObject(j) ?: continue
                val id = book.optString("book_id")
                if (id.isNotBlank() && id != "null" && id !in skipped && book.optString("price") == "0.00") return book
            }
        }
        return null
    }
}
