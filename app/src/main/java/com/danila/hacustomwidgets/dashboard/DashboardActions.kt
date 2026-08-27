package com.danila.hacustomwidgets.dashboard

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.data.model.HaEntity
import kotlinx.coroutines.delay

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
            val entityIds = container.dashboards.entityIds(appWidgetId)
            if (entityIds.isEmpty() || container.dashboards.requiresCatalogRefresh(appWidgetId)) {
                runCatching { container.client.getCatalog(connection) }
                    .onSuccess { container.dashboards.updateFromCatalog(appWidgetId, it) }
                    .onFailure { container.dashboards.saveError(appWidgetId, it.message ?: "Ошибка сети") }
            } else {
                runCatching { container.client.getEntities(connection, entityIds) }
                    .onSuccess { container.dashboards.updateEntityStates(appWidgetId, it) }
                    .onFailure { container.dashboards.saveError(appWidgetId, it.message ?: "Ошибка сети") }
            }
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
        val entityId = parameters[DashboardEntityKey] ?: return
        val domain = parameters[DashboardDomainKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        if (!container.dashboards.tryBeginAction(appWidgetId, entityId)) return
        DashboardWidget().update(context, glanceId)
        try {
            val connection = container.connectionStore.load() ?: error("Подключение не настроено")
            val current = container.dashboards.get(appWidgetId)
                ?.cards
                ?.asSequence()
                ?.flatMap { it.controls.asSequence() }
                ?.firstOrNull { it.entityId == entityId }
                ?.state
            val service = serviceFor(domain, current)
            container.client.callService(connection, domain, service, entityId)
            val confirmed = awaitConfirmedState(
                load = { container.client.getEntity(connection, entityId) },
                expected = expectedState(domain, service),
            )
            container.dashboards.updateEntityStates(appWidgetId, listOf(confirmed))
        } catch (error: Throwable) {
            container.dashboards.saveError(appWidgetId, error.message ?: "Команда не выполнена")
        } finally {
            container.dashboards.finishAction(appWidgetId, entityId)
            DashboardWidget().update(context, glanceId)
        }
    }

    private suspend fun awaitConfirmedState(
        load: suspend () -> HaEntity,
        expected: String?,
    ): HaEntity {
        var latest: HaEntity? = null
        val attempts = if (expected == null) 1 else 8
        for (attempt in 0 until attempts) {
            delay(if (attempt == 0) 350L else 450L)
            latest = load()
            if (expected == null || latest.state == expected) break
        }
        return checkNotNull(latest)
    }

    private fun serviceFor(domain: String, state: String?): String = when (domain) {
        "light", "switch", "input_boolean" -> if (state == "on") "turn_off" else "turn_on"
        "button" -> "press"
        "script", "scene" -> "turn_on"
        "timer" -> if (state == "active") "pause" else "start"
        else -> error("Управление $domain пока не поддерживается")
    }

    private fun expectedState(domain: String, service: String): String? = when {
        domain in setOf("light", "switch", "input_boolean") && service == "turn_on" -> "on"
        domain in setOf("light", "switch", "input_boolean") && service == "turn_off" -> "off"
        domain == "timer" && service == "start" -> "active"
        domain == "timer" && service == "pause" -> "paused"
        else -> null
    }
}
