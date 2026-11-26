package io.github.zolarly.ukiyo.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkTest {
    private val token = "AbCdEfGhIjKlMnOpQrSt-_"

    @Test
    fun findsLinkInsideChatText() {
        assertEquals(
            ShareLink("http://192.168.1.100:8090", token),
            findShareLink("给你发了照片 http://192.168.1.100:8090/s/$token 24小时有效"),
        )
    }

    @Test
    fun stopsAtChinesePunctuation() {
        assertEquals(ShareLink("https://ukiyo.example.com", token), findShareLink("https://ukiyo.example.com/s/$token。"))
    }

    @Test
    fun acceptsTicketedPageLink() {
        assertEquals(token, findShareLink("https://ukiyo.example.com/s/$token?ticket=abc")?.token)
    }

    @Test
    fun rejectsWrongTokenLength() {
        assertNull(findShareLink("https://ukiyo.example.com/s/${token.drop(1)}"))
        assertNull(findShareLink("https://ukiyo.example.com/s/${token}x"))
    }

    @Test
    fun rejectsTextWithoutLink() {
        assertNull(findShareLink("今晚吃什么"))
    }

    @Test
    fun apiUrl() {
        assertEquals("http://h:8090/api/shares/$token", ShareLink("http://h:8090", token).apiUrl)
    }
}
