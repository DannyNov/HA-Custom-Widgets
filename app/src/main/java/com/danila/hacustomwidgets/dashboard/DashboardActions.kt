package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.danila.hacustomwidgets.HaWidgetApplication

val DashboardWidgetIdKey = ActionParameters.Key<Int>("dashboard_widget_id")
val DashboardTabKey = ActionParameters.Key<String>("dashboard_tab")
val DashboardSectionKey = ActionParameters.Key<String>("dashboard_section")
val DashboardDeviceKey = ActionParameters.Key<String>("dashboard_device")
val DashboardEntityKey = ActionParameters.Key<String>("dashboard_entity")
val DashboardDomainKey = ActionParameters.Key<String>("dashboard_domain")

class DashboardRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[DashboardWidgetIdKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        container.dashboardEvents.ensureStarted("MANUAL_REFRESH", reconcileIfStale = false)
        Log.d(TAG, "refresh callback received widgetId=$appWidgetId")
        container.dashboards.markRefreshInProgress(appWidgetId, true)
        DashboardRefreshWorker.enqueue(context, appWidgetId, DashboardStateSource.MANUAL_REFRESH)
    }
}

class DashboardNavigateAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[DashboardWidgetIdKey] ?: return
        val tab = parameters[DashboardTabKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        container.dashboardEvents.ensureStarted("NAVIGATION")
        val started = System.currentTimeMillis()
        container.dashboards.setSelectedTab(appWidgetId, tab)
        Log.d(TAG, "navigation callback finished widgetId=$appWidgetId durationMs=${System.currentTimeMillis() - started}")
    }
}

class DashboardToggleSectionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[DashboardWidgetIdKey] ?: return
        val section = parameters[DashboardSectionKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        container.dashboardEvents.ensureStarted("SECTION")
        container.dashboards.toggleSection(appWidgetId, section)
    }
}

class DashboardControlAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[DashboardWidgetIdKey] ?: return
        val entityId = parameters[DashboardEntityKey] ?: return
        val domain = parameters[DashboardDomainKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        container.dashboardEvents.ensureStarted("CONTROL")
        if (container.connectionStore.load() == null) {
            container.dashboards.saveError(appWidgetId, "Подключение не настроено")
            return
        }
        val operation = runCatching {
            container.dashboards.beginOperation(appWidgetId, entityId, domain)
        }.getOrElse {
            container.dashboards.saveError(appWidgetId, it.message ?: "Действие недоступно")
            return
        } ?: return
        Log.d(
            TAG,
            "CONTROL_TAP processStartId=${DashboardDiagnostics.processStartId} operationId=${operation.operationId} " +
                "appWidgetId=$appWidgetId entityId=$entityId source=ACTION desiredState=${operation.desiredState}",
        )
        DashboardActionWorker.enqueue(context, appWidgetId, entityId, operation.operationId)
    }
}

private const val TAG = "HAWidgetDashboardAction"
