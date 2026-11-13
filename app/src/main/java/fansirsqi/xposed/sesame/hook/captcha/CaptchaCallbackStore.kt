package fansirsqi.xposed.sesame.hook.captcha

/** 按对象身份关联异步回调，避免 equals 实现和无限持有回调导致串联或泄漏。 */
class CaptchaCallbackStore<T>(private val capacity: Int = 64, private val ttlMillis: Long = 120_000) {
    private class IdentityKey(val value: Any) {
        override fun equals(other: Any?) = other is IdentityKey && value === other.value
        override fun hashCode() = System.identityHashCode(value)
    }
    private data class Entry<T>(val value: T, val createdAt: Long)
    private val entries = LinkedHashMap<IdentityKey, Entry<T>>()

    init { require(capacity > 0 && ttlMillis > 0) }

    @Synchronized fun put(callback: Any, value: T, now: Long) {
        expire(now)
        entries[IdentityKey(callback)] = Entry(value, now)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized fun get(callback: Any, now: Long): T? {
        expire(now)
        return entries[IdentityKey(callback)]?.value
    }

    @Synchronized fun remove(callback: Any, now: Long): T? {
        expire(now)
        return entries.remove(IdentityKey(callback))?.value
    }

    @Synchronized fun clear() = entries.clear()

    private fun expire(now: Long) {
        entries.entries.removeAll { now < it.value.createdAt || now - it.value.createdAt >= ttlMillis }
    }
}
