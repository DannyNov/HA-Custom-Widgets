package com.danila.hacustomwidgets.dashboard

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.R

internal object StableCollectionPolicy {
    fun use(api: Int) = api in 26..30
    fun identity(widgetId: Int) = "hacw://dashboard/$widgetId/collection/static-v2"
}

internal object DashboardStableCollection {
    private val retryHandler by lazy { Handler(Looper.getMainLooper()) }
    private val retryByWidget = mutableMapOf<Int, Runnable>()

    @Synchronized
    internal fun notifyDataChanged(manager: AppWidgetManager, id: Int) {
        manager.notifyAppWidgetViewDataChanged(id, R.id.legacy_list)
        if (Build.VERSION.SDK_INT !in 26..27) return
        retryByWidget.remove(id)?.let(retryHandler::removeCallbacks)
        // Oreo can refresh the factory snapshot but leave visible collection children stale
        // during a burst. A single coalesced data-only retry does not rebind the adapter.
        val retry = Runnable {
            manager.notifyAppWidgetViewDataChanged(id, R.id.legacy_list)
            synchronized(this) { retryByWidget.remove(id) }
        }
        retryByWidget[id] = retry
        retryHandler.postDelayed(retry, 5_000)
    }

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
        val views = buildViews(context, id, state, initial)
        if (initial) {
            manager.updateAppWidget(id, views)
            prefs.edit().putBoolean("bound:$id", true).apply()
        } else manager.partiallyUpdateAppWidget(id, views)
        notifyDataChanged(manager, id)
    }

    @Synchronized
    fun forget(context: Context, id: Int) {
        retryByWidget.remove(id)?.let(retryHandler::removeCallbacks)
        context.getSharedPreferences("dashboard_static_hosts_v2", Context.MODE_PRIVATE).edit().remove("bound:$id").apply()
    }
}

class DashboardStableService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        return Factory(this, id) { (applicationContext as HaWidgetApplication).container.dashboards.get(id) }
    }

    internal class Factory(private val context: Context, private val id: Int,
        private val load: () -> DashboardState?) : RemoteViewsFactory {
        @Volatile private var rows: List<DashboardStableRows.Row> = emptyList()
        override fun onCreate() = Unit
        override fun onDestroy() { rows = emptyList() }
        override fun onDataSetChanged() { rows = load()?.let { DashboardStableRows(context, id).rows(it) }.orEmpty() }
        override fun getCount() = rows.size
        override fun getViewAt(position: Int) = rows.getOrNull(position)?.views
        override fun getItemId(position: Int) = rows.getOrNull(position)?.key?.let(DashboardStatePolicy::stableCollectionId) ?: 0L
        override fun hasStableIds() = true
        override fun getViewTypeCount() = 3
        override fun getLoadingView(): RemoteViews? = null
    }
}
