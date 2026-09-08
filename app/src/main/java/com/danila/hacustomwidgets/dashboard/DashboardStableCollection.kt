package com.danila.hacustomwidgets.dashboard

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.R

internal object StableCollectionPolicy {
    fun use(api: Int) = api in 26..30
    fun identity(widgetId: Int) = "hacw://dashboard/$widgetId/collection/static-v2"
}

internal object DashboardStableCollection {
    internal fun notifyDataChanged(manager: AppWidgetManager, id: Int) =
        manager.notifyAppWidgetViewDataChanged(id, R.id.legacy_list)

    fun buildViews(context: Context, id: Int, state: DashboardState?, initial: Boolean,
        adapterIntent: Intent = Intent(context, DashboardStableService::class.java)
            .setData(Uri.parse(StableCollectionPolicy.identity(id)))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)): RemoteViews =
        DashboardLegacyCollection.buildViews(context, id, state, initial, adapterIntent)

    fun update(context: Context, id: Int, bind: Boolean = false) {
        val manager = AppWidgetManager.getInstance(context)
        val prefs = context.getSharedPreferences("dashboard_static_hosts_v2", Context.MODE_PRIVATE)
        val initial = bind || !prefs.getBoolean("bound:$id", false)
        val state = (context.applicationContext as HaWidgetApplication).container.dashboards.get(id)
        DashboardPipelineDiagnostics.config("RENDER_CONFIG", state?.config)
        DashboardPipelineDiagnostics.event(id, state?.stateRevision, "COLLECTION_DECISION",
            "bind=$bind persistedBound=${prefs.getBoolean("bound:$id", false)} initial=$initial stateExists=${state != null} adapter=${StableCollectionPolicy.identity(id)}")
        val views = buildViews(context, id, state, initial)
        if (initial) {
            manager.updateAppWidget(id, views)
            DashboardPipelineDiagnostics.event(id, state?.stateRevision, "COLLECTION_PUBLISHED", "outer=true adapterBound=true")
            prefs.edit().putBoolean("bound:$id", true).apply()
        }
        // API 26 may replace the entire ListView even for a partial outer RemoteViews update.
        // Routine state changes therefore update only the already-bound collection adapter.
        notifyDataChanged(manager, id)
        DashboardPipelineDiagnostics.event(id, state?.stateRevision, "COLLECTION_NOTIFIED", "outerPublished=$initial listId=${R.id.legacy_list}")
    }

    fun forget(context: Context, id: Int) {
        context.getSharedPreferences("dashboard_static_hosts_v2", Context.MODE_PRIVATE).edit().remove("bound:$id").apply()
    }
}

class DashboardStableService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        DashboardPipelineDiagnostics.event(id, null, "FACTORY_CREATED", "adapter=${intent.data}")
        return Factory(this, id) { (applicationContext as HaWidgetApplication).container.dashboards.get(id) }
    }

    internal class Factory(private val context: Context, private val id: Int,
        private val load: () -> DashboardState?) : RemoteViewsFactory {
        @Volatile private var rows: List<DashboardStableRows.Row> = emptyList()
        @Volatile private var diagnosticRevision: Long? = null
        override fun onCreate() = Unit
        override fun onDestroy() { rows = emptyList() }
        override fun onDataSetChanged() {
            rows = load()?.let {
                diagnosticRevision = it.stateRevision
                DashboardPipelineDiagnostics.config("FACTORY_CONFIG", it.config)
                DashboardStableRows(context, id).rows(it)
            }.orEmpty()
            DashboardPipelineDiagnostics.event(id, diagnosticRevision, "FACTORY_DATA_CHANGED", "dataset=${rows.size}")
            rows.forEachIndexed { position, row ->
                DashboardPipelineDiagnostics.event(id, diagnosticRevision, "FACTORY_ROW",
                    "position=$position key=${row.key} stableId=${DashboardStatePolicy.stableCollectionId(row.key)}")
            }
        }
        override fun getCount() = rows.size.also {
            DashboardPipelineDiagnostics.event(id, diagnosticRevision, "FACTORY_GET_COUNT", "count=$it")
        }
        override fun getViewAt(position: Int) = rows.getOrNull(position)?.views.also {
            DashboardPipelineDiagnostics.event(id, diagnosticRevision, "FACTORY_GET_VIEW_AT", "position=$position exists=${it != null}")
        }
        override fun getItemId(position: Int) = rows.getOrNull(position)?.key?.let(DashboardStatePolicy::stableCollectionId) ?: 0L
        override fun hasStableIds() = true
        override fun getViewTypeCount() = 3
        override fun getLoadingView(): RemoteViews? = null
    }
}
