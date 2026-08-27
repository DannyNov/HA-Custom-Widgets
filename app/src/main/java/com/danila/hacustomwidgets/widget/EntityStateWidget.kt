package com.danila.hacustomwidgets.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.danila.hacustomwidgets.R
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.data.WidgetConfig
import java.text.DateFormat
import java.util.Date

class EntityStateWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val repository = (context.applicationContext as HaWidgetApplication).container.widgets
        val config = repository.get(appWidgetId)
        provideContent { WidgetContent(config, appWidgetId) }
    }
}

@Composable
private fun WidgetContent(config: WidgetConfig?, appWidgetId: Int) {
    val background = ColorProvider(R.color.widget_background)
    val primary = ColorProvider(R.color.widget_primary)
    val secondary = ColorProvider(R.color.widget_secondary)
    val accent = ColorProvider(R.color.widget_accent)
    val error = ColorProvider(R.color.widget_error)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(background)
            .cornerRadius(20.dp)
            .padding(16.dp)
            .clickable(
                actionRunCallback<RefreshEntityAction>(
                    actionParametersOf(WidgetIdKey to appWidgetId),
                ),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (config == null) {
            Text(
                "Настройте виджет",
                style = TextStyle(color = primary, fontSize = 16.sp),
            )
            return@Column
        }

        Text(
            config.title,
            maxLines = 1,
            style = TextStyle(color = secondary, fontSize = 13.sp),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            config.state,
            maxLines = 1,
            style = TextStyle(color = primary, fontSize = 24.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(5.dp))
        val footer = config.error?.let { "Ошибка обновления" }
            ?: config.lastUpdatedMillis.takeIf { it > 0 }?.let {
                "Обновлено ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}"
            }.orEmpty()
        Text(
            "↻  $footer",
            maxLines = 1,
            style = TextStyle(
                color = if (config.error == null) accent else error,
                fontSize = 11.sp,
            ),
        )
    }
}

val WidgetIdKey = ActionParameters.Key<Int>("app_widget_id")

class EntityStateWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EntityStateWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val repository = (context.applicationContext as HaWidgetApplication).container.widgets
        appWidgetIds.forEach(repository::delete)
        super.onDeleted(context, appWidgetIds)
    }
}
