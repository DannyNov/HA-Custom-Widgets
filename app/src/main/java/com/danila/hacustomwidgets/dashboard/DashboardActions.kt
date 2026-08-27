package com.danila.hacustomwidgets.dashboard

import android.content.Context
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
        val connection = container.connectionStore.load()
        if (connection == null) {
            container.dashboards.saveError(appWidgetId, "Подключение не настроено")
        } else {
            runCatching { container.client.getCatalog(connection) }
                .onSuccess { container.dashboards.updateFromCatalog(appWidgetId, it) }
                .onFailure { container.dashboards.saveError(appWidgetId, it.message ?: "Ошибка сети") }
        }
        DashboardWidget().update(context, glanceId)
    }
}

class DashboardNavigateAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[DashboardWidgetIdKey] ?: return
        val tab = parameters[DashboardTabKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        container.dashboards.setSelectedTab(appWidgetId, tab)
        DashboardWidget().update(context, glanceId)
    }
}

class DashboardToggleSectionAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[DashboardWidgetIdKey] ?: return
        val section = parameters[DashboardSectionKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        container.dashboards.toggleSection(appWidgetId, section)
        DashboardWidget().update(context, glanceId)
    }
}

class DashboardControlAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[DashboardWidgetIdKey] ?: return
        val deviceKey = parameters[DashboardDeviceKey] ?: return
        val entityId = parameters[DashboardEntityKey] ?: return
        val domain = parameters[DashboardDomainKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        if (!container.dashboards.tryBeginAction(appWidgetId, deviceKey)) return
        DashboardWidget().update(context, glanceId)
        try {
            val connection = container.connectionStore.load() ?: error("Подключение не настроено")
            val state = container.dashboards.get(appWidgetId)
            val current = state?.cards?.firstOrNull { it.key == deviceKey }?.controlState
            val service = serviceFor(domain, current)
            container.client.callService(connection, domain, service, entityId)
            val confirmed = container.client.getEntity(connection, entityId)
            container.dashboards.updateEntityStates(appWidgetId, listOf(confirmed))
        } catch (error: Throwable) {
            container.dashboards.saveError(appWidgetId, error.message ?: "Команда не выполнена")
        } finally {
            container.dashboards.finishAction(appWidgetId, deviceKey)
            DashboardWidget().update(context, glanceId)
        }
    }

    private fun serviceFor(domain: String, state: String?): String = when (domain) {
        "light", "switch", "input_boolean" -> "toggle"
        "button" -> "press"
        "script", "scene" -> "turn_on"
        "timer" -> if (state == "active") "pause" else "start"
        else -> error("Управление $domain пока не поддерживается")
    }
}
