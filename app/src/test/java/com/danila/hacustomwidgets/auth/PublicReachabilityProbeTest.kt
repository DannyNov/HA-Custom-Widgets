package com.danila.hacustomwidgets.auth

import com.danila.hacustomwidgets.data.security.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PublicReachabilityProbeTest {
    @Test fun probeUsesPublicRootWithoutCredentialsForOAuthAndLegacy() {
        for (oauth in listOf(false, true)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("Home Assistant"))
            val url = server.url("/").toString().trimEnd('/')
            val connection = HomeAssistantConnection(url, "secret-access", if (oauth) "secret-refresh" else null)
            assertEquals(url, HAConnectionManager(AuthTestStore(connection)).resolve(connection))
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/", request.path)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            assertEquals(0, request.bodySize)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun publicRedirectIsReachableButNeverFollowed() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/api/")))
        val url = server.url("/").toString().trimEnd('/')
        val connection = HomeAssistantConnection(url, "secret")
        assertEquals(url, HAConnectionManager(AuthTestStore(connection)).resolve(connection))
        assertEquals("/", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test fun unauthorizedRootIsNotTreatedAsSuccessfulProbe() = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(401))
        val url = server.url("/").toString().trimEnd('/')
        val connection = HomeAssistantConnection(url, "secret")
        try {
            HAConnectionManager(AuthTestStore(connection)).resolve(connection)
            fail("401 must fail")
        } catch (error: EndpointStatusException) { assertEquals(401, error.status) }
        assertEquals("/", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }
}
