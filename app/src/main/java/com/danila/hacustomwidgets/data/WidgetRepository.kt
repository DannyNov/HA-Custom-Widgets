package com.danila.hacustomwidgets.data

import android.content.Context
import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.security.SecureConnectionStore
import org.json.JSONArray
import org.json.JSONObject

data class WidgetMetric(
    val entityId: String,
    val label: String,
    val state: String,
)

data class WidgetConfig(
    val appWidgetId: Int,
    val deviceId: String?,
    val title: String,
    val metrics: List<WidgetMetric>,
    val showLastUpdated: Boolean,
    val lastUpdatedMillis: Long,
    val error: String? = null,
)

class WidgetRepository(context: Context) {
    private val prefs = context.getSharedPreferences("entity_widgets", Context.MODE_PRIVATE)

    fun saveConfiguration(
        appWidgetId: Int,
        group: HaDeviceGroup,
        entities: List<HaEntity>,
        showLastUpdated: Boolean,
    ) {
        val metrics = entities.map {
            WidgetMetric(
                entityId = it.entityId,
                label = compactMetricName(group.title, it.friendlyName),
                state = it.displayState,
            )
        }
        writeConfig(
            WidgetConfig(
                appWidgetId = appWidgetId,
                deviceId = group.device?.id,
                title = group.title,
                metrics = metrics,
                showLastUpdated = showLastUpdated,
                lastUpdatedMillis = System.currentTimeMillis(),
            ),
        )
    }

    fun updateStates(appWidgetId: Int, entities: List<HaEntity>) {
        val current = get(appWidgetId) ?: return
        val byId = entities.associateBy { it.entityId }
        writeConfig(
            current.copy(
                metrics = current.metrics.map { metric ->
                    byId[metric.entityId]?.let { metric.copy(state = it.displayState) } ?: metric
                },
                lastUpdatedMillis = System.currentTimeMillis(),
                error = null,
            ),
        )
    }

    fun saveError(appWidgetId: Int, message: String) {
        prefs.edit().putString(key(appWidgetId, "error"), message).apply()
    }

    fun get(appWidgetId: Int): WidgetConfig? {
        val metricsJson = prefs.getString(key(appWidgetId, "metrics"), null)
        if (metricsJson != null) {
            val metrics = runCatching { parseMetrics(metricsJson) }.getOrDefault(emptyList())
            if (metrics.isEmpty()) return null
            return WidgetConfig(
                appWidgetId = appWidgetId,
                deviceId = prefs.getString(key(appWidgetId, "device_id"), null),
                title = prefs.getString(key(appWidgetId, "title"), "Home Assistant") ?: "Home Assistant",
                metrics = metrics,
                showLastUpdated = prefs.getBoolean(key(appWidgetId, "show_updated"), true),
                lastUpdatedMillis = prefs.getLong(key(appWidgetId, "updated"), 0L),
                error = prefs.getString(key(appWidgetId, "error"), null),
            )
        }
        return readLegacyConfig(appWidgetId)
    }

    fun all(): List<WidgetConfig> = configuredIds().mapNotNull { it.toIntOrNull()?.let(::get) }

    fun delete(appWidgetId: Int) {
        val editor = prefs.edit()
        listOf(
            "device_id", "title", "metrics", "show_updated", "updated", "error",
            "entity_id", "state",
        ).forEach { editor.remove(key(appWidgetId, it)) }
        editor.putStringSet(KEY_IDS, configuredIds().minus(appWidgetId.toString())).apply()
    }

    private fun writeConfig(config: WidgetConfig) {
        prefs.edit()
            .putString(key(config.appWidgetId, "device_id"), config.deviceId)
            .putString(key(config.appWidgetId, "title"), config.title)
            .putString(key(config.appWidgetId, "metrics"), metricsJson(config.metrics))
            .putBoolean(key(config.appWidgetId, "show_updated"), config.showLastUpdated)
            .putLong(key(config.appWidgetId, "updated"), config.lastUpdatedMillis)
            .apply {
                if (config.error == null) remove(key(config.appWidgetId, "error"))
                else putString(key(config.appWidgetId, "error"), config.error)
            }
            .putStringSet(KEY_IDS, configuredIds().plus(config.appWidgetId.toString()))
            .apply()
    }

    private fun readLegacyConfig(appWidgetId: Int): WidgetConfig? {
        val entityId = prefs.getString(key(appWidgetId, "entity_id"), null) ?: return null
        val title = prefs.getString(key(appWidgetId, "title"), entityId) ?: entityId
        return WidgetConfig(
            appWidgetId = appWidgetId,
            deviceId = null,
            title = title,
            metrics = listOf(
                WidgetMetric(
                    entityId = entityId,
                    label = entityId.substringAfter('.').replace('_', ' '),
                    state = prefs.getString(key(appWidgetId, "state"), "—") ?: "—",
                ),
            ),
            showLastUpdated = true,
            lastUpdatedMillis = prefs.getLong(key(appWidgetId, "updated"), 0L),
            error = prefs.getString(key(appWidgetId, "error"), null),
        )
    }

    private fun metricsJson(metrics: List<WidgetMetric>): String {
        val array = JSONArray()
        metrics.forEach { metric ->
            array.put(
                JSONObject()
                    .put("entity_id", metric.entityId)
                    .put("label", metric.label)
                    .put("state", metric.state),
            )
        }
        return array.toString()
    }

    private fun parseMetrics(json: String): List<WidgetMetric> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    WidgetMetric(
                        entityId = item.getString("entity_id"),
                        label = item.optString("label", item.getString("entity_id")),
                        state = item.optString("state", "—"),
                    ),
                )
            }
        }
    }

    private fun configuredIds(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet())?.toSet().orEmpty()
    private fun key(id: Int, suffix: String) = "widget_${id}_$suffix"

    companion object {
        private const val KEY_IDS = "configured_widget_ids"

        internal fun compactMetricName(deviceName: String, entityName: String): String {
            val trimmed = entityName.trim()
            if (trimmed.equals(deviceName, ignoreCase = true)) return trimmed
            val prefix = deviceName.trim().takeIf { it.isNotBlank() } ?: return trimmed
            return trimmed.removePrefixIgnoringCase("$prefix ").ifBlank { trimmed }
        }

        private fun String.removePrefixIgnoringCase(prefix: String): String =
            if (startsWith(prefix, ignoreCase = true)) drop(prefix.length).trim() else this
    }
}

class AppContainer(context: Context) {
    val connectionStore = SecureConnectionStore(context)
    val client = HomeAssistantClient()
    val widgets = WidgetRepository(context)
}
