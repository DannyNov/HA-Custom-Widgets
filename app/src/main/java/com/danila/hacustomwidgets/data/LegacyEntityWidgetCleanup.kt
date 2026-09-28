package com.danila.hacustomwidgets.data

import android.content.Context
import androidx.work.WorkManager
import java.io.File

/** Upgrade bridge for <= 0.6.1.1. Run before constructing any Dashboard/Glance stores.
 * The framework removes the missing provider but does not call its onDeleted callback.
 * Clear the dedicated legacy store last so interrupted cleanup is retried next start.
 */
object LegacyEntityWidgetCleanup {
    fun run(context: Context) {
        // This is only Glance's generated receiver-to-class index, not widget configuration.
        // Before Glance is initialized it is safe to invalidate the old index; the manager
        // reconstructs it from installed providers on first access, including Dashboard.
        val index = File(context.filesDir, "datastore/GlanceAppWidgetManager.preferences_pb")
        runCatching {
            if (index.isFile && index.readText().contains(
                    "com.danila.hacustomwidgets.widget.EntityStateWidgetReceiver")) index.delete()
        }
        val legacy = context.getSharedPreferences("entity_widgets", Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        val dashboard = context.getSharedPreferences("dashboard_widgets", Context.MODE_PRIVATE)
        val dashboardIds = dashboard.getStringSet("configured_dashboard_ids", emptySet()).orEmpty()
        val ids = legacy.getStringSet("configured_widget_ids", emptySet()).orEmpty() +
            legacy.all.keys.mapNotNull { Regex("widget_(\\d+)_.*").matchEntire(it)?.groupValues?.get(1) }
        val freshness = context.getSharedPreferences("dashboard_sync_freshness", Context.MODE_PRIVATE).edit()
        var complete = true
        ids.mapNotNull(String::toIntOrNull).filter { it.toString() !in dashboardIds }.forEach { id ->
            WorkManager.getInstance(context).cancelUniqueWork("appWidget-$id")
            freshness.remove("widget_${id}_last_confirmed_sync")
            // Glance 1.1.1's per-instance state and layout cache; never touch other IDs.
            listOf("appWidget-$id.preferences_pb", "appWidgetLayout-$id").forEach { name ->
                val file = File(context.filesDir, "datastore/$name")
                if (file.exists() && !file.delete()) complete = false
            }
        }
        if (!freshness.commit()) complete = false
        if (complete) legacy.edit().clear().commit()
    }
}
