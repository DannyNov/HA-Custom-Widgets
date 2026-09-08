package com.danila.hacustomwidgets.dashboard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.R
import com.danila.hacustomwidgets.tr
import kotlinx.coroutines.*
import java.time.Instant
import java.text.DateFormat
import java.util.Date

/** No revision, entity payload, tab, or session token belongs in the adapter identity. */
object LegacyCollectionPolicy {
    fun adapterIdentity(widgetId: Int) = "hacw://dashboard/$widgetId/collection/v1"
    // RC3: use the RC1 Glance renderer on all supported APIs. The native collection
    // also needs a new click-addressing design before it can safely be enabled again.
    @Suppress("UNUSED_PARAMETER")
    fun useLegacy(api: Int) = false
}

/** Native legacy host/rows; ordering, grouping, actions and state use the shared Dashboard policies. */
object DashboardLegacyCollection {
    fun update(context: Context, id: Int, bind: Boolean = false) {
        val manager = AppWidgetManager.getInstance(context)
        val repository = (context.applicationContext as HaWidgetApplication).container.dashboards
        val state = repository.get(id)
        val prefs = context.getSharedPreferences("dashboard_legacy_hosts", Context.MODE_PRIVATE)
        val initial = bind || !prefs.getBoolean("bound:$id", false)
        val views = buildViews(context, id, state, initial)
        if (initial) {
            manager.updateAppWidget(id, views)
            prefs.edit().putBoolean("bound:$id", true).apply()
        } else {
            // This patch contains only header setters. It never replaces or binds the ListView.
            manager.partiallyUpdateAppWidget(id, views)
        }
        manager.notifyAppWidgetViewDataChanged(id, R.id.legacy_list)
        android.util.Log.d("HAWidgetLegacy", "${if (initial) "BIND" else "DATA_ONLY"} widgetId=$id adapter=${LegacyCollectionPolicy.adapterIdentity(id)}")
    }

    internal fun buildViews(context: Context, id: Int, state: DashboardState?, initial: Boolean,
        adapterIntent: Intent = Intent(context, DashboardLegacyService::class.java)
            .setData(Uri.parse(LegacyCollectionPolicy.adapterIdentity(id)))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.dashboard_legacy)
        if (initial) {
            views.setRemoteAdapter(R.id.legacy_list, adapterIntent)
            views.setPendingIntentTemplate(R.id.legacy_list, PendingIntent.getBroadcast(context, id,
                Intent(context, DashboardLegacyActionReceiver::class.java)
                    .setData(Uri.parse("hacw://dashboard/$id/action")),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            views.setEmptyView(R.id.legacy_list, R.id.legacy_empty)
        }
        views.setTextViewText(R.id.legacy_empty, if (state == null) tr("Configure HA Dashboard", "Настройте HA Dashboard")
            else tr("No available devices", "Нет доступных устройств"))
        views.setViewVisibility(R.id.legacy_tabs, if (state == null) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.legacy_updated, if (state?.config?.showLastUpdated == true)
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.lastUpdatedMillis)) else "")
        views.setTextViewText(R.id.legacy_refresh, if (state?.error == null) "↻" else "!")
        views.setOnClickPendingIntent(R.id.legacy_refresh, pending(context, id, "refresh"))
        val settings = Intent(context, DashboardWidgetConfigActivity::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .setData(Uri.parse("hacw://dashboard/$id/settings"))
        views.setOnClickPendingIntent(R.id.legacy_settings, PendingIntent.getActivity(context, id, settings,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (state != null) {
            val tabs = state.tabs
            val index = tabs.indexOfFirst { it.id == state.selectedTabId }.coerceAtLeast(0)
            val next = tabs[(index + 1) % tabs.size].id
            views.setTextViewText(R.id.legacy_tab, state.selectedTab.name)
            views.setOnClickPendingIntent(R.id.legacy_home, pending(context, id, "navigate", MAIN_TAB_ID))
            views.setOnClickPendingIntent(R.id.legacy_prev, pending(context, id, "navigate", tabs[(index - 1 + tabs.size) % tabs.size].id))
            views.setOnClickPendingIntent(R.id.legacy_next, pending(context, id, "navigate", next))
            views.setOnClickPendingIntent(R.id.legacy_tab, pending(context, id, "navigate", next))
        }
        return views
    }

    fun forget(context: Context, id: Int) {
        context.getSharedPreferences("dashboard_legacy_hosts", Context.MODE_PRIVATE).edit().remove("bound:$id").apply()
    }

    private fun pending(context: Context, id: Int, action: String, key: String = ""): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, DashboardLegacyActionReceiver::class.java)
            .setData(Uri.parse("hacw://dashboard/$id/$action/${Uri.encode(key)}"))
            .putExtra("widget", id).putExtra("action", action).putExtra("key", key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class DashboardLegacyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("widget", -1)
        if (id < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(id)
                val key = intent.getStringExtra("key").orEmpty()
                val parameters = actionParametersOf(DashboardWidgetIdKey to id,
                    DashboardTabKey to key, DashboardSectionKey to key, DashboardDeviceKey to key,
                    DashboardEntityKey to intent.getStringExtra("entity").orEmpty(),
                    DashboardDomainKey to intent.getStringExtra("domain").orEmpty())
                val callback = when (intent.getStringExtra("action")) {
                    "navigate" -> DashboardNavigateAction()
                    "section" -> DashboardToggleSectionAction()
                    "control" -> DashboardControlAction()
                    "timer" -> DashboardTimerAction()
                    "scenario" -> DashboardRunScenarioAction()
                    "refresh" -> DashboardRefreshAction()
                    else -> return@launch
                }
                callback.onAction(context, glanceId, parameters)
            } finally { pending.finish() }
        }
    }
}

class DashboardLegacyService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(this,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

    internal class Factory(val context: Context, val id: Int) : RemoteViewsFactory {
        private data class Item(val stableKey: String, val views: RemoteViews)
        @Volatile private var items: List<Item> = emptyList()
        override fun onCreate() = Unit
        override fun onDestroy() { items = emptyList() }
        override fun getCount() = items.size
        override fun getViewAt(position: Int) = items.getOrNull(position)?.views
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 2
        override fun getItemId(position: Int) = items.getOrNull(position)?.stableKey
            ?.let(DashboardStatePolicy::stableCollectionId) ?: 0L
        override fun hasStableIds() = true
        override fun onDataSetChanged() {
            val state = (context.applicationContext as HaWidgetApplication).container.dashboards.get(id)
            items = if (state == null) emptyList() else buildList {
                dashboardSections(state).forEach { section ->
                    if (section.title != null) add(Item("section:${section.key}", rv(R.layout.dashboard_legacy_section).apply {
                        setTextViewText(R.id.legacy_section, "${if (section.key in state.collapsedSections) "▸" else "▾"} ${section.icon.orEmpty()} ${section.title} (${section.cards.size})")
                        setOnClickFillInIntent(R.id.legacy_section, action("section", section.key))
                    }))
                    if (section.key !in state.collapsedSections) section.cards.forEach {
                        add(Item("card:${it.key}", card(it, state)))
                    }
                }
            }
        }

        private fun rv(layout: Int) = RemoteViews(context.packageName, layout)
        private fun action(kind: String, key: String, control: DashboardControl? = null) = Intent()
            .putExtra("widget", id).putExtra("action", kind).putExtra("key", key)
            .putExtra("entity", control?.entityId).putExtra("domain", control?.domain)

        private fun button(icon: Int, background: Int, label: String, intent: Intent,
                           status: DashboardOperationStatus? = null, weighted: Boolean = false) = rv(if (weighted) R.layout.dashboard_legacy_button_weighted else R.layout.dashboard_legacy_button).apply {
            setImageViewResource(R.id.legacy_button_icon, icon)
            setInt(R.id.legacy_button_icon, "setBackgroundResource", background)
            setContentDescription(R.id.legacy_button, label.ifBlank {
                if (icon == R.drawable.ic_power) tr("Power", "Питание") else tr("Run", "Запустить")
            })
            setTextViewText(R.id.legacy_button_label, label)
            setTextViewText(R.id.legacy_button_status, when {
                status?.isActive == true -> "…"
                status in setOf(DashboardOperationStatus.FAILED, DashboardOperationStatus.TIMEOUT) -> "!"
                else -> ""
            })
            setOnClickFillInIntent(R.id.legacy_button, intent)
        }

        internal fun card(card: DashboardCard, state: DashboardState): RemoteViews = rv(R.layout.dashboard_legacy_card).apply {
            // Reapply replays actions on the old hierarchy; XML defaults do not run again.
            // Retained for regression coverage, NOT sufficient to re-enable this renderer:
            // nested fill-in intents are rejected by Android 8's collection-child check.
            removeAllViews(R.id.legacy_header_controls)
            removeAllViews(R.id.legacy_controls)
            removeAllViews(R.id.legacy_metrics)
            setTextViewText(R.id.legacy_remaining, "")
            setTextColor(R.id.legacy_remaining, context.getColor(R.color.widget_primary))
            setTextViewText(R.id.legacy_card_title, card.title)
            setTextViewTextSize(R.id.legacy_card_title, android.util.TypedValue.COMPLEX_UNIT_SP,
                if (state.config.compactDensity) 12f else 14f)
            setViewVisibility(R.id.legacy_remaining, View.GONE)
            val unavailable = card.visibleControls.any { it.state == "unavailable" } ||
                card.metrics.any { it.rawState == "unavailable" && batteryHealth(it) == BatteryHealth.NOT_BATTERY }
            val scenario = card.key.startsWith("scenario:")
            val primary = AutoOffTimerPolicy.resolveControl(card.controls, card.autoOffTimer)
            val active = card.visibleControls.any { it.state in setOf("on", "active", "open", "playing", "heat", "cool", "home") }
            setInt(R.id.legacy_card, "setBackgroundResource", when {
                unavailable -> R.drawable.legacy_tile_problem
                card.visibleControls.firstOrNull()?.domain == "light" && active -> R.drawable.legacy_tile_light
                active -> R.drawable.legacy_tile_active
                else -> R.drawable.legacy_tile
            })
            fun power(control: DashboardControl, labelled: Boolean, weighted: Boolean = labelled): RemoteViews {
                val color = when (PrimaryPowerButtonPolicy.tone(control)) {
                    PrimaryPowerButtonTone.OFF -> R.drawable.circle_secondary
                    PrimaryPowerButtonTone.LIGHT_ON_GREEN -> R.drawable.circle_active_surface
                    PrimaryPowerButtonTone.SWITCH_ON_YELLOW -> R.drawable.circle_light_surface
                }
                return button(if (control.domain in setOf("script", "scene", "button")) R.drawable.ic_launch_play else R.drawable.ic_power,
                    color, if (labelled) control.label else "", action("control", card.key, control), state.operationStatusByEntity[control.entityId], weighted)
            }
            if (unavailable) {
                setViewVisibility(R.id.legacy_remaining, View.VISIBLE)
                setTextViewText(R.id.legacy_remaining, tr("Unavailable", "Недоступно"))
                setTextColor(R.id.legacy_remaining, context.getColor(R.color.widget_problem))
            } else if (scenario) {
                card.controls.firstOrNull()?.let { control ->
                    if (card.scenarioRunnable) {
                        val status = state.scenarioRunStatusByEntity[control.entityId]
                        addView(R.id.legacy_header_controls, button(scenarioLaunchIcon(status), when (status) {
                            DashboardOperationStatus.CONFIRMED -> R.drawable.circle_switch_on
                            DashboardOperationStatus.FAILED, DashboardOperationStatus.TIMEOUT -> R.drawable.circle_problem
                            else -> R.drawable.circle_accent
                        }, "", action("scenario", card.key, control)))
                    }
                    if (ScenarioDisplayPolicy.showStateToggle(control.domain)) addView(R.id.legacy_header_controls, power(control, false))
                }
            } else if (card.autoOffTimer != null && primary != null) {
                val presentation = card.timerState?.let { HaTimerPresentationPolicy.resolve(it, Instant.now()) }
                val minutes = AutoOffTimerPolicy.displayedPresetMinutes(card.autoOffTimer,
                    presentation?.status ?: HaTimerStatus.UNKNOWN, presentation?.actualDurationMinutes)
                val timerRow = rv(R.layout.dashboard_legacy_controls_row)
                timerRow.addView(R.id.legacy_controls_row, power(primary, false, weighted = true))
                timerRow.addView(R.id.legacy_controls_row, button(R.drawable.ic_timer,
                    if (presentation?.status == HaTimerStatus.ACTIVE) R.drawable.circle_timer_active else R.drawable.circle_accent,
                    minutes?.let { tr("$it min", "$it мин") } ?: "—", action("timer", card.key), weighted = true))
                addView(R.id.legacy_controls, timerRow)
                presentation?.formattedRemaining?.takeIf { presentation.status in setOf(HaTimerStatus.ACTIVE, HaTimerStatus.PAUSED) }?.let {
                    setViewVisibility(R.id.legacy_remaining, View.VISIBLE)
                    setTextViewText(R.id.legacy_remaining, if (presentation.status == HaTimerStatus.PAUSED) tr("Paused · $it", "Пауза · $it") else tr("Remaining $it", "Осталось $it"))
                }
            } else if (card.visibleControls.size == 1) addView(R.id.legacy_header_controls, power(card.visibleControls.first(), false))
            if (!unavailable && !scenario && card.visibleControls.size > 1) card.visibleControls.chunked(2).forEach { controls ->
                val controlRow = rv(R.layout.dashboard_legacy_controls_row)
                controls.forEach { control -> controlRow.addView(R.id.legacy_controls_row, power(control, true)) }
                addView(R.id.legacy_controls, controlRow)
            }
            val width = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            MetricLayoutPolicy.rows(card.metrics, width).forEach { metrics ->
                val row = rv(R.layout.dashboard_legacy_metric_row)
                metrics.forEach { metric ->
                    val policy = MetricPresentationPolicy.resolve(metric)
                    row.addView(R.id.legacy_metric_row, rv(R.layout.dashboard_legacy_metric).apply {
                        setViewVisibility(R.id.legacy_metric_icon, if (policy.showLabel) View.GONE else View.VISIBLE)
                        setImageViewResource(R.id.legacy_metric_icon, if (policy.semantic == HaSemanticIcon.BATTERY) batteryIconResource(metric) else metricIconResource(policy.semantic))
                        setTextViewText(R.id.legacy_metric_text, if (policy.showLabel) "${metric.label}: ${metric.state}" else batteryDisplayState(metric))
                        setTextColor(R.id.legacy_metric_text, context.getColor(when {
                            batteryHealth(metric) == BatteryHealth.UNKNOWN -> R.color.widget_secondary
                            metric.rawState == "unavailable" || batteryHealth(metric) == BatteryHealth.CRITICAL -> R.color.widget_problem
                            batteryHealth(metric) == BatteryHealth.LOW -> R.color.widget_warning
                            batteryHealth(metric) == BatteryHealth.NORMAL -> R.color.widget_switch_on
                            else -> R.color.widget_primary
                        }))
                    })
                }
                addView(R.id.legacy_metrics, row)
            }
        }
    }
}
