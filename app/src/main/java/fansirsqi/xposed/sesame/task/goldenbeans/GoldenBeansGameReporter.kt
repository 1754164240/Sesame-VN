package fansirsqi.xposed.sesame.task.goldenbeans

import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.hook.internal.AlipayMiniMarkHelper
import fansirsqi.xposed.sesame.hook.internal.AuthCodeHelper
import fansirsqi.xposed.sesame.util.GameTask
import fansirsqi.xposed.sesame.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 金豆渠道同步上报，不启动脱离任务生命周期的后台线程，不跨账号缓存令牌。 */
class GoldenBeansGameReporter private constructor(private val game: GameTask) {
    val title: String get() = game.title

    var isRejected: Boolean = false
        private set

    fun reportSync(scene: String, count: Int, channel: String, actionId: String, reached: java.util.function.BooleanSupplier): Int {
        isRejected = false
        if (count <= 0 || channel != "goldenbean") { isRejected = true; return 0 }
        GoldenBeansRuntime.check()
        val loginBody = body().put("pf", "zfb")
        if (testTransport == null) loginBody.put("code", AuthCodeHelper.getAuthCode(game.appId))
        val loginResponse = send("game/login", loginBody, null)
        val token = loginResponse?.takeIf { it.optInt("code") == 1 }?.optJSONObject("data")?.optString("token").orEmpty()
        if (token.isEmpty()) {
            // 登录本身不消耗游戏权益，未进入上报阶段可以安全解除待确认。
            isRejected = true
            return 0
        }
        var successes = 0
        val maximum = (count.toLong() * (game.requestsPerEgg + 1)).coerceAtMost(64).toInt()
        repeat(maximum) {
            GoldenBeansRuntime.check()
            val report = body().put("reqId", "$actionId-$it").put("action_code", game.action).put("action_finish_channel", channel)
            val response = send("zfb/taskReport", report, token)
            if (response?.optInt("code", -1) != 1) {
                isRejected = successes == 0 && response?.has("code") == true && response.optInt("code") == 0
                return successes
            }
            successes++
            // 每次成功上报后都回查，权益已经推进就立即停止，查询失败通过异常终止本批。
            if (reached.asBoolean) return successes
            if (it + 1 < maximum) GoldenBeansSupport.pause(1200)
        }
        GoldenBeansRuntime.record("金豆乐园", "$scene 上报完成[$successes]")
        return successes
    }

    private fun body() = JSONObject().put("v", game.version).put("version", game.version)
        .put("reqId", "${System.currentTimeMillis()}_${(10..99).random()}").put("gid", game.gid)

    private fun send(path: String, body: JSONObject, token: String?): JSONObject? {
        GoldenBeansRuntime.check()
        testTransport?.let { return it.apply(path, body) }
        val connection = URL("https://gamesapi2.aslk2018.com/v2/$path").openConnection() as HttpURLConnection
        try {
            connection.apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("alipayMiniMark", AlipayMiniMarkHelper.getAlipayMiniMark(game.appId, game.version))
                setRequestProperty("x-release-type", "ONLINE")
                val version = ApplicationHook.alipayVersion
                setRequestProperty("User-Agent", "${System.getProperty("http.agent").orEmpty()} NebulaSDK/1.8.100112 Nebula AliApp(AP/$version) AlipayClient/$version")
                setRequestProperty("referer", "https://${game.appId}.hybrid.alipay-eco.com/${game.appId}/${game.version}/index.html")
                if (!token.isNullOrEmpty()) setRequestProperty("authorization", token)
            }
            GoldenBeansRuntime.check()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            GoldenBeansRuntime.check()
            return if (response.isEmpty()) null else JSONObject(response)
        } catch (error: Exception) {
            GoldenBeansRuntime.rethrowIfStopped(error)
            GoldenBeansRuntime.record("金豆游戏上报失败: ${error.message}")
            return null
        } finally { connection.disconnect() }
    }

    companion object {
        private var testTransport: java.util.function.BiFunction<String, JSONObject, JSONObject?>? = null
        @JvmStatic fun setTransportForTest(transport: java.util.function.BiFunction<String, JSONObject, JSONObject?>?) { testTransport = transport }
        @JvmStatic fun matchAppId(appId: String): GoldenBeansGameReporter? =
            GameTask.entries.firstOrNull { it.appId == appId }?.let(::GoldenBeansGameReporter)
    }
}
