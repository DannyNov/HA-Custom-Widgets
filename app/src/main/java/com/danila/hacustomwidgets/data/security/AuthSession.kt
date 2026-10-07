package com.danila.hacustomwidgets.data.security

import java.io.IOException

/** Shared by all store/manager instances in this (single-process) application. */
internal val sessionLock = Any()
private val refreshLock = Any()

data class HomeAssistantConnection(
    val baseUrl: String,
    val token: String,
    val refreshToken: String? = null,
    val expiresAt: Long = Long.MAX_VALUE,
    val clientId: String? = null,
    val sessionId: String = "legacy",
    val server: ServerMetadata = ServerMetadata(),
) {
    val isOAuth get() = refreshToken != null
    val authenticationId: String get() = if (isOAuth) sessionId else java.security.MessageDigest.getInstance("SHA-256")
        .digest((baseUrl + "\u0000" + token).toByteArray()).joinToString("") { "%02x".format(it) }
    override fun toString() = "HomeAssistantConnection(oauth=$isOAuth)"
    // A refreshed token or changed route must not invalidate in-flight state reconciliation.
    override fun equals(other: Any?): Boolean = other is HomeAssistantConnection &&
        baseUrl == other.baseUrl && sessionId == other.sessionId && isOAuth == other.isOAuth &&
        (isOAuth || token == other.token)
    override fun hashCode(): Int = 31 * baseUrl.hashCode() + (if (isOAuth) sessionId else token).hashCode()
}

interface SessionStore {
    fun load(): HomeAssistantConnection?
    fun replace(connection: HomeAssistantConnection)
    fun updateMetadata(connection: HomeAssistantConnection, metadata: ServerMetadata)
    fun clear()
}

interface AuthorizationStore : SessionStore {
    fun readSecret(name: String): String?
    fun writeSecret(name: String, value: String, removeLegacy: Boolean = false)
    fun removeSecret(name: String)
}

class ReauthorizationRequired : IOException("Войдите в Home Assistant повторно / Sign in to Home Assistant again")

/** Serializes refreshes and prevents stale requests from restoring a logged-out/replaced session. */
class AccessTokenManager(
    private val store: SessionStore,
    private val refresh: (HomeAssistantConnection) -> HomeAssistantConnection,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun rejectSession(connection: HomeAssistantConnection, rejectedToken: String) = synchronized(sessionLock) {
        val current = store.load()
        if (current == connection && current!!.isOAuth && current.token == rejectedToken) {
            store.replace(current.copy(token = "", expiresAt = 0))
        }
    }
    fun token(connection: HomeAssistantConnection, rejected: String? = null): String = synchronized(refreshLock) {
        if (!connection.isOAuth) return@synchronized connection.token
        val current = store.load() ?: throw ReauthorizationRequired()
        if (current.sessionId != connection.sessionId || current.baseUrl != connection.baseUrl || !current.isOAuth) {
            throw ReauthorizationRequired()
        }
        if (current.token.isEmpty()) throw ReauthorizationRequired()
        if (rejected != null && rejected != current.token) return@synchronized current.token
        if (rejected == null && now() < current.expiresAt - 60_000) return@synchronized current.token
        val renewed = try { refresh(current) } catch (error: ReauthorizationRequired) {
            // Keep URL and widget configuration, but make this session unusable until sign-in.
            synchronized(sessionLock) { if (store.load() == current) store.replace(current.copy(token = "", expiresAt = 0)) }
            throw error
        }
        synchronized(sessionLock) {
            val latest = store.load()?.takeIf { it == current } ?: throw ReauthorizationRequired()
            store.replace(renewed.copy(server = latest.server))
        }
        renewed.token
    }
}
