package com.danila.hacustomwidgets.data.remote

import com.danila.hacustomwidgets.data.model.HaCatalog
import com.danila.hacustomwidgets.data.model.HaArea
import com.danila.hacustomwidgets.data.model.HaDevice
import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.model.HaFloor
import com.danila.hacustomwidgets.data.security.HomeAssistantConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
            .mapNotNull { entity ->
                val registry = registries.entities[entity.entityId]
                if (registry?.disabledBy != null) return@mapNotNull null
                entity.copy(
                    deviceId = registry?.deviceId,
                    areaId = registry?.areaId,
                    entityCategory = registry?.entityCategory,
                    hiddenBy = registry?.hiddenBy,
                    disabledBy = registry?.disabledBy,
                )
            }
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
        val unassignedGroups = grouped[null].orEmpty()
            .sortedBy { it.friendlyName.lowercase() }
            .map { entity ->
                HaDeviceGroup(
                    device = null,
                    entities = listOf(entity),
                    syntheticKey = "entity:${entity.entityId}",
                    syntheticTitle = entity.friendlyName,
                )
            }
        HaCatalog(
            groups = deviceGroups + unassignedGroups,
            areas = registries.areas,
            floors = registries.floors,
        )
    }

    suspend fun callService(
        connection: HomeAssistantConnection,
        domain: String,
        service: String,
        entityId: String,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("entity_id", entityId).toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        http.newCall(
            Request.Builder()
                .url(connection.baseUrl + "/api/services/$domain/$service")
                .header("Authorization", "Bearer ${connection.token}")
                .header("Accept", "application/json")
                .post(body)
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw apiError(response.code)
        }
    }

    fun openStateChangedWebSocket(
        connection: HomeAssistantConnection,
        onSubscribed: () -> Unit,
        onStateChanged: (HaEntity) -> Unit,
        onClosed: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): WebSocket {
        val request = Request.Builder().url(webSocketUrl(connection)).build()
        return http.newWebSocket(request, object : WebSocketListener() {
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
                        "auth_ok" -> webSocket.send(
                            JSONObject()
                                .put("id", STATE_CHANGED_SUBSCRIPTION_ID)
                                .put("type", "subscribe_events")
                                .put("event_type", "state_changed")
                                .toString(),
                        )
                        "result" -> if (message.optInt("id") == STATE_CHANGED_SUBSCRIPTION_ID) {
                            ensureSuccessful(message)
                            onSubscribed()
                        }
                        "event" -> if (message.optInt("id") == STATE_CHANGED_SUBSCRIPTION_ID) {
                            val newState = message.optJSONObject("event")
                                ?.optJSONObject("data")
                                ?.optJSONObject("new_state")
                            if (newState != null) onStateChanged(newState.toEntity())
                        }
                    }
                }.onFailure {
                    onFailure(it)
                    webSocket.close(1002, "invalid response")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onFailure(IOException("Соединение событий Home Assistant потеряно", t))
            }
        })
    }

    private suspend fun getRegistries(connection: HomeAssistantConnection): RegistrySnapshot =
        withContext(Dispatchers.IO) {
            withTimeout(20_000) {
                val result = CompletableDeferred<RegistrySnapshot>()
                val request = Request.Builder().url(webSocketUrl(connection)).build()
                var devices = emptyList<HaDevice>()
                var entities = emptyMap<String, RegistryEntity>()
                var areas = emptyList<HaArea>()
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
                                        entities = parseEntities(message.getJSONArray("result"))
                                        webSocket.send(command(AREA_REQUEST_ID, "config/area_registry/list"))
                                    }
                                    AREA_REQUEST_ID -> {
                                        ensureSuccessful(message)
                                        areas = parseAreas(message.getJSONArray("result"))
                                        webSocket.send(command(FLOOR_REQUEST_ID, "config/floor_registry/list"))
                                    }
                                    FLOOR_REQUEST_ID -> {
                                        val floors = if (message.optBoolean("success")) {
                                            parseFloors(message.getJSONArray("result"))
                                        } else emptyList()
                                        result.complete(RegistrySnapshot(devices, entities, areas, floors))
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
                    areaId = item.optNullableString("area_id"),
                ),
            )
        }
    }

    private fun parseEntities(array: JSONArray): Map<String, RegistryEntity> = buildMap {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            put(
                item.getString("entity_id"),
                RegistryEntity(
                    deviceId = item.optNullableString("device_id"),
                    areaId = item.optNullableString("area_id"),
                    entityCategory = item.optNullableString("entity_category"),
                    hiddenBy = item.optNullableString("hidden_by"),
                    disabledBy = item.optNullableString("disabled_by"),
                ),
            )
        }
    }

    private fun parseAreas(array: JSONArray): List<HaArea> = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(HaArea(item.getString("area_id"), item.getString("name"), item.optNullableString("floor_id")))
        }
    }.sortedBy { it.name.lowercase() }

    private fun parseFloors(array: JSONArray): List<HaFloor> = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                HaFloor(
                    id = item.getString("floor_id"),
                    name = item.getString("name"),
                    level = item.optInt("level").takeIf { !item.isNull("level") },
                ),
            )
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
            deviceClass = attributes.optNullableString("device_class"),
            icon = attributes.optNullableString("icon"),
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
        val entities: Map<String, RegistryEntity>,
        val areas: List<HaArea>,
        val floors: List<HaFloor>,
    )

    private data class RegistryEntity(
        val deviceId: String?,
        val areaId: String?,
        val entityCategory: String?,
        val hiddenBy: String?,
        val disabledBy: String?,
    )

    private companion object {
        const val DEVICE_REQUEST_ID = 1
        const val ENTITY_REQUEST_ID = 2
        const val AREA_REQUEST_ID = 3
        const val FLOOR_REQUEST_ID = 4
        const val STATE_CHANGED_SUBSCRIPTION_ID = 10_001
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
