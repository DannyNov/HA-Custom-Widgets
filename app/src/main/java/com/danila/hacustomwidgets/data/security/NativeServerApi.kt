package com.danila.hacustomwidgets.data.security

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Official REST config + mobile_app registration/get_config; no frontend scraping. */
class NativeServerApi(
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
    private val deviceInfo: () -> Map<String, String> = { mapOf(
        "manufacturer" to android.os.Build.MANUFACTURER, "model" to android.os.Build.MODEL,
        "os_version" to android.os.Build.VERSION.RELEASE,
    ) },
) {
    private fun request(endpoint: String, path: String, token: String?, body: JSONObject? = null): JSONObject {
        val request = Request.Builder().url(endpoint + path).apply {
            if (token != null) header("Authorization", "Bearer $token")
            if (body != null) post(body.toString().toRequestBody("application/json".toMediaType()))
        }.build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw EndpointStatusException(response.code)
            try { JSONObject(response.body?.string().orEmpty()) }
            catch (_: Exception) { throw IOException("Invalid Home Assistant metadata") }
        }
    }

    fun inspect(connection: HomeAssistantConnection, endpoint: String): HomeAssistantConnection {
        val config = request(endpoint, "/api/config", connection.token)
        val old = connection.server
        val routes = mutableListOf<ServerRoute>()
        fun add(value: String?, kind: RouteKind) {
            if (value.isNullOrBlank()) return
            runCatching { OAuthPolicy.normalizeUrl(value) }.getOrNull()?.let {
                if (kind == RouteKind.INTERNAL || it.startsWith("https://")) routes.add(ServerRoute(it, kind))
            }
        }
        add(config.optString("internal_url").takeUnless { config.isNull("internal_url") }, RouteKind.INTERNAL)
        add(config.optString("external_url").takeUnless { config.isNull("external_url") }, RouteKind.EXTERNAL)
        var webhook = old.webhookId
        val device = old.deviceId ?: UUID.randomUUID().toString()
        var registrationDevice = old.registrationDeviceId
        var cloud: String? = null
        val components = config.optJSONArray("components")
        val mobileAvailable = components != null && (0 until components.length()).any { components.optString(it) == "mobile_app" }
        if (mobileAvailable) {
            // Registration is optional: a missing/forbidden mobile_app never breaks authentication.
            try {
                if (webhook == null) {
                    val result = request(endpoint, "/api/mobile_app/registrations", connection.token, JSONObject()
                        .put("device_id", device).put("app_id", "com.danila.hacustomwidgets")
                        .put("app_name", "HA Custom Widgets").put("app_version", com.danila.hacustomwidgets.BuildConfig.VERSION_NAME)
                        .put("device_name", "HA Custom Widgets Android").put("os_name", "Android")
                        .put("supports_encryption", false).apply { deviceInfo().forEach { (key, value) -> put(key, value) } })
                    webhook = result.optString("webhook_id").takeIf { it.isNotBlank() }
                    cloud = result.optString("remote_ui_url").takeUnless { result.isNull("remote_ui_url") }
                }
                if (webhook != null) {
                    var nativeConfig: JSONObject? = null
                    for (attempt in 0..3) {
                        try { nativeConfig = nativeConfig(endpoint, webhook); break }
                        catch (error: EndpointStatusException) {
                            if (error.status != 404 || attempt == 3) throw error
                            // mobile_app registration creates its config entry asynchronously.
                            Thread.sleep(250)
                        }
                    }
                    val resolved = requireNotNull(nativeConfig)
                    val received = resolved.optString("hass_device_id").takeUnless { resolved.isNull("hass_device_id") || it.isBlank() }
                    require(registrationDevice == null || received == registrationDevice) { "Home Assistant registration mismatch" }
                    registrationDevice = received ?: registrationDevice
                    cloud = resolved.optString("remote_ui_url").takeUnless { resolved.isNull("remote_ui_url") } ?: cloud
                }
            } catch (_: IOException) { /* Retain known metadata, expose unavailable remote access in UI. */ }
        }
        add(cloud, RouteKind.CLOUD)
        routes.add(ServerRoute(endpoint, RouteKind.DISCOVERED))
        return connection.copy(server = old.copy(registrationDeviceId = registrationDevice,
            name = config.optString("location_name", "Home Assistant"),
            routes = (routes + old.routes).distinctBy { it.url }.take(10), webhookId = webhook, deviceId = device,
            lastWorkingUrl = endpoint, lastSuccessAt = System.currentTimeMillis()))
    }

    fun nativeConfig(endpoint: String, webhook: String): JSONObject = request(endpoint,
        "/api/webhook/" + okhttp3.HttpUrl.Builder().scheme("https").host("localhost").addPathSegment(webhook).build().encodedPath.removePrefix("/"),
        null, JSONObject().put("type", "get_config"))

    fun canReuseDiscovered(connection: HomeAssistantConnection, endpoint: String): Boolean {
        // mDNS UUID/name/IP and /api/config are not authenticated instance identity.
        // Never send a bearer or webhook secret to a newly advertised DHCP address.
        return RouteTrustPolicy.isKnown(connection, endpoint)
    }

    fun acceptDiscovered(connection: HomeAssistantConnection, endpoint: String, userConfirmed: Boolean): HomeAssistantConnection {
        val url = OAuthPolicy.normalizeUrl(endpoint)
        require(userConfirmed || RouteTrustPolicy.isKnown(connection, url)) { "Confirm this Home Assistant address first" }
        val inspected = inspect(connection, url)
        return inspected.copy(server = inspected.server.copy(
            routes = (inspected.server.routes + ServerRoute(url, RouteKind.DISCOVERED)).distinctBy { it.url },
            // Adding a fallback must not displace a working primary endpoint.
            lastWorkingUrl = connection.server.lastWorkingUrl,
            lastSuccessAt = connection.server.lastSuccessAt))
    }
}

object RouteTrustPolicy {
    fun isKnown(connection: HomeAssistantConnection, endpoint: String): Boolean =
        endpoint == connection.baseUrl || connection.server.routes.any { it.url == endpoint }
}
