package com.danila.hacustomwidgets.auth

import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.security.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HomeAssistantTransportTest {
    @Test fun expiredBackgroundRequestUsesCloudAndRefreshWithoutActivity() = runBlocking {
        MockWebServer().use { remote ->
            val lan = "http://192.168.1.20:8123"
            val cloud = remote.url("/").toString().trimEnd('/')
            val c = oauthConnection(lan).copy(expiresAt = 0, server = ServerMetadata(instanceId = "home-instance",
                routes = listOf(ServerRoute(lan, RouteKind.INTERNAL), ServerRoute(cloud, RouteKind.CLOUD)), lastWorkingUrl = lan))
            val store = AuthTestStore(c)
            val connections = HAConnectionManager(store, probe = { if (it == lan) throw java.net.UnknownHostException() })
            val transport = OAuthTransport()
            val tokens = AccessTokenManager(store, { connections.refresh(it, transport) })
            val client = HomeAssistantClient(accessTokens = tokens, connections = connections)
            remote.enqueue(MockResponse().setBody("""{"access_token":"background-token","expires_in":1800,"token_type":"Bearer"}"""))
            remote.enqueue(MockResponse().setBody("[]"))
            assertTrue(client.getEntities(c).isEmpty())
            val refresh = remote.takeRequest(); assertEquals("/auth/token", refresh.path)
            assertTrue(refresh.body.readUtf8().contains("refresh_token=refresh-secret"))
            val states = remote.takeRequest(); assertEquals("/api/states", states.path)
            assertEquals("Bearer background-token", states.getHeader("Authorization"))
            assertEquals(cloud, store.load()!!.server.lastWorkingUrl); assertEquals("home-instance", store.load()!!.server.instanceId)
        }
    }
    @Test fun rest401RefreshesAndRetriesExactlyOnce() = runBlocking {
        MockWebServer().use { server ->
            val c = oauthConnection(server.url("/").toString().trimEnd('/')); val store = AuthTestStore(c); var refreshes = 0
            val manager = AccessTokenManager(store, { refreshes++; it.copy(token = "new") }, { 100 })
            val client = HomeAssistantClient(accessTokens = manager)
            server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(MockResponse().setBody("[]"))
            assertTrue(client.getEntities(c).isEmpty())
            assertEquals("Bearer access-secret", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization")); assertEquals(1, refreshes)
        }
    }
    @Test fun second401RequiresReauthorizationAnd403DoesNotRefresh() = runBlocking {
        MockWebServer().use { server ->
            val c = oauthConnection(server.url("/").toString().trimEnd('/')); val store = AuthTestStore(c); var refreshes = 0
            val manager = AccessTokenManager(store, { refreshes++; it.copy(token = "new") }, { 100 })
            val client = HomeAssistantClient(accessTokens = manager)
            server.enqueue(MockResponse().setResponseCode(403))
            try { client.testConnection(c); fail("Expected forbidden") } catch (_: java.io.IOException) {}
            assertEquals(0, refreshes)
            server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(MockResponse().setResponseCode(401))
            try { client.testConnection(c); fail("Expected unauthorized") } catch (_: java.io.IOException) {}
            assertEquals(1, refreshes); assertEquals("", store.load()!!.token); assertEquals(3, server.requestCount)
        }
    }
    @Test fun serviceCallsUseSelectedEndpointAndNeverReplayAfterAmbiguousDisconnect() = runBlocking {
        MockWebServer().use { initial -> MockWebServer().use { remote ->
            val c = oauthConnection(initial.url("/").toString().trimEnd('/')).copy(server = ServerMetadata(
                routes = listOf(ServerRoute(remote.url("/").toString().trimEnd('/'), RouteKind.CLOUD)),
                lastWorkingUrl = remote.url("/").toString().trimEnd('/')))
            val store = AuthTestStore(c)
            val client = HomeAssistantClient(accessTokens = AccessTokenManager(store, { error("Unexpected refresh") }, { 100 }),
                connections = HAConnectionManager(store, probe = {}))
            remote.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            try { client.callService(c, "switch", "toggle", "switch.test"); fail("Expected disconnect") } catch (_: java.io.IOException) {}
            assertEquals(0, initial.requestCount); assertEquals(1, remote.requestCount)
            assertEquals("/api/services/switch/toggle", remote.takeRequest().path)
        } }
    }
    @Test fun legacyRestUsesExistingTokenAndRedirectsNeverReceiveCredentials() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            val c = HomeAssistantConnection(server.url("/").toString().trimEnd('/'), "LLAT")
            val client = HomeAssistantClient()
            server.enqueue(MockResponse().setBody("[]")); client.getEntities(c)
            assertEquals("Bearer LLAT", server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/api/")))
            try { client.testConnection(c); fail("Expected redirect rejection") } catch (_: java.io.IOException) {}
            assertEquals(0, other.requestCount)
        } }
    }
    @Test fun registryAndRepairsWebSocketsRefreshRejectedTokensAndReconnect() = runBlocking {
        MockWebServer().use { server ->
            val opens = AtomicInteger(); val observedTokens = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/api/states") return MockResponse().setBody("[]")
                    val number = opens.incrementAndGet()
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(socket: WebSocket, response: Response) { socket.send("""{"type":"auth_required"}""") }
                        override fun onMessage(socket: WebSocket, text: String) {
                            val json = JSONObject(text)
                            if (json.getString("type") == "auth") {
                                synchronized(observedTokens) { observedTokens += json.getString("access_token") }
                                socket.send(if (number == 1 || number == 3) """{"type":"auth_invalid"}""" else """{"type":"auth_ok"}""")
                            } else {
                                val payload = if (json.getString("type") == "repairs/list_issues") "{\"issues\":[]}" else "[]"
                                socket.send("""{"type":"result","id":${json.getInt("id")},"success":true,"result":$payload}""")
                            }
                        }
                    })
                }
            }
            val c = oauthConnection(server.url("/").toString().trimEnd('/')); val store = AuthTestStore(c); var refreshes = 0
            val manager = AccessTokenManager(store, { refreshes++; it.copy(token = "new-$refreshes") }, { 100 })
            val client = HomeAssistantClient(accessTokens = manager)
            assertTrue(client.getCatalog(c).groups.isEmpty())
            assertTrue(client.getRepairs(c).isEmpty())
            assertEquals(2, refreshes); assertEquals(4, opens.get())
            assertEquals(listOf("access-secret", "new-1", "new-1", "new-2"), observedTokens)
        }
    }
}
