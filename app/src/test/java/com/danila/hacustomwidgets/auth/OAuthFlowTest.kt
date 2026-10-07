package com.danila.hacustomwidgets.auth

import com.danila.hacustomwidgets.data.security.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class OAuthFlowTest {
    private fun tokens() = MockResponse().setBody("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":1800,"token_type":"Bearer"}""")
    private fun callback(authorize: String, error: Boolean = false): String {
        val state = authorize.toHttpUrl().queryParameter("state")!!
        return OAuthPolicy.REDIRECT_URI + "?state=$state&" + if (error) "error=access_denied" else "code=auth-code"
    }
    private fun flow(store: AuthTestStore, transport: OAuthTransport, enrich: (HomeAssistantConnection, String) -> HomeAssistantConnection = { c, _ -> c }) =
        HomeAssistantOAuth(store, transport, HAConnectionManager(store, probe = {}), enrich)

    @Test fun successExchangesFormWithExactClientIdAndConsumesCallback() {
        MockWebServer().use { server ->
            server.enqueue(tokens()); val store = AuthTestStore(); val auth = flow(store, OAuthTransport())
            val redirect = callback(auth.begin(server.url("/").toString()))
            auth.complete(redirect)
            val request = server.takeRequest(); assertEquals("POST", request.method); assertEquals("/auth/token", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("grant_type=authorization_code")); assertTrue(body.contains("code=auth-code"))
            assertTrue(body.contains("client_id=https%3A%2F%2Fdannynov.github.io%2FHA-Custom-Widgets%2F"))
            assertEquals("new-refresh", store.load()!!.refreshToken)
            assertThrows(java.io.IOException::class.java) { auth.complete(redirect) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun successfulMigrationAndFailedMigrationPreserveLegacyUntilSuccess() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val old = HomeAssistantConnection(base, "LLAT"); val store = AuthTestStore(old)
            server.enqueue(MockResponse().setResponseCode(400).setBody("secret-server-response"))
            val auth = flow(store, OAuthTransport())
            val failed = callback(auth.begin(base)); assertThrows(java.io.IOException::class.java) { auth.complete(failed) }
            assertEquals("LLAT", store.load()!!.token)
            server.enqueue(tokens()); auth.complete(callback(auth.begin(base)))
            assertTrue(store.load()!!.isOAuth)
        }
    }
    @Test fun metadataFailureDoesNotReplaceLegacyAndRevokesNewGrant() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/'); val old = HomeAssistantConnection(base, "LLAT")
            val store = AuthTestStore(old); server.enqueue(tokens()); server.enqueue(MockResponse())
            val auth = flow(store, OAuthTransport()) { _, _ -> throw java.io.IOException("API unavailable") }
            assertThrows(java.io.IOException::class.java) { auth.complete(callback(auth.begin(base))) }
            assertEquals("LLAT", store.load()!!.token); server.takeRequest()
            assertEquals("/auth/revoke", server.takeRequest().path)
        }
    }
    @Test fun cancelAndInvalidStateDoNotTouchLegacyOrExchangeTokens() {
        val old = HomeAssistantConnection("https://ha.example.com", "LLAT"); val store = AuthTestStore(old)
        val auth = flow(store, OAuthTransport()); val authorize = auth.begin(old.baseUrl)
        assertThrows(IllegalArgumentException::class.java) { auth.complete(callback(authorize).replace("state=", "state=bad")) }
        assertEquals("LLAT", store.load()!!.token)
        assertThrows(java.io.IOException::class.java) { auth.complete(callback(authorize, error = true)) }
        assertNull(store.readSecret("pending_oauth"))
        auth.cancel(); assertThrows(java.io.IOException::class.java) { auth.complete(callback(authorize)) }
        assertEquals("LLAT", store.load()!!.token)
    }
    @Test fun restartWithPersistedPendingStateCompletesAndConcurrentSessionChangeFails() {
        MockWebServer().use { server ->
            val store = AuthTestStore(); server.enqueue(tokens())
            val redirect = callback(flow(store, OAuthTransport()).begin(server.url("/").toString()))
            flow(store, OAuthTransport()).complete(redirect); assertTrue(store.load()!!.isOAuth)
            val auth = flow(store, OAuthTransport()); val next = callback(auth.begin(store.load()!!.baseUrl))
            store.value = store.value!!.copy(sessionId = "replacement")
            assertThrows(IllegalArgumentException::class.java) { auth.complete(next) }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun refreshHandlesNonRotatingTokensAndSanitizesErrors() {
        MockWebServer().use { server ->
            val c = oauthConnection(server.url("/").toString().trimEnd('/')); val transport = OAuthTransport(now = { 100 })
            server.enqueue(MockResponse().setBody("""{"access_token":"refreshed","expires_in":1800,"token_type":"Bearer"}"""))
            val next = transport.refresh(c); assertEquals(c.refreshToken, next.refreshToken); assertEquals(1_800_100, next.expiresAt)
            assertTrue(server.takeRequest().body.readUtf8().contains("grant_type=refresh_token"))
            server.enqueue(MockResponse().setResponseCode(400).setBody("SECRET"))
            val error = assertThrows(ReauthorizationRequired::class.java) { transport.refresh(c) }
            assertFalse(error.toString().contains("SECRET"))
        }
    }
    @Test fun revokeFallsBackForOldHAAndOfflineRemovalIsExplicit() {
        MockWebServer().use { server ->
            val c = oauthConnection(server.url("/").toString().trimEnd('/')); val store = AuthTestStore(c)
            val auth = flow(store, OAuthTransport())
            server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse())
            auth.logout(); assertNull(store.load())
            assertEquals("/auth/revoke", server.takeRequest().path)
            assertTrue(server.takeRequest().body.readUtf8().contains("action=revoke"))
            store.value = c; server.enqueue(MockResponse().setResponseCode(503))
            assertThrows(java.io.IOException::class.java) { auth.logout() }; assertNotNull(store.load())
            auth.logout(localOnly = true); assertNull(store.load())
        }
    }
}
