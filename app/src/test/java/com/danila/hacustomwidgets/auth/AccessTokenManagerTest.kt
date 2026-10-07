package com.danila.hacustomwidgets.auth

import com.danila.hacustomwidgets.data.security.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class AccessTokenManagerTest {
    @Test fun legacyAndValidTokensDoNotRefresh() {
        val legacy = HomeAssistantConnection("http://ha.local", "LLAT")
        val store = AuthTestStore(legacy)
        val manager = AccessTokenManager(store, { error("Unexpected refresh") }, { 100 })
        assertEquals("LLAT", manager.token(legacy))
        store.value = oauthConnection(); assertEquals("access-secret", manager.token(store.value!!))
    }
    @Test fun expiresRefreshesAndPersistsBeforeReturning() {
        val c = oauthConnection(); val store = AuthTestStore(c)
        val manager = AccessTokenManager(store, { it.copy(token = "renewed", expiresAt = 4_000_000) }, { 999_999 })
        assertEquals("renewed", manager.token(c)); assertEquals("renewed", store.load()!!.token)
        assertEquals(c.refreshToken, store.load()!!.refreshToken)
    }
    @Test fun simultaneousExpirationAnd401RefreshOnce() {
        val c = oauthConnection(); val store = AuthTestStore(c); val calls = AtomicInteger()
        val manager = AccessTokenManager(store, { calls.incrementAndGet(); it.copy(token = "new", expiresAt = 4_000_000) }, { 999_999 })
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..16).map { pool.submit(Callable { manager.token(c, c.token) }) }.map { it.get(5, TimeUnit.SECONDS) }
            assertTrue(results.all { it == "new" }); assertEquals(1, calls.get())
        } finally { pool.shutdownNow() }
    }
    @Test fun transientRefreshFailureRetainsCredentials() {
        val c = oauthConnection(); val store = AuthTestStore(c)
        val manager = AccessTokenManager(store, { throw IOException("offline") }, { 999_999 })
        assertThrows(IOException::class.java) { manager.token(c) }
        assertEquals(c.refreshToken, store.load()!!.refreshToken); assertEquals(c.token, store.load()!!.token)
    }
    @Test fun rejectedRefreshStopsBackgroundRetryLoopButKeepsRevokeCredential() {
        val c = oauthConnection(); val store = AuthTestStore(c); var calls = 0
        val manager = AccessTokenManager(store, { calls++; throw ReauthorizationRequired() }, { 999_999 })
        repeat(2) { assertThrows(ReauthorizationRequired::class.java) { manager.token(c) } }
        assertEquals(1, calls); assertEquals("", store.load()!!.token); assertEquals(c.refreshToken, store.load()!!.refreshToken)
    }
    @Test fun logoutDuringRefreshCannotResurrectSession() {
        val c = oauthConnection(); val store = AuthTestStore(c)
        val manager = AccessTokenManager(store, { store.clear(); it.copy(token = "new") }, { 999_999 })
        assertThrows(ReauthorizationRequired::class.java) { manager.token(c) }; assertNull(store.load())
    }
    @Test fun replacedSessionCannotUseOldCredentials() {
        val c = oauthConnection(); val store = AuthTestStore(c.copy(sessionId = "other"))
        val manager = AccessTokenManager(store, { error("Unexpected refresh") }, { 100 })
        assertThrows(ReauthorizationRequired::class.java) { manager.token(c) }
    }
    @Test fun tokenAndRouteChangesPreserveSessionEquality() {
        val c = oauthConnection()
        assertEquals(c, c.copy(token = "new", server = ServerMetadata(lastWorkingUrl = "https://remote.example")))
        assertNotEquals(c, c.copy(sessionId = "other"))
    }
}
