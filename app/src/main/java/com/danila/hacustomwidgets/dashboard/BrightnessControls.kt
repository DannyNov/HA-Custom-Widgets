package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import androidx.glance.semantics.semantics
import androidx.glance.semantics.contentDescription
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.R
import com.danila.hacustomwidgets.tr

val BrightnessStepKey = ActionParameters.Key<Int>("brightness_step")

class BrightnessStepAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entity = parameters[DashboardEntityKey] ?: return
        val step = parameters[BrightnessStepKey] ?: return
        val container = (context.applicationContext as HaWidgetApplication).container
        container.brightness.submit(entity, step = step)
        container.dashboardEvents.wakeAsync("BRIGHTNESS", reconcileIfStale = false)
    }
}

object BrightnessLayoutPolicy {
    // 20 outer + 24 card padding + 80 name + 144 dimmer + 48 power.
    fun percentWidth(fontScale: Float): Float = 48f * fontScale.coerceIn(1f, 1.5f)
    fun showSteps(widthDp: Int, fontScale: Float): Boolean = widthDp >= 268 + percentWidth(fontScale) && fontScale <= 1.3f
}

@Composable
fun BrightnessControls(context: Context, control: DashboardControl, widgetId: Int, widthDp: Int) {
    if (!control.brightnessCapable) return
    val available = control.state in setOf("on", "off")
    val enabled = available && control.brightnessPercent != null
    val color = ColorProvider(if (enabled && control.state == "on") R.color.widget_light_on else R.color.widget_secondary)
    val fontScale = context.resources.configuration.fontScale
    val steps = BrightnessLayoutPolicy.showSteps(widthDp, fontScale)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (steps) BrightnessStep(control, -1, enabled, color)
        val intent = Intent(context, BrightnessActivity::class.java)
            .putExtra("brightness_entity", control.entityId).putExtra("brightness_widget", widgetId)
        Box(
            modifier = GlanceModifier.width(BrightnessLayoutPolicy.percentWidth(fontScale).dp).height(48.dp).semantics {
                contentDescription = tr("Set brightness", "Настроить яркость") + ": " + control.label + " " + (control.brightnessPercent?.let { "$it%" } ?: "—%")
            }.let {
                if (available) it.clickable(actionStartActivity(intent)) else it
            }, contentAlignment = Alignment.Center,
        ) {
            Text(control.brightnessPercent?.let { "$it%" } ?: "—%", modifier = GlanceModifier.fillMaxWidth(), maxLines = 1,
                style = TextStyle(color = color, fontSize = (12f * fontScale.coerceAtMost(2f) / fontScale).sp, textAlign = TextAlign.Center))
        }
        if (steps) BrightnessStep(control, 1, enabled, color)
    }
}

@Composable
private fun BrightnessStep(control: DashboardControl, step: Int, enabled: Boolean, color: ColorProvider) {
    Box(modifier = GlanceModifier.width(48.dp).height(48.dp).semantics {
        contentDescription = if (step > 0) tr("Increase brightness by 5 percent", "Увеличить яркость на 5 процентов")
            else tr("Decrease brightness by 5 percent", "Уменьшить яркость на 5 процентов")
    }.let {
        if (enabled) it.clickable(actionRunCallback<BrightnessStepAction>(actionParametersOf(
            DashboardEntityKey to control.entityId, BrightnessStepKey to step,
        ))) else it
    }, contentAlignment = Alignment.Center) {
        Text(if (step > 0) "+" else "−", style = TextStyle(color = color, fontSize = 18.sp))
    }
}
