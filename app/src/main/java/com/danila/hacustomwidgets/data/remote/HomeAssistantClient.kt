package com.danila.hacustomwidgets.data.remote

import com.danila.hacustomwidgets.data.model.HaCatalog
import com.danila.hacustomwidgets.data.model.HaDevice
import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.security.HomeAssistantConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HomeAssistantClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun testConnection(connection: HomeAssistantConnection) = withContext(Dispatchers.IO) {
        execute(connection, "/api/").use { response ->
            if (!response.isSuccessful) throw apiError(response.code)
        }
    }

    suspend fun getEntities(connection: HomeAssistantConnection): List<HaEntity> =
        withContext(Dispatchers.IO) {
            execute(connection, "/api/states").use { response ->
                if (!response.isSuccessful) throw apiError(response.code)
                val body = response.body?.string() ?: throw IOException("Пустой ответ Home Assistant")
                val array = JSONArray(body)
                buildList {
                    for (index in 0 until array.length()) add(array.getJSONObject(index).toEntity())
                }.sortedBy { it.friendlyName.lowercase() }
            }
        }

    suspend fun getEntity(connection: HomeAssistantConnection, entityId: String): HaEntity =
        withContext(Dispatchers.IO) {
            execute(connection, "/api/states/${encodePathSegment(entityId)}").use { response ->
                if (!response.isSuccessful) throw apiError(response.code)
                val body = response.body?.string() ?: throw IOException("Пустой ответ Home Assistant")
                JSONObject(body).toEntity()
            }
        }

    suspend fun getEntities(
        connection: HomeAssistantConnection,
        entityIds: List<String>,
    ): List<HaEntity> = coroutineScope {
        entityIds.distinct().map { entityId -> async { getEntity(connection, entityId) } }.awaitAll()
    }

    suspend fun getCatalog(connection: HomeAssistantConnection): HaCatalog = coroutineScope {
        val statesDeferred = async { getEntities(connection) }
        val registriesDeferred = async { getRegistries(connection) }
        val states = statesDeferred.await()
        val registries = registriesDeferred.await()
        val devices = registries.devices.associateBy { it.id }
        val grouped = states
            .map { it.copy(deviceId = registries.entityDeviceIds[it.entityId]) }
            .groupBy { it.deviceId }

        val deviceGroups = grouped.entries
            .filter { it.key != null }
            .map { (deviceId, entities) ->
                HaDeviceGroup(
                    device = devices[deviceId] ?: HaDevice(deviceId.orEmpty(), "Неизвестное устройство"),
                    entities = entities.sortedBy { it.friendlyName.lowercase() },
                )
            }
            .sortedBy { it.title.lowercase() }
        val unassigned = grouped[null].orEmpty()
        HaCatalog(
            groups = deviceGroups + listOfNotNull(
                unassigned.takeIf { it.isNotEmpty() }?.let {
                    HaDeviceGroup(null, it.sortedBy { item -> item.friendlyName.lowercase() })
                },
            ),
        )
    }

    private suspend fun getRegistries(connection: HomeAssistantConnection): RegistrySnapshot =
        withContext(Dispatchers.IO) {
            withTimeout(20_000) {
                val result = CompletableDeferred<RegistrySnapshot>()
                val request = Request.Builder().url(webSocketUrl(connection)).build()
                var devices = emptyList<HaDevice>()
                val socket = http.newWebSocket(request, object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching {
                            val message = JSONObject(text)
                            when (message.optString("type")) {
                                "auth_required" -> webSocket.send(
                                    JSONObject()
                                        .put("type", "auth")
                                        .put("access_token", connection.token)
                                        .toString(),
                                )
                                "auth_invalid" -> throw IOException("Токен отклонён Home Assistant")
                                "auth_ok" -> webSocket.send(command(DEVICE_REQUEST_ID, "config/device_registry/list"))
                                "result" -> when (message.optInt("id")) {
                                    DEVICE_REQUEST_ID -> {
                                        ensureSuccessful(message)
                                        devices = parseDevices(message.getJSONArray("result"))
                                        webSocket.send(command(ENTITY_REQUEST_ID, "config/entity_registry/list"))
                                    }
                                    ENTITY_REQUEST_ID -> {
                                        ensureSuccessful(message)
                                        result.complete(
                                            RegistrySnapshot(
                                                devices,
                                                parseEntityDevices(message.getJSONArray("result")),
                                            ),
                                        )
                                        webSocket.close(1000, "done")
                                    }
                                }
                            }
                        }.onFailure {
                            result.completeExceptionally(it)
                            webSocket.close(1002, "invalid response")
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        result.completeExceptionally(
                            IOException("Не удалось загрузить реестр Home Assistant", t),
                        )
                    }
                })
                try {
                    result.await()
                } finally {
                    socket.cancel()
                }
            }
        }

    private fun parseDevices(array: JSONArray): List<HaDevice> = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            add(
                HaDevice(
                    id = id,
                    name = item.optNullableString("name_by_user")
                        ?: item.optNullableString("name")
                        ?: id,
                    manufacturer = item.optNullableString("manufacturer"),
                    model = item.optNullableString("model"),
                ),
            )
        }
    }

    private fun parseEntityDevices(array: JSONArray): Map<String, String?> = buildMap {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            if (item.optNullableString("disabled_by") == null) {
                put(item.getString("entity_id"), item.optNullableString("device_id"))
            }
        }
    }

    private fun command(id: Int, type: String) = JSONObject()
        .put("id", id)
        .put("type", type)
        .toString()

    private fun ensureSuccessful(message: JSONObject) {
        if (!message.optBoolean("success")) {
            val error = message.optJSONObject("error")?.optString("message")
            throw IOException(error?.takeIf { it.isNotBlank() } ?: "Ошибка Registry API Home Assistant")
        }
    }

    private fun webSocketUrl(connection: HomeAssistantConnection): String {
        val base = connection.baseUrl.toHttpUrl()
        val httpUrl = base.newBuilder()
            .addPathSegments("api/websocket")
            .build()
            .toString()
        return if (base.isHttps) httpUrl.replaceFirst("https://", "wss://")
        else httpUrl.replaceFirst("http://", "ws://")
    }

    private fun execute(connection: HomeAssistantConnection, path: String) = http.newCall(
        Request.Builder()
            .url(connection.baseUrl + path)
            .header("Authorization", "Bearer ${connection.token}")
            .header("Accept", "application/json")
            .get()
            .build(),
    ).execute()

    private fun JSONObject.toEntity(): HaEntity {
        val id = getString("entity_id")
        val attributes = optJSONObject("attributes") ?: JSONObject()
        return HaEntity(
            entityId = id,
            state = optString("state", "unknown"),
            friendlyName = attributes.optString("friendly_name", id),
            unit = attributes.optNullableString("unit_of_measurement"),
            lastUpdated = optNullableString("last_updated"),
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        optString(key).takeIf { !isNull(key) && it.isNotBlank() }

    private fun apiError(code: Int) = when (code) {
        401 -> IOException("Токен отклонён Home Assistant")
        404 -> IOException("Сущность не найдена")
        else -> IOException("Home Assistant вернул HTTP $code")
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private data class RegistrySnapshot(
        val devices: List<HaDevice>,
        val entityDeviceIds: Map<String, String?>,
    )

    private companion object {
        const val DEVICE_REQUEST_ID = 1
        const val ENTITY_REQUEST_ID = 2
    }
}
