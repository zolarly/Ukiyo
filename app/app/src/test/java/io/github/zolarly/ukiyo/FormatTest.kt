package io.github.zolarly.ukiyo

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    private val minute = 60_000L

    @Test
    fun remaining() {
        assertEquals("已过期", formatRemaining(0))
        assertEquals("已过期", formatRemaining(-5))
        assertEquals("还剩 1 分", formatRemaining(30_000))
        assertEquals("还剩 59 分", formatRemaining(59 * minute))
        assertEquals("还剩 1 小时 31 分", formatRemaining(90 * minute + 30_000))
        assertEquals("还剩 24 小时 0 分", formatRemaining(24 * 60 * minute))
    }

    @Test
    fun size() {
        assertEquals("未知大小", formatSize(-1))
        assertEquals("1 KB", formatSize(1024))
        assertEquals("1.5 MB", formatSize(3L shl 19))
    }
}
