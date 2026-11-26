package io.github.zolarly.ukiyo.net

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorsTest {
    @Test
    fun expiredLoginLeadsToLogin() {
        assertEquals(Fix.LOGIN, describeError(ApiException(401, "未登录或登录已过期")).fix)
    }

    @Test
    fun goneShareIsDestroyed() {
        val destroyed = ErrorInfo("链接已销毁", Fix.NONE)
        assertEquals(destroyed, describeError(ApiException(404, "404 page not found")))
        assertEquals(destroyed, describeError(ApiException(403, "invalid or expired ticket")))
    }

    @Test
    fun viewsUsedUp() {
        assertEquals(ErrorInfo("查看次数已用完", Fix.NONE), describeError(ApiException(410, "views used up")))
    }

    @Test
    fun limitsCannotBeRetried() {
        assertEquals(Fix.NONE, describeError(ApiException(413, "upload too large")).fix)
        assertEquals(Fix.NONE, describeError(ApiException(429, "daily upload quota used up")).fix)
    }

    @Test
    fun storageFullCanBeRetried() {
        assertEquals(Fix.RETRY, describeError(ApiException(507, "storage full")).fix)
    }

    @Test
    fun otherServerErrorShowsCode() {
        val info = describeError(ApiException(500, "server error"))
        assertEquals(Fix.RETRY, info.fix)
        assertTrue(info.message.contains("500"))
    }

    @Test
    fun networkErrorCanBeRetried() {
        val info = describeError(SocketTimeoutException("timeout"))
        assertEquals(Fix.RETRY, info.fix)
        assertTrue(info.message.startsWith("网络问题"))
    }
}
