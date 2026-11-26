package io.github.zolarly.ukiyo.net

import java.io.IOException
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthTest {
    private fun jwt(payload: String) =
        "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".sig"

    @Test
    fun readsExpiry() {
        assertEquals(1764000000L, jwtExpiry(jwt("""{"sub":"1","exp":1764000000}""")))
    }

    @Test
    fun garbageIsExpired() {
        for (token in listOf("garbage", "a.!!!.c", jwt("""{"sub":"1"}"""))) {
            assertEquals(token, 0L, jwtExpiry(token))
        }
    }

    @Test
    fun parsesValidTokens() {
        val tokens = parseTokens(
            """{"access_token":"a","refresh_token":"r","username":"bob"}"""
        )
        assertEquals(Tokens("a", "r", "bob"), tokens)
    }

    @Test
    fun malformedBodyIsIOException() {
        assertThrows(IOException::class.java) { parseTokens("<html>") }
    }
}
