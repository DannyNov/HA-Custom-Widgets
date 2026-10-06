package com.danila.hacustomwidgets.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.ColorFilter
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.danila.hacustomwidgets.R
import com.danila.hacustomwidgets.tr

@Composable
internal fun MaintenanceContent(snapshot: MaintenanceSnapshot, primary: ColorProvider, secondary: ColorProvider, modifier: GlanceModifier) {
    LazyColumn(modifier) {
        if (!snapshot.catalogLoaded) item(itemId = DashboardStatePolicy.stableCollectionId("maintenance:catalog")) { Text(tr("Refresh to load Maintenance", "Обновите для загрузки обслуживания"), style = TextStyle(color = secondary, fontSize = 12.sp)) }
        if (!snapshot.repairsLoaded || snapshot.repairsError) item(itemId = DashboardStatePolicy.stableCollectionId("maintenance:repairs-status")) {
            Text(if (snapshot.repairsError) tr("Repairs unavailable · refresh to retry", "Repairs недоступны · обновите для повтора")
                else tr("Repairs have not loaded yet", "Repairs ещё не загружены"), style = TextStyle(color = secondary, fontSize = 12.sp))
        }
        item(itemId = DashboardStatePolicy.stableCollectionId("maintenance:attention-title")) { Text(tr("⚠ Requires attention", "⚠ Требуют внимания"), style = TextStyle(color = primary, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.padding(vertical = 6.dp)) }
        if (!snapshot.attention) item(itemId = DashboardStatePolicy.stableCollectionId("maintenance:no-attention")) { Text(if (snapshot.catalogLoaded && snapshot.repairsLoaded && !snapshot.repairsError)
            tr("Nothing requires attention", "Ничего не требует внимания") else tr("No known items require attention", "Среди известных данных нет тревог"), style = TextStyle(color = secondary, fontSize = 12.sp)) }
        snapshot.activeRepairs.forEach { issue -> item(itemId = DashboardStatePolicy.stableCollectionId("repair:${issue.domain}:${issue.issueId}")) {
            MaintenanceRow(issue.title, issue.detail, primary, secondary)
        } }
        snapshot.availableUpdates.forEach { entity -> item(itemId = DashboardStatePolicy.stableCollectionId("update:${entity.entityId}")) {
            MaintenanceRow(entity.friendlyName, tr("Update available · review in Home Assistant", "Доступно обновление · проверьте в Home Assistant"), primary, secondary)
        } }
        snapshot.attentionBatteries.forEach { battery -> item(itemId = DashboardStatePolicy.stableCollectionId("battery:${battery.entity.entityId}")) { MaintenanceBatteryRow(battery, primary, secondary) } }
        item(itemId = DashboardStatePolicy.stableCollectionId("maintenance:batteries-title")) { Text(tr("Batteries", "Батареи"), style = TextStyle(color = primary, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.padding(vertical = 6.dp)) }
        if (snapshot.otherBatteries.isEmpty()) item(itemId = DashboardStatePolicy.stableCollectionId("maintenance:no-batteries")) { Text(tr("No other batteries", "Других батарей нет"), style = TextStyle(color = secondary, fontSize = 12.sp)) }
        snapshot.otherBatteries.groupBy { it.area }.forEach { (area, batteries) ->
            item(itemId = DashboardStatePolicy.stableCollectionId("maintenance:area:$area")) { Text(area ?: tr("Unassigned", "Без помещения"), style = TextStyle(color = secondary, fontSize = 11.sp), modifier = GlanceModifier.padding(vertical = 4.dp)) }
            batteries.forEach { battery -> item(itemId = DashboardStatePolicy.stableCollectionId("battery:${battery.entity.entityId}")) { MaintenanceBatteryRow(battery, primary, secondary) } }
        }
    }
}

@Composable
private fun MaintenanceRow(title: String, detail: String, primary: ColorProvider, secondary: ColorProvider) {
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 5.dp).background(ColorProvider(R.color.widget_tile)).cornerRadius(12.dp).padding(8.dp)) {
        Text(title, style = TextStyle(color = primary, fontSize = 13.sp))
        Text(detail, style = TextStyle(color = secondary, fontSize = 11.sp))
    }
}

@Composable
internal fun MaintenanceBatteryRow(battery: MaintenanceBattery, primary: ColorProvider, secondary: ColorProvider) {
    val metric = battery.metric
    val color = when (batteryHealth(metric)) {
        BatteryHealth.NORMAL -> ColorProvider(R.color.widget_switch_on)
        BatteryHealth.LOW -> ColorProvider(R.color.widget_warning)
        BatteryHealth.CRITICAL -> ColorProvider(R.color.widget_problem)
        else -> secondary
    }
    Column(GlanceModifier.fillMaxWidth().padding(bottom = 5.dp).background(ColorProvider(R.color.widget_tile)).cornerRadius(12.dp).padding(8.dp)) {
        Text(battery.title, style = TextStyle(color = primary, fontSize = 13.sp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(batteryIconResource(metric)), tr("Battery", "Батарея"), modifier = GlanceModifier.width(16.dp).height(16.dp), colorFilter = ColorFilter.tint(color))
            Text(" ${metric.state}", style = TextStyle(color = color, fontSize = 12.sp))
        }
    }
}
