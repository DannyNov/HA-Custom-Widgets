package com.danila.hacustomwidgets.data.security

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

object OAuthPolicy {
    const val CLIENT_ID = "https://dannynov.github.io/HA-Custom-Widgets/"
    const val REDIRECT_URI = "https://dannynov.github.io/HA-Custom-Widgets/auth/callback"
    const val MAX_AGE_MS = 10 * 60_000L

    fun normalizeUrl(input: String): String {
        val url = input.trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Укажите только адрес Home Assistant / Enter only the Home Assistant URL"
        }
        // Existing installations on trusted LANs may continue to use HTTP. Never bypass TLS validation.
        return url.toString().trimEnd('/')
    }

    fun randomState(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        ByteArray(32).also { SecureRandom().nextBytes(it) },
    )

    fun authorizationUrl(baseUrl: String, state: String): String =
        (baseUrl + "/auth/authorize").toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("state", state).build().toString()

    fun callbackCode(uri: String, state: String, createdAt: Long, now: Long): String {
        val url = try { uri.toHttpUrl() } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid authorization callback")
        }
        val expected = REDIRECT_URI.toHttpUrl()
        require(url.scheme == expected.scheme && url.host == expected.host && url.port == expected.port &&
            url.encodedPath == expected.encodedPath && url.username.isEmpty() && url.password.isEmpty() &&
            url.fragment == null && url.queryParameterValues("state").size == 1 &&
            MessageDigest.isEqual(url.queryParameter("state").orEmpty().toByteArray(), state.toByteArray()) &&
            now >= createdAt && now - createdAt <= MAX_AGE_MS) {
            "Недействительный или просроченный ответ входа / Invalid or expired sign-in response"
        }
        if (url.queryParameter("error") != null) throw IOException("Вход отменён / Sign-in cancelled")
        require(url.queryParameterValues("code").size == 1) { "Invalid authorization response" }
        return url.queryParameter("code")!!.also { require(it.isNotBlank() && it.length <= 4096) }
    }
}

class OAuthTransport(
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun exchange(baseUrl: String, code: String): HomeAssistantConnection {
        val json = post(baseUrl, "/auth/token", FormBody.Builder()
            .add("grant_type", "authorization_code").add("code", code)
            .add("client_id", OAuthPolicy.CLIENT_ID).add("redirect_uri", OAuthPolicy.REDIRECT_URI).build())
        val refresh = json.optString("refresh_token").takeIf { !json.isNull("refresh_token") && it.isNotBlank() }
            ?: throw IOException("Invalid Home Assistant token response")
        return parse(json, HomeAssistantConnection(baseUrl, "", refresh, 0, OAuthPolicy.CLIENT_ID, UUID.randomUUID().toString()))
    }

    fun refresh(connection: HomeAssistantConnection, endpoint: String = connection.baseUrl): HomeAssistantConnection = parse(
        post(endpoint, "/auth/token", FormBody.Builder().add("grant_type", "refresh_token")
            .add("refresh_token", requireNotNull(connection.refreshToken))
            .add("client_id", requireNotNull(connection.clientId)).build(), refreshing = true), connection,
    )

    private fun parse(json: JSONObject, previous: HomeAssistantConnection): HomeAssistantConnection = try {
        require(json.getString("token_type").equals("Bearer", ignoreCase = true)) { "Unsupported token type" }
        val seconds = json.getLong("expires_in")
        require(seconds in 1..31_536_000) { "Invalid token lifetime" }
        previous.copy(token = json.getString("access_token").also { require(it.isNotBlank()) },
            expiresAt = now() + seconds * 1000,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: previous.refreshToken)
    } catch (_: Exception) {
        throw IOException("Некорректный ответ токена Home Assistant / Invalid Home Assistant token response")
    }

    private fun post(base: String, path: String, body: FormBody, refreshing: Boolean = false): JSONObject {
        http.newCall(Request.Builder().url(base + path).post(body).build()).execute().use { response ->
            if (!response.isSuccessful) {
                // Never expose server error_description, response bodies, codes or token strings to logs/UI.
                if (refreshing && response.code in setOf(400, 401, 403)) throw ReauthorizationRequired()
                throw IOException("Home Assistant: ошибка входа / Sign-in failed (HTTP ${response.code})")
            }
            return try { JSONObject(response.body?.string() ?: "") }
            catch (_: Exception) { throw IOException("Некорректный ответ Home Assistant / Invalid Home Assistant response") }
        }
    }

    fun revoke(connection: HomeAssistantConnection, endpoint: String = connection.baseUrl) {
        val token = connection.refreshToken ?: return
        fun request(path: String, legacy: Boolean): Int {
            val body = FormBody.Builder().add("token", token).apply { if (legacy) add("action", "revoke") }.build()
            return http.newCall(Request.Builder().url(endpoint + path).post(body).build()).execute().use { it.code }
        }
        val status = request("/auth/revoke", false)
        val finalStatus = if (status == 404 || status == 405) request("/auth/token", true) else status
        if (finalStatus != 200) throw IOException("Не удалось отозвать вход / Could not revoke sign-in")
    }
}

class HomeAssistantOAuth(private val store: AuthorizationStore, private val transport: OAuthTransport,
    private val routes: HAConnectionManager,
    private val enrich: (HomeAssistantConnection, String) -> HomeAssistantConnection,
) {
    fun begin(input: String): String = synchronized(sessionLock) {
        val base = OAuthPolicy.normalizeUrl(input)
        val existing = store.load()
        require(existing == null || existing.baseUrl == base || existing.server.routes.any { it.url == base }) {
            "Сначала выйдите перед сменой сервера / Sign out before changing servers"
        }
        val state = OAuthPolicy.randomState()
        val pending = JSONObject().put("url", base).put("state", state).put("created", System.currentTimeMillis())
            .put("previous", existing?.authenticationId.orEmpty())
        store.writeSecret("pending_oauth", pending.toString())
        OAuthPolicy.authorizationUrl(base, state)
    }

    fun cancel() = synchronized(sessionLock) { store.removeSecret("pending_oauth") }

    fun complete(uri: String) {
        val (pending, code) = synchronized(sessionLock) {
        val pending = try { JSONObject(store.readSecret("pending_oauth") ?: throw IOException()) }
            catch (_: Exception) { throw IOException("Нет ожидающего входа / No pending sign-in") }
        val code = try { OAuthPolicy.callbackCode(uri, pending.getString("state"), pending.getLong("created"), System.currentTimeMillis()) }
        catch (error: IOException) { store.removeSecret("pending_oauth"); throw error }
        // Consume before network I/O; duplicate callbacks must never exchange a code twice.
        store.removeSecret("pending_oauth")
        require(store.load()?.authenticationId.orEmpty() == pending.getString("previous")) { "Connection changed; start sign-in again" }
        pending to code
        }
        val previous = store.load()
        val connection = transport.exchange(pending.getString("url"), code).let {
            it.copy(baseUrl = previous?.baseUrl ?: it.baseUrl, server = previous?.server ?: it.server)
        }
        // Test API access and collect metadata before replacing the legacy credentials.
        try {
            val enriched = enrich(connection, pending.getString("url"))
            synchronized(sessionLock) {
                require(store.load()?.authenticationId.orEmpty() == pending.getString("previous")) { "Connection changed; start sign-in again" }
                store.replace(enriched)
            }
        } catch (error: Exception) {
            runCatching { transport.revoke(connection, pending.getString("url")) }
            throw error
        }
    }

    /** Failed revoke retains credentials for retry; local-only removal is a separate explicit choice. */
    fun logout(localOnly: Boolean = false) {
        val connection = store.load()
        if (!localOnly && connection != null) transport.revoke(connection, routes.resolve(connection))
        synchronized(sessionLock) {
            require(store.load() == connection) { "Connection changed; try again" }
            store.clear()
        }
    }
}
