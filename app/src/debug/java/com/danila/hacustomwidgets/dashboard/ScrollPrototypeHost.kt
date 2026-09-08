package com.danila.hacustomwidgets.dashboard

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.RemoteViewsService
import com.danila.hacustomwidgets.R
import java.util.concurrent.LinkedBlockingQueue

/** Debug-only fixture. No HA connection, secret, or network action is used. */
object ScrollPrototypeData {
    @Volatile var revision = 0
    @Volatile var factoryRows = -1
    @Volatile var factoryRevision = -1L
    @Volatile var lifecycleUpdates = 0
    @Volatile var initialPublications = 0
    val clicks = LinkedBlockingQueue<Intent>()
    fun state(id: Int): DashboardState {
        val cards = (0 until 30).map { index ->
            val domain = listOf("switch", "light", "automation", "script", "scene")[index % 5]
            val control = DashboardControl("$domain.fixture$index", "$domain $index", domain,
                if (revision % 2 == 0) "off" else "on")
            DashboardCard(if (domain in setOf("automation", "script", "scene")) "scenario:fixture$index" else "fixture$index",
                "Fixture ${index.toString().padStart(2, '0')} · r$revision", null, null, DeviceCategory.OTHER,
                if (index % 3 == 0) listOf(DashboardMetric("sensor.temp$index", "Temperature", "23 °C", "23", "sensor", "temperature"),
                    DashboardMetric("sensor.battery$index", "Battery", listOf("high", "middle", "low", "unknown")[revision % 4],
                        listOf("high", "middle", "low", "unknown")[revision % 4], "sensor", "battery")) else emptyList(),
                if (index % 7 == 0) (0..6).map { control.copy(entityId = "$domain.fixture${index}_$it") } else listOf(control),
                autoOffTimer = if (domain == "switch") AutoOffTimerConfig(true, "timer.fixture$index", selectedDurationIndex = revision % 4) else null,
                timerState = if (domain == "switch") DashboardMetric("timer.fixture$index", "Timer",
                    listOf("active", "paused", "idle")[revision % 3], listOf("active", "paused", "idle")[revision % 3],
                    "timer", null, "02:00:00", "01:55:00", java.time.Instant.now().plusSeconds(6900).toString()) else null,
                scenarioRunnable = domain in setOf("automation", "script", "scene"))
        }.map { card -> card.copy(metrics = card.metrics + card.controls.map { control ->
            DashboardMetric(control.entityId, control.label, control.state, control.state, control.domain, null)
        }) }
        return DashboardState(DashboardConfig(id, emptyList(), emptyMap(), cards.map { it.key },
            emptyMap(), emptyMap(), true, true), emptyList(), cards, emptyList(), MAIN_TAB_ID,
            emptySet(), emptySet(), emptyMap(), revision.toLong(), false, 0, null)
    }
}

class ScrollPrototypeService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        DashboardStableService.Factory(this, 42) { ScrollPrototypeData.state(42).also {
            ScrollPrototypeData.factoryRows = DashboardStableRows(this, 42).rows(it).size
            ScrollPrototypeData.factoryRevision = it.stateRevision
        } }
}

class ScrollPrototypeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { ScrollPrototypeData.clicks.offer(intent) }
}

class ScrollPrototypeProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Record the framework lifecycle only. The test publishes after this callback and
        // the host's initial layout work have both drained.
        ScrollPrototypeData.lifecycleUpdates++
    }

    companion object {
        @Synchronized
        fun publishInitial(context: Context, manager: AppWidgetManager, widgetId: Int) {
            check(ScrollPrototypeData.initialPublications == 0)
            val views = DashboardStableCollection.buildViews(context, widgetId,
                ScrollPrototypeData.state(42), true,
                Intent(context, ScrollPrototypeService::class.java)
                    .setData(android.net.Uri.parse("hacw://rc5-test/$widgetId")))
            views.setPendingIntentTemplate(R.id.legacy_list, PendingIntent.getBroadcast(context, widgetId,
                Intent(context, ScrollPrototypeReceiver::class.java)
                    .setData(android.net.Uri.parse("hacw://rc5-click/$widgetId")),
                PendingIntent.FLAG_UPDATE_CURRENT))
            manager.updateAppWidget(widgetId, views)
            ScrollPrototypeData.initialPublications++
        }
    }
}

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
