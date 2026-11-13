package fansirsqi.xposed.sesame.task.antForest

import android.os.Build
import fansirsqi.xposed.sesame.hook.ApplicationHook
import fansirsqi.xposed.sesame.hook.RequestManager
import fansirsqi.xposed.sesame.hook.internal.AuthCodeHelper
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.maps.UserMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 森林无纸阅读：按账号隔离缓存，真实服务端进度满额才记录当日完成。 */
object ReadForestTask {
    private const val TAG = "ReadForest"
    private val authorization = ReadForestAuthorization()

    suspend fun execute() = withContext(Dispatchers.IO) {
        val context = ApplicationHook.appContext ?: return@withContext
        val uid = UserMap.currentUid?.takeIf { it.isNotBlank() } ?: return@withContext
        fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val day = today()
        val preferences = context.getSharedPreferences("sesame_forest_read", 0)
        suspend fun checkpoint() {
            currentCoroutineContext().ensureActive()
            if (ApplicationHook.offline || RequestManager.isVerificationPaused() || UserMap.currentUid != uid || today() != day) {
                throw CancellationException("无纸阅读因暂停、账号或日期变化停止")
            }
        }
        val version = ApplicationHook.alipayVersion.versionString
        val ua = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/112.0.0.0 Mobile Safari/537.36 " +
            "NebulaSDK/1.8.100112 Nebula AlipayClient/$version AliApp(AP/$version) Language/zh-Hans"
        val client = ReadForestRpcCall(ua)
        val runner = ReadForestRunner(
            request = client::request,
            readState = { preferences.getString("${uid}_$it", null) },
            writeState = { key, value -> preferences.edit().putString("${uid}_$key", value).apply() },
            authCode = { authorization.await { AuthCodeHelper.getAuthCode(ReadForestRpcCall.APP_ID) } },
            day = day, checkpoint = ::checkpoint,
            onProgress = { current, total -> Log.record(TAG, "无纸阅读进度：$current/${total}g") }
        )
        try {
            checkpoint()
            if (preferences.getString("${uid}_done", null) == day) return@withContext
            Log.record(TAG, "开始无纸阅读")
            val result = withTimeoutOrNull(120_000L) { runner.run() }
            val reason = when (result) {
                ReadForestRunner.Result.FULL -> "今日能量已满额"
                ReadForestRunner.Result.NO_BOOKS -> "暂无未读免费书籍"
                ReadForestRunner.Result.NO_PROGRESS -> "有效进度或章节缺失"
                ReadForestRunner.Result.NO_GAIN -> "连续六章无收益，等待下轮"
                ReadForestRunner.Result.LIMIT -> "达到本轮章节上限，等待下轮"
                null -> "达到本轮两分钟时限，等待下轮"
            }
            Log.record(TAG, "无纸阅读结束：$reason，本轮获得${runner.energyGained}g")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.record(TAG, "无纸阅读请求失败，本轮结束，下轮重试；已确认获得${runner.energyGained}g")
        }
    }
}
