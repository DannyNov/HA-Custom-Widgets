package com.danila.hacustomwidgets.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.danila.hacustomwidgets.HaWidgetApplication

class RefreshEntityAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[WidgetIdKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        val config = container.widgets.get(appWidgetId) ?: return
        val connection = container.connectionStore.load()
        if (connection == null) {
            container.widgets.saveError(appWidgetId, "Подключение не настроено")
        } else {
            runCatching { container.client.getEntity(connection, config.entityId) }
                .onSuccess { container.widgets.save(appWidgetId, it) }
                .onFailure { container.widgets.saveError(appWidgetId, it.message ?: "Ошибка сети") }
        }
        EntityStateWidget().updateAll(context)
    }
}
