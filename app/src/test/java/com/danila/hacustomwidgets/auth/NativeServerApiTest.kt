package com.danila.hacustomwidgets.auth

import com.danila.hacustomwidgets.data.security.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class NativeServerApiTest {
    @Test fun firstRegistrationPersistsWebhookAndCloudAndWaitsForAsyncConfigEntry() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val api = NativeServerApi(deviceInfo = { mapOf("manufacturer" to "Test", "model" to "Test", "os_version" to "16") })
            server.enqueue(MockResponse().setBody("""{"components":["mobile_app"],"location_name":"Home"}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"webhook_id":"new-webhook","remote_ui_url":"https://home.ui.nabu.casa"}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"instance_id":"instance","remote_ui_url":"https://home.ui.nabu.casa"}"""))
            val saved = api.inspect(oauthConnection(base), base)
            assertEquals("new-webhook", saved.server.webhookId); assertEquals("instance", saved.server.instanceId)
            assertNotNull(saved.server.deviceId)
            assertTrue(saved.server.routes.contains(ServerRoute("https://home.ui.nabu.casa", RouteKind.CLOUD)))
            server.takeRequest(); val registration = server.takeRequest()
            assertEquals("/api/mobile_app/registrations", registration.path)
            val payload = org.json.JSONObject(registration.body.readUtf8())
            assertEquals("com.danila.hacustomwidgets", payload.getString("app_id"))
            assertFalse(payload.getBoolean("supports_encryption")); assertFalse(payload.has("gps"))
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun forbiddenMobileRegistrationKeepsOAuthAndManualRemoteFallback() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val api = NativeServerApi(deviceInfo = { emptyMap() })
            server.enqueue(MockResponse().setBody("""{"components":["mobile_app"],"location_name":"Home"}"""))
            server.enqueue(MockResponse().setResponseCode(403))
            val saved = api.inspect(oauthConnection(base), base)
            assertTrue(saved.isOAuth); assertNull(saved.server.webhookId)
            assertTrue(saved.server.routes.none { it.kind == RouteKind.CLOUD })
        }
    }
    @Test fun authenticatedConfigAndNativeWebhookProvideRoutesAndStableIdentity() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val c = oauthConnection(base).copy(server = ServerMetadata(webhookId = "native-secret"))
            server.enqueue(MockResponse().setBody("""{"location_name":"Home","components":["mobile_app"],"internal_url":"http://ha.local:8123","external_url":"https://home.example.com"}"""))
            server.enqueue(MockResponse().setBody("""{"instance_id":"stable-instance","remote_ui_url":"https://example.ui.nabu.casa"}"""))
            val inspected = NativeServerApi().inspect(c, base)
            assertEquals("stable-instance", inspected.server.instanceId); assertEquals("Home", inspected.server.name)
            assertTrue(inspected.server.routes.contains(ServerRoute("http://ha.local:8123", RouteKind.INTERNAL)))
            assertTrue(inspected.server.routes.contains(ServerRoute("https://home.example.com", RouteKind.EXTERNAL)))
            assertTrue(inspected.server.routes.contains(ServerRoute("https://example.ui.nabu.casa", RouteKind.CLOUD)))
            assertEquals("Bearer access-secret", server.takeRequest().getHeader("Authorization"))
            val webhook = server.takeRequest(); assertEquals("/api/webhook/native-secret", webhook.path)
            assertNull(webhook.getHeader("Authorization")); assertTrue(webhook.body.readUtf8().contains("get_config"))
        }
    }
    @Test fun absentMobileAppAndUnsetExternalRemainHonestLocalOnlyFallback() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("""{"location_name":"Home","components":[],"internal_url":null,"external_url":null}"""))
            val inspected = NativeServerApi().inspect(oauthConnection(base), base)
            assertNull(inspected.server.instanceId); assertNull(inspected.server.webhookId)
            assertTrue(inspected.server.routes.none { it.kind == RouteKind.CLOUD || it.kind == RouteKind.EXTERNAL })
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun rediscoveryRequiresSameInstanceAndStoredRegistration() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val c = oauthConnection().copy(server = ServerMetadata(instanceId = "same", webhookId = "native-secret"))
            val native = NativeServerApi()
            server.enqueue(MockResponse().setBody("""{"instance_id":"other"}""")); assertFalse(native.verifyRediscovered(c, base))
            server.enqueue(MockResponse().setBody("""{"instance_id":"same"}""")); assertTrue(native.verifyRediscovered(c, base))
            assertFalse(native.verifyRediscovered(c.copy(server = ServerMetadata()), base)); assertEquals(2, server.requestCount)
        }
    }
    @Test fun metadataInspectionRejectsDifferentInstance() {
        MockWebServer().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val c = oauthConnection(base).copy(server = ServerMetadata(instanceId = "same", webhookId = "native-secret"))
            server.enqueue(MockResponse().setBody("""{"components":["mobile_app"]}"""))
            server.enqueue(MockResponse().setBody("""{"instance_id":"other"}"""))
            assertThrows(IllegalArgumentException::class.java) { NativeServerApi().inspect(c, base) }
        }
    }
}
