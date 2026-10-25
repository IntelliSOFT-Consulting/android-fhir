package com.icl.surveillance.network

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRefresherTest {

    private val now = 1_800_000_000L

    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        return "$header.${encoder.encodeToString(payload.toByteArray())}.signature"
    }

    @Test
    fun `the expiry is read from the token`() {
        assertEquals(now, TokenRefresher.expiryOf(jwt("""{"sub":"u1","exp":$now,"iat":1}""")))
    }

    @Test
    fun `a token valid well beyond the margin is kept`() {
        val token = jwt("""{"exp":${now + 3600}}""")
        assertFalse(TokenRefresher.isExpiringSoon(token, nowSeconds = now))
    }

    @Test
    fun `an expired token or one about to expire is refreshed`() {
        assertTrue(TokenRefresher.isExpiringSoon(jwt("""{"exp":${now - 1}}"""), nowSeconds = now))
        assertTrue(TokenRefresher.isExpiringSoon(jwt("""{"exp":${now + 60}}"""), nowSeconds = now))
    }

    @Test
    fun `a token whose expiry cannot be read is refreshed`() {
        assertNull(TokenRefresher.expiryOf("not-a-jwt"))
        assertTrue(TokenRefresher.isExpiringSoon("not-a-jwt", nowSeconds = now))
        assertTrue(TokenRefresher.isExpiringSoon(jwt("""{"sub":"u1"}"""), nowSeconds = now))
    }
}
