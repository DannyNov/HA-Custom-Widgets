package com.danila.hacustomwidgets.auth

import com.danila.hacustomwidgets.data.security.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException

class ConnectionRoutingTest {
    private val lan = "http://192.168.1.20:8123"
    private val remote = "https://home.example.com"
    private fun connection(last: String = lan) = oauthConnection(lan).copy(server = ServerMetadata(instanceId = "instance", routes =
        listOf(ServerRoute(lan, RouteKind.INTERNAL), ServerRoute(remote, RouteKind.EXTERNAL)), lastWorkingUrl = last))

    @Test fun lastWorkingRouteSkipsOtherProbesAndCaches() {
        val c = connection(); val store = AuthTestStore(c); val probes = mutableListOf<String>()
        val routes = HAConnectionManager(store, { 100 }) { probes += it }
        repeat(3) { assertEquals(lan, routes.resolve(c)) }
        assertEquals(listOf(lan), probes)
    }
    @Test fun wifiToMobileAndBackWithNoReauthorization() {
        var time = 100L; var home = true
        val c = connection(); val store = AuthTestStore(c); val probes = mutableListOf<String>()
        val routes = HAConnectionManager(store, { time }) { url ->
            probes += url
            if ((url == lan && !home) || (url == remote && home)) throw ConnectException()
        }
        assertEquals(lan, routes.resolve(c)); routes.succeeded(c, lan)
        home = false; time += 1_000; routes.networkChanged()
        assertEquals(remote, routes.resolve(c)); routes.succeeded(c, remote)
        assertEquals(remote, store.load()!!.server.lastWorkingUrl)
        probes.clear(); repeat(5) { assertEquals(remote, routes.resolve(c)) }; assertTrue(probes.isEmpty())
        assertEquals(c.refreshToken, store.load()!!.refreshToken)
        home = true; time += 1_000; routes.networkChanged()
        assertEquals(lan, routes.resolve(c)); routes.succeeded(c, lan)
        assertEquals(lan, store.load()!!.server.lastWorkingUrl)
        assertEquals(c.sessionId, store.load()!!.sessionId)
    }
    @Test fun universallyWorkingExternalCanStaySelectedAtHome() {
        val c = connection(remote); val store = AuthTestStore(c); val probes = mutableListOf<String>()
        val routes = HAConnectionManager(store, { 100 }) { probes += it }
        assertEquals(remote, routes.resolve(c)); assertEquals(listOf(remote), probes)
    }
    @Test fun failedCurrentRouteFailsOverAndAvoidsRepeatedLanTimeout() {
        val c = connection(); val store = AuthTestStore(c); val probes = mutableListOf<String>()
        val routes = HAConnectionManager(store, { 100 }) { probes += it }
        assertEquals(lan, routes.resolve(c))
        routes.failed(lan, java.net.SocketTimeoutException())
        assertEquals(remote, routes.resolve(c)); assertEquals(listOf(lan, remote), probes)
    }
    @Test fun tlsAndHttpFailuresDoNotSwitchEndpoints() {
        listOf(SSLHandshakeException("bad certificate"), EndpointStatusException(403), EndpointStatusException(503)).forEach { error ->
            val c = connection(); val probes = mutableListOf<String>()
            val routes = HAConnectionManager(AuthTestStore(c), { 100 }) { probes += it; throw error }
            assertThrows(java.io.IOException::class.java) { routes.resolve(c) }
            assertEquals(listOf(lan), probes)
        }
    }
    @Test fun metadataSurvivesSerializationAndProcessRecreation() {
        val c = connection(remote).copy(server = connection(remote).server.copy(webhookId = "webhook-secret", lastSuccessAt = 1234))
        val restored = c.copy(server = ServerMetadata.fromJson(org.json.JSONObject(c.server.toJson().toString())))
        assertEquals(c.server, restored.server)
        assertEquals(remote, HAConnectionManager(AuthTestStore(restored), { 100 }) {}.resolve(restored))
    }
    @Test fun oneMultipleNoneAndChangedIpDiscovery() {
        val old = DiscoveryPolicy.server("192.168.1.20", 8123, "Home", mapOf("uuid" to "same"))
        val next = DiscoveryPolicy.server("192.168.1.30", 8123, "Home", mapOf("uuid" to "same"))
        val other = DiscoveryPolicy.server("192.168.1.40", 8123, "Other", mapOf("uuid" to "other"))
        assertEquals(0, emptyList<DiscoveredServer>().size)
        assertEquals(1, DiscoveryPolicy.merge(emptyList(), old).size)
        assertEquals(2, DiscoveryPolicy.merge(listOf(old), other).size)
        val updated = DiscoveryPolicy.merge(listOf(old, other), next)
        assertEquals(2, updated.size); assertTrue(updated.contains(next)); assertFalse(updated.contains(old))
    }
    @Test fun externalAndDiscoveredFailOverInBothDirectionsAndKeepLastWorking() {
        var blocked = remote
        val c = oauthConnection(remote).copy(server = ServerMetadata(routes = listOf(
            ServerRoute(remote, RouteKind.EXTERNAL), ServerRoute(lan, RouteKind.DISCOVERED)), lastWorkingUrl = remote))
        val store = AuthTestStore(c)
        val manager = HAConnectionManager(store, { 100L }) { if (it == blocked) throw ConnectException() }
        assertEquals(lan, manager.resolve(c)); manager.succeeded(c, lan)
        assertEquals(lan, store.load()!!.server.lastWorkingUrl)
        blocked = ""; manager.networkChanged()
        assertEquals(lan, manager.resolve(store.load()!!)) // recovery does not force a healthy route off LAN
        blocked = lan; manager.networkChanged()
        assertEquals(remote, manager.resolve(store.load()!!)); manager.succeeded(c, remote)
        assertEquals(remote, store.load()!!.server.lastWorkingUrl)
        assertEquals(c.sessionId, store.load()!!.sessionId)
        assertEquals(c.refreshToken, store.load()!!.refreshToken)
    }

    @Test fun dhcpAndSpoofedUuidCannotAuthorizeANewEndpoint() {
        val c = connection().copy(server = connection().server.copy(instanceId = "public-uuid"))
        val unknown = DiscoveryPolicy.server("192.168.1.99", 8123, "Home", mapOf("uuid" to "public-uuid"))
        assertFalse(RouteTrustPolicy.isKnown(c, unknown.url))
        assertFalse(NativeServerApi().canReuseDiscovered(c, unknown.url))
        assertEquals(2, c.server.routes.size)
    }

}
