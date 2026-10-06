package com.danila.hacustomwidgets.dashboard
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import java.text.DateFormat
import java.util.Date

// Frozen header extracted from v0.7.0 (58a39ede).
@Composable
internal fun LegacyV070Header(
    context: Context,
    appWidgetId: Int,
    state: DashboardState?,
    widthDp: Int,
    primary: ColorProvider,
    accent: ColorProvider,
) {
    val settingsIntent = Intent(context, DashboardWidgetConfigActivity::class.java)
        .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (widthDp >= 260) "HA Dashboard" else "HA",
            modifier = GlanceModifier.defaultWeight(),
            maxLines = 1,
            style = TextStyle(color = primary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
        if (state != null && state.config.showLastUpdated && widthDp >= 240) {
            Text(
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.lastUpdatedMillis)),
                modifier = GlanceModifier.padding(end = 2.dp),
                maxLines = 1,
                style = TextStyle(color = primary, fontSize = 9.sp),
            )
        }
        Text(
            if (state?.error == null) "↻" else "⚠",
            modifier = GlanceModifier.padding(horizontal = 8.dp, vertical = 4.dp).clickable(
                actionRunCallback<DashboardRefreshAction>(
                    actionParametersOf(DashboardWidgetIdKey to appWidgetId),
                ),
            ),
            style = TextStyle(color = accent, fontSize = 18.sp),
        )
        Text(
            "⚙",
            modifier = GlanceModifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                .clickable(actionStartActivity(settingsIntent)),
            style = TextStyle(color = accent, fontSize = 18.sp),
        )
    }
}
