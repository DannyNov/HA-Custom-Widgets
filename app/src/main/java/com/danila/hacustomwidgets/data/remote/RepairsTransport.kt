package com.danila.hacustomwidgets.data.remote

import com.danila.hacustomwidgets.dashboard.MaintenancePolicy
import com.danila.hacustomwidgets.dashboard.RepairIssue
import com.danila.hacustomwidgets.data.security.HomeAssistantConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException

/** Read-only snapshot; separate bounded request socket, same HA connection/token. */
internal suspend fun fetchRepairs(http: OkHttpClient, connection: HomeAssistantConnection): List<RepairIssue> = withTimeout(20_000) {
    val result = CompletableDeferred<List<RepairIssue>>()
    val base = connection.baseUrl.toHttpUrl()
    val url = base.newBuilder().addPathSegments("api/websocket").build().toString()
        .replaceFirst(if (base.isHttps) "https://" else "http://", if (base.isHttps) "wss://" else "ws://")
    var issues = emptyList<RepairIssue>()
    var titles = emptyMap<String, String>()
    val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
        fun translation(socket: WebSocket, id: Int, language: String) {
            socket.send(JSONObject().put("id", id).put("type", "frontend/get_translations").put("language", language)
                .put("category", "issues").put("integration", JSONArray(issues.map { it.domain }.distinct())).toString())
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val message = JSONObject(text)
                when (message.optString("type")) {
                    "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", connection.token).toString())
                    "auth_invalid" -> throw IOException("HA authentication failed")
                    "auth_ok" -> webSocket.send(JSONObject().put("id", 1).put("type", "repairs/list_issues").toString())
                    "result" -> when (message.getInt("id")) {
                        1 -> {
                            if (!message.optBoolean("success")) throw IOException("Repairs API unavailable")
                            issues = MaintenancePolicy.parseIssues(message.getJSONObject("result"))
                            if (issues.isEmpty()) result.complete(issues) else translation(webSocket, 2, "en")
                        }
                        2 -> {
                            val resources = message.optJSONObject("result")?.optJSONObject("resources") ?: JSONObject()
                            titles = issues.mapNotNull { issue -> MaintenancePolicy.localizedTitle(issue, resources)?.let { "${issue.domain}:${issue.issueId}" to it } }.toMap()
                            translation(webSocket, 3, "ru")
                        }
                        3 -> {
                            val resources = message.optJSONObject("result")?.optJSONObject("resources") ?: JSONObject()
                            result.complete(issues.map { issue -> issue.copy(titles = buildMap {
                                titles["${issue.domain}:${issue.issueId}"]?.let { put("en", it) }
                                MaintenancePolicy.localizedTitle(issue, resources)?.let { put("ru", it) }
                            }) })
                        }
                    }
                }
            }.onFailure { result.completeExceptionally(it) }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (!result.isCompleted) result.completeExceptionally(IOException("Repairs connection closed")) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { result.completeExceptionally(IOException("Repairs connection failed", t)) }
    })
    try { result.await() } finally { socket.cancel() }
}

internal val maintenanceRegistryEvents = setOf("repairs_issue_registry_updated", "entity_registry_updated", "device_registry_updated", "area_registry_updated")
