package fansirsqi.xposed.sesame.hook.captcha

import org.junit.Assert.*
import org.junit.Test

class CaptchaCallbackStoreTest {
    @Test fun `callback保留到异步响应而不依赖RPC返回时机`() {
        val store = CaptchaCallbackStore<String>(2, 100)
        val callback = Any()
        store.put(callback, "request", 0)
        assertEquals("request", store.get(callback, 50))
        assertEquals("request", store.remove(callback, 51))
        assertNull(store.remove(callback, 52))
    }

    @Test fun `容量过期和相等但不同实例不会串联请求`() {
        val store = CaptchaCallbackStore<Int>(2, 100)
        val a = String(charArrayOf('a'))
        val sameValue = String(charArrayOf('a'))
        store.put(a, 1, 0)
        assertNull(store.get(sameValue, 1))
        val b = Any()
        store.put(b, 2, 1)
        store.put(Any(), 3, 2)
        assertNull(store.get(a, 3))
        assertNull(store.get(b, 102))
    }
}
