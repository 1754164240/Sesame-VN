package fansirsqi.xposed.sesame.hook.captcha

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class CaptchaRequestData(val bizNo: String, val scene: String)

sealed interface CaptchaReply {
    data class Challenge(val bizNo: String, val fingerprint: String, val interaction: String) : CaptchaReply
    data class Passed(val bizNo: String) : CaptchaReply
}

/** 只提取会话字段，不持有完整请求、令牌或安全参数。 */
object CaptchaProtocol {
    const val METHOD = "alipay.security.antcaptcha.verify"

    fun parseRequest(raw: String): CaptchaRequestData = runCatching {
        var value: Any = JSONObject(raw)
        repeat(4) {
            value = when (val current = value) {
                is JSONObject -> current.opt("requestData") ?: current
                is JSONArray -> current.opt(0) ?: JSONObject()
                is String -> if (current.trimStart().startsWith("[")) JSONArray(current) else JSONObject(current)
                else -> JSONObject()
            }
        }
        val obj = value as? JSONObject ?: JSONObject()
        CaptchaRequestData(obj.optString("bizNo"), obj.optString("scene", obj.optString("interaction")))
    }.getOrDefault(CaptchaRequestData("", ""))

    fun parseReply(raw: String): CaptchaReply? = runCatching {
        val root = JSONObject(raw)
        if (root.optString("errorCode") != "00000") return null
        val data = root.optJSONObject("data") ?: return null
        when (data.optString("result")) {
            "pass" -> CaptchaReply.Passed(data.optString("bizNo"))
            "captcha" -> {
                val bizNo = data.optString("bizNo")
                if (bizNo.isBlank()) return null
                val config = data.optJSONObject("captchaConfig") ?: return null
                CaptchaReply.Challenge(bizNo, fingerprint(config.toString()), config.optString("interaction"))
            }
            else -> null
        }
    }.getOrNull()

    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
