package com.danila.hacustomwidgets.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.R
import com.danila.hacustomwidgets.data.WidgetConfig
import com.danila.hacustomwidgets.data.WidgetMetric
import java.text.DateFormat
import java.util.Date

class EntityStateWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val repository = (context.applicationContext as HaWidgetApplication).container.widgets
        val config = repository.get(appWidgetId)
        provideContent {
            GlanceTheme { WidgetContent(config, appWidgetId, LocalSize.current) }
        }
    }
}

@Composable
private fun WidgetContent(config: WidgetConfig?, appWidgetId: Int, size: DpSize) {
    val background = ColorProvider(R.color.widget_background)
    val primary = ColorProvider(R.color.widget_primary)
    val secondary = ColorProvider(R.color.widget_secondary)
    val accent = ColorProvider(R.color.widget_accent)
    val error = ColorProvider(R.color.widget_error)
    val tile = ColorProvider(R.color.widget_tile)
    val width = size.width.value.toInt().coerceAtLeast(56)
    val height = size.height.value.toInt().coerceAtLeast(50)
    val itemCount = config?.metrics?.size ?: 1
    val spec = widgetLayoutSpec(width, height, itemCount)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(background)
            .cornerRadius(if (width < 100 || height < 70) 14.dp else 20.dp)
            .padding(spec.paddingDp.dp)
            .clickable(
                actionRunCallback<RefreshEntityAction>(
                    actionParametersOf(WidgetIdKey to appWidgetId),
                ),
            ),
    ) {
        if (config == null) {
            Text("Настройте виджет", style = TextStyle(color = primary, fontSize = 15.sp))
            return@Column
        }

        val visible = config.metrics.take(spec.visibleItems)
        val hiddenCount = (config.metrics.size - visible.size).coerceAtLeast(0)
        if (spec.showTitle) {
            Text(
                if (hiddenCount > 0) "${config.title}  +$hiddenCount" else config.title,
                maxLines = 1,
                style = TextStyle(color = secondary, fontSize = if (width >= 180) 13.sp else 11.sp),
            )
            Spacer(GlanceModifier.height(spec.gapDp.dp))
        }

        visible.chunked(spec.columns).forEachIndexed { rowIndex, rowMetrics ->
            MetricRow(
                metrics = rowMetrics,
                widthDp = width,
                spec = spec,
                primary = primary,
                secondary = secondary,
                tile = tile,
            )
            if (rowIndex < visible.chunked(spec.columns).lastIndex) {
                Spacer(GlanceModifier.height(spec.gapDp.dp))
            }
        }

        if (spec.showFooter && (config.showLastUpdated || config.error != null)) {
            Spacer(GlanceModifier.height(spec.gapDp.dp))
            val footer = config.error?.let { "↻ Ошибка обновления" }
                ?: config.lastUpdatedMillis.takeIf { it > 0 }?.let {
                    "↻ ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}"
                }.orEmpty()
            Text(
                footer,
                maxLines = 1,
                style = TextStyle(
                    color = if (config.error == null) accent else error,
                    fontSize = 10.sp,
                ),
            )
        }
    }
}

@Composable
private fun MetricRow(
    metrics: List<WidgetMetric>,
    widthDp: Int,
    spec: WidgetLayoutSpec,
    primary: ColorProvider,
    secondary: ColorProvider,
    tile: ColorProvider,
) {
    val availableWidth = widthDp - spec.paddingDp * 2 - spec.gapDp * (spec.columns - 1)
    val tileWidth = (availableWidth / spec.columns).coerceAtLeast(40)
    Row(verticalAlignment = Alignment.CenterVertically) {
        metrics.forEachIndexed { index, metric ->
            Column(
                modifier = GlanceModifier
                    .width(tileWidth.dp)
                    .background(tile)
                    .cornerRadius(12.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    metric.state,
                    maxLines = 1,
                    style = TextStyle(
                        color = primary,
                        fontSize = spec.valueTextSp.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                if (spec.showLabels) {
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        metric.label,
                        maxLines = 1,
                        style = TextStyle(color = secondary, fontSize = 10.sp),
                    )
                }
            }
            if (index < metrics.lastIndex) Spacer(GlanceModifier.width(spec.gapDp.dp))
        }
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
