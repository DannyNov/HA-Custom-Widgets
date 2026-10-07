package com.danila.hacustomwidgets.auth

import com.danila.hacustomwidgets.data.security.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class OAuthPolicyTest {
    private fun callback(query: String = "state=state&code=code") = OAuthPolicy.REDIRECT_URI + "?" + query
    @Test fun validCallbackAndAuthorization() {
        assertEquals("code", OAuthPolicy.callbackCode(callback(), "state", 100, 200))
        val url = OAuthPolicy.authorizationUrl("http://ha.local:8123", "state +").toHttpUrl()
        assertEquals("/auth/authorize", url.encodedPath)
        assertEquals("state +", url.queryParameter("state"))
        assertEquals(OAuthPolicy.CLIENT_ID, url.queryParameter("client_id"))
        assertEquals(OAuthPolicy.REDIRECT_URI, url.queryParameter("redirect_uri"))
        val client = OAuthPolicy.CLIENT_ID.toHttpUrl(); val redirect = OAuthPolicy.REDIRECT_URI.toHttpUrl()
        assertEquals(client.host, redirect.host); assertEquals(client.port, redirect.port)
    }
    @Test fun rejectsMissingOrWrongOrDuplicateState() {
        listOf("code=c", "state=bad&code=c", "state=state&state=state&code=c", "state=state&code=c&code=d").forEach {
            assertThrows(IllegalArgumentException::class.java) { OAuthPolicy.callbackCode(callback(it), "state", 100, 200) }
        }
    }
    @Test fun rejectsWrongHostSchemePathCredentialsFragmentAndExpiredFlow() {
        listOf(callback().replace("https:", "http:"), callback().replace("dannynov.github.io", "evil.example"),
            callback().replace("/auth/callback", "/auth/callback/"), callback().replace("https://", "https://user@"), callback() + "#fragment").forEach {
            assertThrows(IllegalArgumentException::class.java) { OAuthPolicy.callbackCode(it, "state", 100, 200) }
        }
        assertThrows(IllegalArgumentException::class.java) { OAuthPolicy.callbackCode(callback(), "state", 100, 700_000) }
        assertThrows(IllegalArgumentException::class.java) { OAuthPolicy.callbackCode(callback(), "state", 100, 99) }
    }
    @Test fun cancellationDoesNotExposeServerErrorDescription() {
        val error = assertThrows(java.io.IOException::class.java) {
            OAuthPolicy.callbackCode(callback("state=state&error=denied&error_description=SECRET"), "state", 100, 200)
        }
        assertFalse(error.toString().contains("SECRET"))
    }
    @Test fun normalizesAndRejectsUrlCredentials() {
        assertEquals("http://ha.local:8123", OAuthPolicy.normalizeUrl(" http://ha.local:8123/ "))
        listOf("https://user:pass@ha.local", "https://ha.local?token=a", "https://ha.local#fragment", "file:///a").forEach {
            assertThrows(IllegalArgumentException::class.java) { OAuthPolicy.normalizeUrl(it) }
        }
    }
    @Test fun randomStateAndRedactedModels() {
        val states = (1..100).map { OAuthPolicy.randomState() }
        assertEquals(100, states.distinct().size); assertTrue(states.all { it.length >= 43 })
        assertFalse(oauthConnection().toString().contains("secret"))
        assertFalse(ServerMetadata(webhookId = "SECRET").toString().contains("SECRET"))
    }
}
