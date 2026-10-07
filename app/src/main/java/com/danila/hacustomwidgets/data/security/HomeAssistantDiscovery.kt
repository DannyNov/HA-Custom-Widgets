package com.danila.hacustomwidgets.data.security

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import okhttp3.HttpUrl

data class DiscoveredServer(val discoveryId: String?, val name: String, val url: String)

object DiscoveryPolicy {
    fun server(host: String, port: Int, serviceName: String, attributes: Map<String, String>): DiscoveredServer {
        require(port in 1..65535)
        val configured = attributes["internal_url"]?.takeIf { it.isNotBlank() }
            ?.let { runCatching { OAuthPolicy.normalizeUrl(it) }.getOrNull() }
        val url = configured ?: HttpUrl.Builder().scheme("http").host(host).port(port).build().toString().trimEnd('/')
        return DiscoveredServer(attributes["uuid"]?.takeIf { it.isNotBlank() },
            attributes["location_name"]?.takeIf { it.isNotBlank() } ?: serviceName, url)
    }

    fun merge(servers: List<DiscoveredServer>, server: DiscoveredServer): List<DiscoveredServer> =
        (servers.filterNot { if (server.discoveryId != null) it.discoveryId == server.discoveryId else it.url == server.url } + server)
            .sortedBy { it.name }
}

class HomeAssistantDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    /** Bounded by caller; NSD owns multicast sockets, so no Wi-Fi/multicast/location permission. */
    @Suppress("DEPRECATION")
    fun discover() = callbackFlow<List<DiscoveredServer>> {
        val handler = Handler(Looper.getMainLooper())
        var closed = false
        var started = false
        var resolving = false
        var found = emptyList<DiscoveredServer>()
        val queue = java.util.ArrayDeque<NsdServiceInfo>()
        fun resolveNext() {
            if (closed || resolving || queue.isEmpty()) return
            resolving = true
            val service = queue.removeFirst()
            try {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        handler.post { resolving = false; resolveNext() }
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        handler.post {
                            if (!closed) runCatching {
                                val host = info.host?.hostAddress ?: return@runCatching
                                val server = DiscoveryPolicy.server(host, info.port, info.serviceName,
                                    info.attributes.mapValues { (_, bytes) -> bytes.toString(Charsets.UTF_8) })
                                found = DiscoveryPolicy.merge(found, server)
                                trySend(found)
                            }
                            resolving = false
                            resolveNext()
                        }
                    }
                })
            } catch (_: Exception) { resolving = false; resolveNext() }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { handler.post {
                started = true
                if (closed) runCatching { nsd.stopServiceDiscovery(this) }
            } }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                handler.post { if (!closed && queue.size < 32) { queue.addLast(serviceInfo); resolveNext() } }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) { /* Keep discoveries for user selection during this scan. */ }
            override fun onDiscoveryStopped(serviceType: String) { started = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { started = false }
        }
        handler.post {
            if (!closed) try { nsd.discoverServices("_home-assistant._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            catch (_: Exception) { close() }
        }
        awaitClose {
            handler.post { closed = true; queue.clear(); if (started) runCatching { nsd.stopServiceDiscovery(listener) } }
        }
    }.conflate()
}
