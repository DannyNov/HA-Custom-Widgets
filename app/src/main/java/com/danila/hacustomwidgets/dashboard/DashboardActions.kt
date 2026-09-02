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
val DashboardTimerEntityKey = ActionParameters.Key<String>("dashboard_timer_entity")

class DashboardRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[DashboardWidgetIdKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        Log.d(TAG, "refresh callback received widgetId=$appWidgetId")
        container.dashboards.markRefreshInProgress(appWidgetId, true)
        DashboardRefreshWorker.enqueue(context, appWidgetId, DashboardStateSource.MANUAL_REFRESH)
        container.dashboardEvents.wakeAsync("MANUAL_REFRESH", reconcileIfStale = false)
    }
}

class DashboardNavigateAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[DashboardWidgetIdKey] ?: return
        val tab = parameters[DashboardTabKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        val started = System.currentTimeMillis()
        container.dashboards.setSelectedTab(appWidgetId, tab)
        Log.d(TAG, "navigation callback finished widgetId=$appWidgetId durationMs=${System.currentTimeMillis() - started}")
        container.dashboardEvents.wakeAsync("NAVIGATION")
    }
}

class DashboardToggleSectionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[DashboardWidgetIdKey] ?: return
        val section = parameters[DashboardSectionKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        container.dashboards.toggleSection(appWidgetId, section)
        container.dashboardEvents.wakeAsync("SECTION")
    }
}

class DashboardControlAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[DashboardWidgetIdKey] ?: return
        val entityId = parameters[DashboardEntityKey] ?: return
        val domain = parameters[DashboardDomainKey] ?: return
        val deviceKey = parameters[DashboardDeviceKey]
        val container = (context.applicationContext as HaWidgetApplication).container
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
        DashboardActionWorker.enqueue(context, appWidgetId, entityId, operation.operationId, deviceKey)
        container.dashboardEvents.wakeAsync("CONTROL")
    }
}

class DashboardTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[DashboardWidgetIdKey] ?: return
        val deviceKey = parameters[DashboardDeviceKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        val selection = container.dashboards.selectNextTimerDuration(appWidgetId, deviceKey) ?: return
        val (card, preset) = selection
        val primary = card.controls.firstOrNull { AutoOffTimerPolicy.eligible(it.domain) } ?: return
        val timerId = card.autoOffTimer?.timerEntityId ?: return
        DashboardTimerActionWorker.enqueue(
            context, appWidgetId, primary.entityId, primary.domain, primary.state in setOf("on", "active"),
            timerId, preset.minutes,
        )
        container.dashboardEvents.wakeAsync("TIMER_CONTROL")
    }
}

private const val TAG = "HAWidgetDashboardAction"
