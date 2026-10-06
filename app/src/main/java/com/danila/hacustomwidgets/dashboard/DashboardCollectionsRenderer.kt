package com.danila.hacustomwidgets.dashboard

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.glance.unit.ColorProvider
import com.danila.hacustomwidgets.R
import org.json.JSONObject

/** Native persistent collection shells; only the active adapter is sent in an update. */
internal object DashboardCollectionsRenderer {
    internal data class Collection(val body: RemoteViews, val viewId: Int, val items: RemoteViews.RemoteCollectionItems)
    @Synchronized
    private fun slots(context: Context, widgetId: Int, ids: List<String>): Map<String, Int> {
        val prefs = context.getSharedPreferences("dashboard_collection_ids", Context.MODE_PRIVATE)
        val key = "widget_$widgetId"
        val stored = JSONObject(prefs.getString(key, "{}") ?: "{}")
        var changed = false
        ids.forEach { id ->
            if (!stored.has(id)) {
                stored.put(id, 0x00e00000 + stored.length())
                changed = true
            }
        }
        if (changed) prefs.edit().putString(key, stored.toString()).commit()
        return ids.associateWith { stored.getInt(it) }
    }

    fun forget(context: Context, widgetId: Int) {
        context.getSharedPreferences("dashboard_collection_ids", Context.MODE_PRIVATE)
            .edit().remove("widget_$widgetId").apply()
    }

    internal fun template(context: Context): PendingIntent = PendingIntent.getActivity(context, 0, Intent(), Intent.FILL_IN_COMPONENT or
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT)

    suspend fun render(context: Context, state: DashboardState, widgetId: Int, size: DpSize): Collection {
        val ids = listOf(MAIN_TAB_ID, SCENARIOS_TAB_ID, MAINTENANCE_TAB_ID, EMPTY_TAB_ID) + state.spaces.map { it.id }.sorted()
        val slots = slots(context, widgetId, ids)
        val selected = state.selectedTab.id
        val expanded = state.copy(selectedTabId = selected, config = state.config.copy(
            showFavorites = true, scenariosEnabled = true, showMaintenance = true,
            visibleSpaceIds = state.spaces.map { it.id }))
        val collectionId = slots.getValue(selected)
        val items = composeGlanceCollection(context, widgetId, collectionId, size) {
            DashboardSpaceList(expanded, selected, widgetId, size.width.value.toInt().coerceAtLeast(180),
                ColorProvider(R.color.widget_primary), ColorProvider(R.color.widget_secondary))
        }
        val body = RemoteViews(context.packageName, R.layout.dashboard_collection_shell).apply {
            removeAllViews(R.id.dashboard_collection_slots)
            ids.forEach { id ->
                val viewId = slots.getValue(id)
                val list = RemoteViews(context.packageName, R.layout.dashboard_collection_list, viewId).apply {
                    setViewVisibility(viewId, if (id == selected) View.VISIBLE else View.GONE)
                    // Omitting inactive adapter actions keeps their data and viewport in the host.
                    // A newly inflated inactive shell needs no data until first selected.
                }
                addStableView(R.id.dashboard_collection_slots, list, viewId)
            }
        }
        return Collection(body, collectionId, items)
    }

    suspend fun dashboard(context: Context, state: DashboardState?, widgetId: Int, size: DpSize): RemoteViews {
        val collection = state?.let { render(context, it, widgetId, size) }
        val chrome = composeGlanceDashboard(context, widgetId, size) {
            DashboardContent(context, state, widgetId, size, collection?.body)
        }
        return RemoteViews(context.packageName, R.layout.dashboard_collection_root).apply {
            removeAllViews(R.id.dashboard_collection_root)
            addStableView(R.id.dashboard_collection_root, chrome, 1)
            // Android 12 checks the rootParent, so these actions MUST be on the
            // outer RemoteViews delivered to AppWidgetHostView, never on a nested shell.
            collection?.let {
                setPendingIntentTemplate(it.viewId, template(context))
                setRemoteAdapter(it.viewId, it.items)
            }
        }
    }
}
