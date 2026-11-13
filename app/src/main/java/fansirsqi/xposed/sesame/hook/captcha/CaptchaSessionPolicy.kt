package fansirsqi.xposed.sesame.hook.captcha

data class CaptchaSession(
    val id: Long,
    val userId: String,
    val bizNo: String,
    val fingerprint: String,
    val pageKey: Int,
    val pauseToken: String,
    val createdAt: Long
)

data class CaptchaBoundRequest(
    val sessionId: Long?, val userId: String, val bizNo: String,
    val pageKey: Int, val pauseToken: String?, val createdAt: Long
)

/** 会话状态不依赖 Android，可验证乱序、刷新和恢复期间的身份变化。 */
class CaptchaSessionPolicy(private val ttlMillis: Long = 300_000) {
    private var serial = 0L
    private var lastChallengeSequence = -1L
    private var active: CaptchaSession? = null
    private var consumed = false
    private var budgetScope: Pair<String, String>? = null

    @Synchronized fun beginChallenge(
        userId: String, bizNo: String, fingerprint: String, pageKey: Int,
        pauseToken: String, sequence: Long, now: Long, observedAt: Long = now
    ): CaptchaSession? {
        val scope = userId to pauseToken
        // 按响应被观察的时间划分请求先后，队列等待不能让旧挑战重新获得有效期。
        if (observedAt > now || now - observedAt >= ttlMillis) return null
        if (userId.isBlank() || pauseToken.isBlank() || (budgetScope == scope && sequence < lastChallengeSequence)) return null
        if (budgetScope != scope) {
            budgetScope = scope
        }
        lastChallengeSequence = sequence
        val previous = active
        if (!consumed && previous?.userId == userId && previous.pauseToken == pauseToken &&
            previous.bizNo == bizNo && previous.fingerprint == fingerprint && previous.pageKey == pageKey &&
            now >= previous.createdAt && now - previous.createdAt < ttlMillis) return previous
        return CaptchaSession(++serial, userId, bizNo, fingerprint, pageKey, pauseToken, observedAt).also {
            active = it
            consumed = false
        }
    }

    @Synchronized fun bindRequest(userId: String, bizNo: String, pageKey: Int, pauseToken: String?, now: Long): CaptchaBoundRequest {
        val session = active?.takeIf {
            !consumed && it.userId == userId && it.bizNo == bizNo && bizNo.isNotBlank() &&
                it.pageKey == pageKey && it.pauseToken == pauseToken && now >= it.createdAt && now - it.createdAt < ttlMillis
        }
        return CaptchaBoundRequest(session?.id, userId, bizNo, pageKey, pauseToken, now)
    }

    @Synchronized fun acceptPass(request: CaptchaBoundRequest, currentUserId: String?, pauseToken: String?, now: Long): CaptchaSession? {
        val session = active ?: return null
        if (consumed || request.sessionId != session.id || request.userId != currentUserId ||
            request.userId != session.userId || request.bizNo != session.bizNo ||
            request.pageKey != session.pageKey || request.pauseToken != pauseToken ||
            session.pauseToken != pauseToken || now < request.createdAt || now < session.createdAt ||
            now - session.createdAt >= ttlMillis || now - request.createdAt >= 120_000) return null
        consumed = true
        return session
    }

    @Synchronized fun isCurrent(sessionId: Long, userId: String?, pauseToken: String?): Boolean =
        active?.let { it.id == sessionId && it.userId == userId && it.pauseToken == pauseToken } == true

    @Synchronized fun current(): CaptchaSession? = active
    @Synchronized fun isStaleChallenge(sequence: Long): Boolean = sequence <= lastChallengeSequence

}
