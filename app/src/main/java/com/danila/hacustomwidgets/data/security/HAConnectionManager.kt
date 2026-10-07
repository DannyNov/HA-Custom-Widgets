package com.danila.hacustomwidgets.data.security

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** One resolver for HTTP, websocket handshakes and OAuth refresh/revoke. No Activity dependency. */
class HAConnectionManager(
    private val store: SessionStore,
    private val now: () -> Long = System::currentTimeMillis,
    probe: ((String) -> Unit)? = null,
) {
    private val lock = Any()
    private val failedUntil = mutableMapOf<String, Long>()
    private val validatedUntil = mutableMapOf<String, Long>()
    private val probeHttp = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val probeEndpoint: (String) -> Unit = probe ?: { url ->
        // Public frontend only: unauthenticated /api/ produces failed-login events in HA.
        // Never send credentials or follow redirects. Authenticated requests verify HA access.
        probeHttp.newCall(Request.Builder().url(url.trimEnd('/') + "/").build()).execute().use {
            if (it.code !in 200..399) throw EndpointStatusException(it.code)
        }
    }

    fun candidates(connection: HomeAssistantConnection): List<String> {
        val metadata = connection.server
        return (listOfNotNull(metadata.lastWorkingUrl) +
            metadata.routes.filter { it.kind == RouteKind.EXTERNAL || it.kind == RouteKind.CLOUD }.map { it.url } +
            metadata.routes.filter { it.kind == RouteKind.INTERNAL }.map { it.url } +
            listOf(connection.baseUrl) + metadata.routes.map { it.url }).distinct()
    }

    fun resolve(connection: HomeAssistantConnection): String {
        val current = store.load()?.takeIf { it == connection } ?: connection.also {
            if (it.isOAuth) throw ReauthorizationRequired()
        }
        val candidates = candidates(current)
        return synchronized(lock) {
        val eligible = candidates.filter { (failedUntil[it] ?: 0) <= now() }
        var lastError: IOException? = null
        for (url in eligible) {
            if ((validatedUntil[url] ?: 0) > now()) return@synchronized url
            try {
                probeEndpoint(url)
                validatedUntil[url] = now() + 30_000
                return@synchronized url
            } catch (error: IOException) {
                if (!isNetworkFailure(error)) throw error
                failedUntil[url] = now() + 60_000
                lastError = error
            }
        }
        throw lastError ?: IOException("Home Assistant недоступен; повторите позже / Home Assistant unavailable; try again shortly")
        }
    }

    fun succeeded(connection: HomeAssistantConnection, url: String) {
        synchronized(sessionLock) {
            val current = store.load()?.takeIf { it == connection } ?: return
            // Coalesce disk writes across widget requests.
            if (current.server.lastWorkingUrl != url || now() - current.server.lastSuccessAt >= 30_000) {
                store.updateMetadata(current, current.server.copy(lastWorkingUrl = url, lastSuccessAt = now()))
            }
        }
    }

    fun failed(url: String, error: IOException) = synchronized(lock) {
        if (isNetworkFailure(error)) { validatedUntil.remove(url); failedUntil[url] = now() + 60_000 }
    }

    fun networkChanged() = synchronized(lock) { validatedUntil.clear(); failedUntil.clear() }

    fun refresh(connection: HomeAssistantConnection, transport: OAuthTransport): HomeAssistantConnection {
        var last: IOException? = null
        repeat(5) {
            val endpoint = resolve(connection)
            try { return transport.refresh(connection, endpoint) }
            catch (error: IOException) {
                failed(endpoint, error)
                if (!isNetworkFailure(error)) throw error
                last = error
            }
        }
        throw last ?: IOException("Home Assistant unavailable")
    }

    companion object {
        fun isNetworkFailure(error: IOException): Boolean = error !is SSLException &&
            (error is UnknownHostException || error is ConnectException || error is SocketTimeoutException ||
                error is java.net.NoRouteToHostException || error is java.net.SocketException)
    }
}

class EndpointStatusException(val status: Int) : IOException("Home Assistant HTTP $status")
