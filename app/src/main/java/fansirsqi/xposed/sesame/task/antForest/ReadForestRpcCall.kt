package fansirsqi.xposed.sesame.task.antForest

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 小程序自有网关；取消任务时同时取消在途 HTTP 请求。 */
internal class ReadForestRpcCall(private val userAgent: String, private val host: String = HOST) {
    suspend fun request(path: String, body: JSONObject?, book: String?, chapter: String?, token: String?): JSONObject {
        val url = (host + path).toHttpUrl().newBuilder().apply {
            if (path == "/book/chapter") {
                addQueryParameter("book_id", book)
                if (!chapter.isNullOrBlank()) {
                    addQueryParameter("chapter_id", chapter)
                    addQueryParameter("chapter_num", "")
                    addQueryParameter("source", "")
                }
            }
        }.build()
        val referer = buildString {
            append("https://$APP_ID.hybrid.alipay-eco.com/$APP_ID/$VERSION/index.html")
            if (book == null) append("#pages/index/index") else {
                append("#moduleA/pages/chapter/index?__appxPageId=1&bid=$book")
                if (chapter != null) append("&cid=$chapter")
            }
        }
        val req = Request.Builder().url(url)
            .header("content-type", "application/json;charset=UTF-8")
            .header("referer", referer).header("user-agent", userAgent)
            .header("x-requested-with", "com.eg.android.AlipayGphone")
            .header("x-mass-tappid", APP_ID).header("x-release-type", "ONLINE")
            .header("accept-language", "zh-CN,zh;q=0.9")
            .apply {
                if (!token.isNullOrBlank()) header("authorization", "Bearer $token")
                if (body != null || path == "/authorization") {
                    post((body?.toString() ?: "{}").toRequestBody(JSON))
                } else get()
            }.build()
        val client = if (path == "/book/set_read_recently") shortClient else client
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 不向上层暴露带完整 URL、授权头或响应正文的网络异常。
                    if (!continuation.isCancelled) continuation.resumeWithException(IOException("无纸阅读网络请求失败"))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw ReadForestHttpException(it.code)
                            val text = it.body.string()
                            try { JSONObject(text) } catch (_: Exception) { throw IOException("无纸阅读响应格式错误") }
                        }
                        if (!continuation.isCancelled) continuation.resume(result)
                    } catch (e: IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    companion object {
        const val APP_ID = "2021003114652763"
        private const val VERSION = "0.2.2410251510.53"
        private const val HOST = "https://m.zhangwenwh.com/api"
        private val JSON = "application/json;charset=UTF-8".toMediaType()
        private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            // 上报请求不做自动重放，避免响应丢失时重复记账。
            .retryOnConnectionFailure(false).build()
        private val shortClient = client.newBuilder().readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS).build()
    }
}

internal class ReadForestHttpException(val statusCode: Int) : IOException("无纸阅读HTTP状态=$statusCode")
