package com.danila.hacustomwidgets.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.defaultWeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
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
    val dayBackground = Color(0xFFF7F9FC)
    val nightBackground = Color(0xFF17212B)
    val dayPrimary = Color(0xFF102A43)
    val nightPrimary = Color(0xFFF5F7FA)
    val daySecondary = Color(0xFF52606D)
    val nightSecondary = Color(0xFFBCCCDC)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(dayBackground, nightBackground))
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
                style = TextStyle(color = ColorProvider(dayPrimary, nightPrimary), fontSize = 16.sp),
            )
            return@Column
        }

        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    config.title,
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(daySecondary, nightSecondary), fontSize = 13.sp),
                )
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    config.state,
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(dayPrimary, nightPrimary),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(GlanceModifier.width(12.dp))
            Text(
                "↻",
                style = TextStyle(color = ColorProvider(Color(0xFF008ACB), Color(0xFF47C9FF)), fontSize = 24.sp),
            )
        }
        Spacer(GlanceModifier.height(5.dp))
        val footer = config.error?.let { "Ошибка обновления" }
            ?: config.lastUpdatedMillis.takeIf { it > 0 }?.let {
                "Обновлено ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}"
            }.orEmpty()
        Text(
            footer,
            maxLines = 1,
            style = TextStyle(
                color = if (config.error == null) ColorProvider(daySecondary, nightSecondary)
                else ColorProvider(Color(0xFFBA1A1A), Color(0xFFFFB4AB)),
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
