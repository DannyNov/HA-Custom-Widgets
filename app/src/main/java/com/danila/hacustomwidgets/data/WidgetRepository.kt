package com.danila.hacustomwidgets.data

import android.content.Context
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.security.SecureConnectionStore

data class WidgetConfig(
    val appWidgetId: Int,
    val entityId: String,
    val title: String,
    val state: String,
    val lastUpdatedMillis: Long,
    val error: String? = null,
)

class WidgetRepository(context: Context) {
    private val prefs = context.getSharedPreferences("entity_widgets", Context.MODE_PRIVATE)

    fun save(appWidgetId: Int, entity: HaEntity) {
        prefs.edit()
            .putString(key(appWidgetId, "entity_id"), entity.entityId)
            .putString(key(appWidgetId, "title"), entity.friendlyName)
            .putString(key(appWidgetId, "state"), entity.displayState)
            .putLong(key(appWidgetId, "updated"), System.currentTimeMillis())
            .remove(key(appWidgetId, "error"))
            .putStringSet(KEY_IDS, configuredIds().plus(appWidgetId.toString()))
            .apply()
    }

    fun saveError(appWidgetId: Int, message: String) {
        prefs.edit().putString(key(appWidgetId, "error"), message).apply()
    }

    fun get(appWidgetId: Int): WidgetConfig? {
        val entityId = prefs.getString(key(appWidgetId, "entity_id"), null) ?: return null
        return WidgetConfig(
            appWidgetId = appWidgetId,
            entityId = entityId,
            title = prefs.getString(key(appWidgetId, "title"), entityId) ?: entityId,
            state = prefs.getString(key(appWidgetId, "state"), "—") ?: "—",
            lastUpdatedMillis = prefs.getLong(key(appWidgetId, "updated"), 0L),
            error = prefs.getString(key(appWidgetId, "error"), null),
        )
    }

    fun all(): List<WidgetConfig> = configuredIds().mapNotNull { it.toIntOrNull()?.let(::get) }

    fun delete(appWidgetId: Int) {
        val editor = prefs.edit()
        listOf("entity_id", "title", "state", "updated", "error")
            .forEach { editor.remove(key(appWidgetId, it)) }
        editor.putStringSet(KEY_IDS, configuredIds().minus(appWidgetId.toString())).apply()
    }

    private fun configuredIds(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet())?.toSet().orEmpty()
    private fun key(id: Int, suffix: String) = "widget_${id}_$suffix"

    companion object { private const val KEY_IDS = "configured_widget_ids" }
}

class AppContainer(context: Context) {
    val connectionStore = SecureConnectionStore(context)
    val client = HomeAssistantClient()
    val widgets = WidgetRepository(context)
}
