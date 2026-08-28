package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager

suspend fun updateDashboardWidget(context: Context, appWidgetId: Int, reason: String) {
    val started = System.currentTimeMillis()
    Log.d(TAG, "widget update requested widgetId=$appWidgetId reason=$reason ts=$started")
    runCatching {
        val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
        DashboardWidget().update(context, glanceId)
    }.onFailure {
        Log.w(TAG, "widget update failed widgetId=$appWidgetId reason=$reason", it)
    }
    Log.d(TAG, "widget update finished widgetId=$appWidgetId reason=$reason durationMs=${System.currentTimeMillis() - started}")
}

private const val TAG = "HAWidgetDashboardUpdate"

