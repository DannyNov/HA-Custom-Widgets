package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.danila.hacustomwidgets.R
import com.danila.hacustomwidgets.tr
import java.time.Instant

/** Prototype: every action is attached to the factory's root RemoteViews, never to addView children. */
internal class DashboardStableRows(private val context: Context, private val widgetId: Int) {
    data class Row(val key: String, val views: RemoteViews)
    private data class Button(val label: String, val icon: Int, val background: Int,
        val intent: Intent, val status: DashboardOperationStatus?)

    private fun action(kind: String, key: String, control: DashboardControl? = null) = Intent()
        .putExtra("widget", widgetId).putExtra("action", kind).putExtra("key", key)
        .putExtra("entity", control?.entityId).putExtra("domain", control?.domain)

    fun rows(state: DashboardState): List<Row> = buildList {
        dashboardSections(state).forEach { section ->
            if (section.title != null) add(Row("section:${section.key}",
                RemoteViews(context.packageName, R.layout.dashboard_legacy_section).apply {
                    setTextViewText(R.id.legacy_section,
                        "${if (section.key in state.collapsedSections) "▸" else "▾"} ${section.title} (${section.cards.size})")
                    setOnClickFillInIntent(R.id.legacy_section, action("section", section.key))
                }))
            if (section.key !in state.collapsedSections) section.cards.forEach { addAll(card(it, state)) }
        }
    }

    fun card(card: DashboardCard, state: DashboardState): List<Row> {
        val unavailable = card.visibleControls.any { it.state == "unavailable" } ||
            card.metrics.any { it.rawState == "unavailable" && batteryHealth(it) == BatteryHealth.NOT_BATTERY }
        val active = card.visibleControls.any { it.state in setOf("on", "active", "open", "playing", "heat", "cool", "home") }
        fun power(control: DashboardControl) = Button(control.label,
            if (control.domain in setOf("scene", "script", "button", "input_button")) R.drawable.ic_launch_play else R.drawable.ic_power,
            when (PrimaryPowerButtonPolicy.tone(control)) {
                PrimaryPowerButtonTone.OFF -> R.drawable.circle_secondary
                PrimaryPowerButtonTone.LIGHT_ON_GREEN -> R.drawable.circle_active_surface
                PrimaryPowerButtonTone.SWITCH_ON_YELLOW -> R.drawable.circle_light_surface
            }, action("control", card.key, control), state.operationStatusByEntity[control.entityId])
        val presentation = card.timerState?.let { HaTimerPresentationPolicy.resolve(it, Instant.now()) }
        val minutes = card.autoOffTimer?.let { AutoOffTimerPolicy.displayedPresetMinutes(it,
            presentation?.status ?: HaTimerStatus.UNKNOWN, presentation?.actualDurationMinutes) }
        // Button count depends on configuration, never on transient state: unavailable rows keep slots.
        val buttons = buildList {
            if (card.key.startsWith("scenario:")) {
                card.controls.firstOrNull()?.let { control ->
                    if (card.scenarioRunnable) {
                        val status = state.scenarioRunStatusByEntity[control.entityId]
                        add(Button(tr("Run", "Запустить"), scenarioLaunchIcon(status), R.drawable.circle_accent,
                            action("scenario", card.key, control), status))
                    }
                    if (ScenarioDisplayPolicy.showStateToggle(control.domain)) add(power(control))
                }
            } else {
                val primary = AutoOffTimerPolicy.resolveControl(card.controls, card.autoOffTimer)
                if (card.autoOffTimer != null && primary != null) {
                    add(power(primary))
                    add(Button(minutes?.let { tr("$it min", "$it мин") } ?: "—", R.drawable.ic_timer,
                        if (presentation?.status == HaTimerStatus.ACTIVE) R.drawable.circle_timer_active else R.drawable.circle_accent,
                        action("timer", card.key), state.operationStatusByEntity[card.autoOffTimer.timerEntityId]))
                    card.visibleControls.filter { it.entityId != primary.entityId }.forEach { add(power(it)) }
                } else card.visibleControls.forEach { add(power(it)) }
            }
        }
        // Unbounded input is paged into fixed structures; no control or metric is silently discarded.
        val count = maxOf(1, (buttons.size + 7) / 8, (card.metrics.size + 11) / 12)
        return (0 until count).map { page ->
            val pageButtons = buttons.drop(page * 8).take(8)
            val metrics = card.metrics.drop(page * 12).take(12)
            val views = RemoteViews(context.packageName, R.layout.dashboard_stable_card)
            fun id(name: String) = context.resources.getIdentifier("stable_$name", "id", context.packageName)
            fun visible(name: String, value: Boolean) = views.setViewVisibility(id(name), if (value) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.stable_title, card.title)
            views.setTextViewTextSize(R.id.stable_title, android.util.TypedValue.COMPLEX_UNIT_SP,
                if (state.config.compactDensity) 12f else 14f)
            views.setInt(R.id.stable_card, "setBackgroundResource", when {
                unavailable -> R.drawable.legacy_tile_problem
                active && card.visibleControls.firstOrNull()?.domain == "light" -> R.drawable.legacy_tile_light
                active -> R.drawable.legacy_tile_active
                else -> R.drawable.legacy_tile
            })
            repeat(4) { visible("controls_$it", it * 2 < pageButtons.size) }
            repeat(8) { slot ->
                val button = pageButtons.getOrNull(slot)
                visible("button_$slot", button != null)
                views.setBoolean(id("button_$slot"), "setEnabled", button != null && !unavailable)
                views.setTextViewText(id("label_$slot"), button?.label.orEmpty())
                views.setImageViewResource(id("icon_$slot"), PendingGlyphPolicy.icon(button?.icon ?: R.drawable.ic_power, button?.status))
                views.setInt(id("icon_$slot"), "setBackgroundResource", button?.background ?: R.drawable.circle_secondary)
                views.setContentDescription(id("button_$slot"), button?.label.orEmpty())
                // Empty/disabled slots are reset too, so recycled rows cannot deliver an old action.
                views.setOnClickFillInIntent(id("button_$slot"), button?.intent ?: Intent())
            }
            val remaining = when {
                unavailable -> tr("Unavailable", "Недоступно")
                page != 0 -> ""
                presentation?.status in setOf(HaTimerStatus.ACTIVE, HaTimerStatus.PAUSED) -> {
                    val millis = presentation?.remainingMillis ?: 0L
                    val value = HaTimerPresentationPolicy.formatRemaining(minutes?.let { minOf(millis, it * 60_000L) } ?: millis)
                    if (presentation?.status == HaTimerStatus.PAUSED) tr("Paused · $value", "Пауза · $value") else tr("Remaining $value", "Осталось $value")
                }
                else -> ""
            }
            views.setTextViewText(R.id.stable_remaining, remaining)
            visible("remaining", remaining.isNotEmpty())
            repeat(6) { visible("metrics_$it", it * 2 < metrics.size) }
            repeat(12) { slot ->
                val metric = metrics.getOrNull(slot)
                visible("metric_$slot", metric != null)
                val policy = metric?.let(MetricPresentationPolicy::resolve)
                visible("metric_icon_$slot", metric != null && policy?.showLabel == false)
                views.setImageViewResource(id("metric_icon_$slot"), if (metric == null) R.drawable.ic_metric_battery
                    else if (policy?.semantic == HaSemanticIcon.BATTERY) batteryIconResource(metric) else metricIconResource(policy!!.semantic))
                views.setTextViewText(id("metric_text_$slot"), if (metric == null) "" else
                    if (policy?.showLabel == true) "${metric.label}: ${metric.state}" else batteryDisplayState(metric))
                views.setTextColor(id("metric_text_$slot"), context.getColor(when {
                    metric == null || batteryHealth(metric) == BatteryHealth.UNKNOWN -> R.color.widget_secondary
                    metric.rawState == "unavailable" || batteryHealth(metric) == BatteryHealth.CRITICAL -> R.color.widget_problem
                    batteryHealth(metric) == BatteryHealth.LOW -> R.color.widget_warning
                    batteryHealth(metric) == BatteryHealth.NORMAL -> R.color.widget_switch_on
                    else -> R.color.widget_primary
                }))
            }
            Row("card:${card.key}:$page", views)
        }
    }
}
