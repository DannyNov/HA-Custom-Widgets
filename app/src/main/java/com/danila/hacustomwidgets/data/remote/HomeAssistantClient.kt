package com.danila.hacustomwidgets.data.remote

import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.security.HomeAssistantConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HomeAssistantClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
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
            unit = attributes.optString("unit_of_measurement").takeIf { it.isNotBlank() },
            lastUpdated = optString("last_updated").takeIf { it.isNotBlank() },
        )
    }

    private fun apiError(code: Int) = when (code) {
        401 -> IOException("Токен отклонён Home Assistant")
        404 -> IOException("Сущность не найдена")
        else -> IOException("Home Assistant вернул HTTP $code")
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
