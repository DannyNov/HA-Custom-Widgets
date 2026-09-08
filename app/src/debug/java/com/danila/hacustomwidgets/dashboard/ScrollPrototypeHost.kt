package com.danila.hacustomwidgets.dashboard

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.RemoteViewsService
import java.util.concurrent.LinkedBlockingQueue

/** Debug-only fixture. No HA connection, secret, or network action is used. */
object ScrollPrototypeData {
    @Volatile var revision = 0
    val clicks = LinkedBlockingQueue<Intent>()
    fun state(id: Int): DashboardState {
        val cards = (0 until 30).map { index ->
            val domain = listOf("switch", "light", "automation", "script", "scene")[index % 5]
            val control = DashboardControl("$domain.fixture$index", "$domain $index", domain,
                if (revision % 2 == 0) "off" else "on")
            DashboardCard("fixture$index", "Fixture $index", null, null, DeviceCategory.OTHER,
                if (index % 3 == 0) listOf(DashboardMetric("sensor.temp$index", "Temperature", "23 °C", "23", "sensor", "temperature"),
                    DashboardMetric("sensor.battery$index", "Battery", "middle", "middle", "sensor", "battery")) else emptyList(),
                if (index % 7 == 0) (0..6).map { control.copy(entityId = "$domain.fixture${index}_$it") } else listOf(control),
                autoOffTimer = if (domain == "switch") AutoOffTimerConfig(true, "timer.fixture$index", selectedDurationIndex = revision % 4) else null)
        }
        return DashboardState(DashboardConfig(id, emptyList(), emptyMap(), cards.map { it.key },
            emptyMap(), emptyMap(), true, true), emptyList(), cards, emptyList(), MAIN_TAB_ID,
            emptySet(), emptySet(), emptyMap(), revision.toLong(), false, 0, null)
    }
}

class ScrollPrototypeService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        DashboardStableService.Factory(this, 42) { ScrollPrototypeData.state(42) }
}

class ScrollPrototypeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { ScrollPrototypeData.clicks.offer(intent) }
}

class ScrollPrototypeProvider : AppWidgetProvider()

class ScrollPrototypeHost : Activity() {
    lateinit var host: AppWidgetHost
    lateinit var content: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = FrameLayout(this)
        setContentView(content)
        host = AppWidgetHost(this, 605)
        host.startListening()
    }
    override fun onDestroy() {
        host.stopListening()
        super.onDestroy()
    }
}
