package com.danila.hacustomwidgets.data.security

import org.json.JSONArray
import org.json.JSONObject

enum class RouteKind { DISCOVERED, INTERNAL, EXTERNAL, CLOUD }
data class ServerRoute(val url: String, val kind: RouteKind)

/** Credentials belong to the server; baseUrl is only the original, stable configuration key. */
data class ServerMetadata(
    // Legacy field retained for storage compatibility; never an identity/trust proof.
    val instanceId: String? = null,
    val name: String = "Home Assistant",
    val routes: List<ServerRoute> = emptyList(),
    val lastWorkingUrl: String? = null,
    val lastSuccessAt: Long = 0,
    val webhookId: String? = null,
    val deviceId: String? = null,
    val registrationDeviceId: String? = null,
) {
    override fun toString() = "ServerMetadata(routes=${routes.size})"
    fun toJson(): JSONObject = JSONObject().put("instance", instanceId).put("name", name)
        .put("routes", JSONArray(routes.map { JSONObject().put("url", it.url).put("kind", it.kind.name) }))
        .put("last", lastWorkingUrl).put("success", lastSuccessAt).put("webhook", webhookId).put("device", deviceId)
        .put("registration_device", registrationDeviceId)

    companion object {
        fun fromJson(json: JSONObject?): ServerMetadata {
            if (json == null) return ServerMetadata()
            fun string(key: String) = json.optString(key).takeIf { !json.isNull(key) && it.isNotBlank() }
            val array = json.optJSONArray("routes") ?: JSONArray()
            return ServerMetadata(string("instance"), string("name") ?: "Home Assistant",
                (0 until array.length()).mapNotNull { index -> runCatching {
                    val route = array.getJSONObject(index)
                    ServerRoute(OAuthPolicy.normalizeUrl(route.getString("url")), RouteKind.valueOf(route.getString("kind")))
                }.getOrNull() }, string("last"), json.optLong("success"), string("webhook"), string("device"), string("registration_device"))
        }
    }
}
