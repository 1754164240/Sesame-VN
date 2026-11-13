package fansirsqi.xposed.sesame.hook

import java.util.UUID

/** 暂停状态仅属于当前进程；重载 Bridge 不清空，重启进程不继承。 */
class VerificationPauseState {
    private val tokens = mutableMapOf<String, String>()

    @Synchronized
    fun pause(userId: String): String = tokens.getOrPut(userId) { UUID.randomUUID().toString() }

    @Synchronized
    fun tokenFor(userId: String?): String? = tokens[userId]

    @Synchronized
    fun finish(userId: String, token: String): Boolean {
        if (tokens[userId] != token) return false
        tokens.remove(userId)
        return true
    }
}
