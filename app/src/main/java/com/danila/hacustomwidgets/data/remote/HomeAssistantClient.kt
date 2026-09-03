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

interface StateChangedWebSocketListener {
    fun onOpen(socket: WebSocket)
    fun onMessage(socket: WebSocket, type: String)
    fun onAuthRequired(socket: WebSocket)
    fun onAuthOk(socket: WebSocket, haVersion: String)
    fun onAuthInvalid(socket: WebSocket)
    fun onSubscriptionResult(socket: WebSocket, subscriptionId: Int, success: Boolean, error: String?)
    fun onEntities(socket: WebSocket, subscriptionId: Int, entities: List<HaEntity>, initial: Boolean)
    fun onClosed(socket: WebSocket, code: Int, reason: String)
    fun onFailure(socket: WebSocket, error: Throwable)
}

enum class EntitySubscriptionMode { SUBSCRIBE_ENTITIES, STATE_CHANGED }

class HomeAssistantClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        // A single transport heartbeat detects half-open TCP connections. There is deliberately
        // no application ping/watchdog loop: NotificationListenerService owns process lifetime.
        .pingInterval(60, TimeUnit.SECONDS)
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
        serviceData: Map<String, *> = emptyMap<String, String>(),
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("entity_id", entityId).apply {
            serviceData.forEach { (key, value) -> put(key, value) }
        }.toString()
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
        listener: StateChangedWebSocketListener,
    ): WebSocket {
        val request = Request.Builder().url(webSocketUrl(connection)).build()
        val compressedParsers = linkedMapOf<Int, CompressedEntitySubscriptionParser>()
        return http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val message = JSONObject(text)
                    val type = message.optString("type")
                    listener.onMessage(webSocket, type)
                    when (type) {
                        "auth_required" -> {
                            listener.onAuthRequired(webSocket)
                            webSocket.send(JSONObject()
                                .put("type", "auth")
                                .put("access_token", connection.token)
                                .toString())
                        }
                        "auth_invalid" -> {
                            listener.onAuthInvalid(webSocket)
                            throw IOException("Токен отклонён Home Assistant")
                        }
                        "auth_ok" -> listener.onAuthOk(webSocket, message.optString("ha_version"))
                        "result" -> {
                            val error = message.optJSONObject("error")?.optString("message")
                            listener.onSubscriptionResult(
                                webSocket, message.optInt("id"), message.optBoolean("success"), error,
                            )
                        }
                        "event" -> {
                            val subscriptionId = message.optInt("id")
                            val event = message.optJSONObject("event") ?: return@runCatching
                            val newState = event.optJSONObject("data")?.optJSONObject("new_state")
                            if (newState != null) {
                                listener.onEntities(webSocket, subscriptionId, listOf(newState.toEntity()), false)
                            } else {
                                val parser = compressedParsers.getOrPut(subscriptionId) {
                                    if (compressedParsers.size >= MAX_RETAINED_SUBSCRIPTION_PARSERS) {
                                        compressedParsers.remove(compressedParsers.keys.first())
                                    }
                                    CompressedEntitySubscriptionParser()
                                }
                                val parsed = parser.apply(event)
                                listener.onEntities(webSocket, subscriptionId, parsed.entities, parsed.initial)
                            }
                        }
                    }
                }.onFailure {
                    listener.onFailure(webSocket, it)
                    webSocket.close(1002, "invalid response")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onFailure(webSocket, IOException("Соединение событий Home Assistant потеряно", t))
            }
        })
    }

    fun subscribeEntities(
        socket: WebSocket,
        subscriptionId: Int,
        entityIds: Set<String>,
        mode: EntitySubscriptionMode,
    ): Boolean {
        return socket.send(entitySubscriptionCommand(subscriptionId, entityIds, mode))
    }

    fun unsubscribe(socket: WebSocket, commandId: Int, subscriptionId: Int): Boolean =
        socket.send(unsubscribeCommand(commandId, subscriptionId))

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
            lastChanged = optNullableString("last_changed"),
            deviceClass = attributes.optNullableString("device_class"),
            icon = attributes.optNullableString("icon"),
            timerDuration = attributes.optNullableString("duration"),
            timerRemaining = attributes.optNullableString("remaining"),
            timerFinishesAt = attributes.optNullableString("finishes_at"),
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
        const val MAX_RETAINED_SUBSCRIPTION_PARSERS = 4
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun entitySubscriptionCommand(
    subscriptionId: Int,
    entityIds: Set<String>,
    mode: EntitySubscriptionMode,
): String = JSONObject().put("id", subscriptionId).apply {
    when (mode) {
        EntitySubscriptionMode.SUBSCRIBE_ENTITIES -> {
            put("type", "subscribe_entities")
            put("entity_ids", JSONArray(entityIds.sorted()))
        }
        EntitySubscriptionMode.STATE_CHANGED -> {
            put("type", "subscribe_events")
            put("event_type", "state_changed")
        }
    }
}.toString()

internal fun unsubscribeCommand(commandId: Int, subscriptionId: Int): String =
    JSONObject().put("id", commandId).put("type", "unsubscribe_events")
        .put("subscription", subscriptionId).toString()

data class EntitySubscriptionUpdate(val entities: List<HaEntity>, val initial: Boolean)

/** Stateful decoder for Home Assistant's subscribe_entities compact event protocol. */
internal class CompressedEntitySubscriptionParser {
    private val states = linkedMapOf<String, HaEntity>()

    fun apply(event: JSONObject): EntitySubscriptionUpdate {
        val initial = event.has("a")
        event.optJSONObject("a")?.let { added ->
            added.keys().forEach { entityId ->
                states[entityId] = fullEntity(entityId, added.getJSONObject(entityId))
            }
        }
        event.optJSONObject("c")?.let { changed ->
            changed.keys().forEach { entityId ->
                val current = states[entityId]
                val change = changed.getJSONObject(entityId)
                val plus = change.optJSONObject("+") ?: JSONObject()
                val attributes = entityAttributes(current, plus.optJSONObject("a"))
                change.optJSONObject("-")?.optJSONArray("a")?.let { removed ->
                    for (index in 0 until removed.length()) attributes.remove(removed.getString(index))
                }
                states[entityId] = HaEntity(
                    entityId = entityId,
                    state = plus.optString("s", current?.state ?: "unknown"),
                    friendlyName = attributes.optString("friendly_name", entityId),
                    unit = attributes.optNullableString("unit_of_measurement"),
                    lastUpdated = epochSeconds(plus, "lu") ?: current?.lastUpdated,
                    lastChanged = epochSeconds(plus, "lc") ?: current?.lastChanged,
                    deviceClass = attributes.optNullableString("device_class"),
                    icon = attributes.optNullableString("icon"),
                    timerDuration = attributes.optNullableString("duration"),
                    timerRemaining = attributes.optNullableString("remaining"),
                    timerFinishesAt = attributes.optNullableString("finishes_at"),
                )
            }
        }
        val removedStates = mutableListOf<HaEntity>()
        event.optJSONArray("r")?.let { removed ->
            for (index in 0 until removed.length()) {
                val entityId = removed.getString(index)
                val previous = states.remove(entityId)
                removedStates += HaEntity(
                    entityId = entityId,
                    state = "unavailable",
                    friendlyName = previous?.friendlyName ?: entityId,
                    unit = null,
                    lastUpdated = java.time.Instant.now().toString(),
                    deviceClass = previous?.deviceClass,
                    icon = previous?.icon,
                )
            }
        }
        val affected = linkedSetOf<String>().apply {
            event.optJSONObject("a")?.keys()?.let { keys -> while (keys.hasNext()) add(keys.next()) }
            event.optJSONObject("c")?.keys()?.let { keys -> while (keys.hasNext()) add(keys.next()) }
        }
        return EntitySubscriptionUpdate(affected.mapNotNull(states::get) + removedStates, initial)
    }

    private fun fullEntity(entityId: String, value: JSONObject): HaEntity {
        val attributes = value.optJSONObject("a") ?: JSONObject()
        return HaEntity(
            entityId = entityId,
            state = value.optString("s", "unknown"),
            friendlyName = attributes.optString("friendly_name", entityId),
            unit = attributes.optNullableString("unit_of_measurement"),
            lastUpdated = epochSeconds(value, "lu"),
            lastChanged = epochSeconds(value, "lc"),
            deviceClass = attributes.optNullableString("device_class"),
            icon = attributes.optNullableString("icon"),
            timerDuration = attributes.optNullableString("duration"),
            timerRemaining = attributes.optNullableString("remaining"),
            timerFinishesAt = attributes.optNullableString("finishes_at"),
        )
    }

    private fun entityAttributes(current: HaEntity?, updates: JSONObject?): JSONObject = JSONObject().apply {
        current?.friendlyName?.let { put("friendly_name", it) }
        current?.unit?.let { put("unit_of_measurement", it) }
        current?.deviceClass?.let { put("device_class", it) }
        current?.icon?.let { put("icon", it) }
        current?.timerDuration?.let { put("duration", it) }
        current?.timerRemaining?.let { put("remaining", it) }
        current?.timerFinishesAt?.let { put("finishes_at", it) }
        updates?.keys()?.let { keys -> while (keys.hasNext()) keys.next().let { put(it, updates.get(it)) } }
    }

    private fun epochSeconds(value: JSONObject, key: String): String? =
        if (!value.has(key) || value.isNull(key)) null
        else java.time.Instant.ofEpochMilli((value.getDouble(key) * 1_000.0).toLong()).toString()

    private fun JSONObject.optNullableString(key: String): String? =
        optString(key).takeIf { !isNull(key) && it.isNotBlank() }
}
