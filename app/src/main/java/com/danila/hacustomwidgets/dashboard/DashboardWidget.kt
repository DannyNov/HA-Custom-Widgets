package com.danila.hacustomwidgets.dashboard

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.R
import java.text.DateFormat
import java.util.Date

class DashboardWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val repository = (context.applicationContext as HaWidgetApplication).container.dashboards
        val states = repository.observe(appWidgetId)
        val started = System.currentTimeMillis()
        Log.d(TAG, "provideGlance started widgetId=$appWidgetId ts=$started")
        provideContent {
            val compositionStarted = SystemClock.elapsedRealtime()
            val state by states.collectAsState()
            Log.d(
                TAG,
                "COMPOSITION_START processStartId=${DashboardDiagnostics.processStartId} widgetId=$appWidgetId " +
                    "revision=${state?.stateRevision} cardsTotal=${state?.cards?.size ?: 0} " +
                    "monotonicMs=$compositionStarted",
            )
            SideEffect {
                Log.d(
                    TAG,
                    "COMPOSITION_END processStartId=${DashboardDiagnostics.processStartId} widgetId=$appWidgetId " +
                        "tab=${state?.selectedTabId} revision=${state?.stateRevision} " +
                        "durationMs=${SystemClock.elapsedRealtime() - compositionStarted}",
                )
            }
            GlanceTheme { DashboardContent(context, state, appWidgetId, LocalSize.current) }
        }
    }
}

private data class DashboardSection(
    val key: String,
    val title: String?,
    val icon: String?,
    val cards: List<DashboardCard>,
)

@Composable
private fun DashboardContent(
    context: Context,
    state: DashboardState?,
    appWidgetId: Int,
    size: DpSize,
) {
    val width = size.width.value.toInt().coerceAtLeast(180)
    val height = size.height.value.toInt().coerceAtLeast(110)
    val padding = if (width < 250) 8.dp else 10.dp
    val background = ColorProvider(R.color.widget_background)
    val primary = ColorProvider(R.color.widget_primary)
    val secondary = ColorProvider(R.color.widget_secondary)
    val accent = ColorProvider(R.color.widget_accent)

    Column(
        modifier = GlanceModifier.fillMaxSize().background(background).cornerRadius(22.dp).padding(padding),
    ) {
        DashboardHeader(context, appWidgetId, state, width, primary, accent)
        Spacer(GlanceModifier.height(5.dp))
        if (state == null) {
            Text("Настройте HA Dashboard", style = TextStyle(color = primary, fontSize = 15.sp))
            return@Column
        }
        DashboardTabs(state, appWidgetId, primary, secondary, accent)
        Spacer(GlanceModifier.height(6.dp))
        val sections = dashboardSections(state)
        if (sections.isEmpty()) {
            Text(
                if (state.selectedTabId == MAIN_TAB_ID) "Добавьте устройства во вкладку «Главное»"
                else "В этом пространстве нет доступных устройств",
                style = TextStyle(color = secondary, fontSize = 13.sp),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                sections.forEach { section ->
                    if (section.title != null) {
                        item(itemId = stableItemId("section:${section.key}")) {
                            SectionHeader(section, state, appWidgetId, primary, secondary)
                        }
                    }
                    if (section.key !in state.collapsedSections) {
                        items(
                            items = section.cards,
                            itemId = { stableItemId("card:${it.key}") },
                        ) { card ->
                            DashboardDeviceCard(
                                card = card,
                                appWidgetId = appWidgetId,
                                widthDp = width,
                                compact = state.config.compactDensity,
                                operationStatuses = state.operationStatusByEntity,
                            )
                            Spacer(GlanceModifier.height(if (state.config.compactDensity) 5.dp else 8.dp))
                        }
                    }
                }
                if (height >= 180 && (state.config.showLastUpdated || state.error != null)) {
                    item(itemId = stableItemId("footer")) {
                        val footer = state.error?.let { "⚠ Ошибка обновления: $it" }
                            ?: "↻ ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.lastUpdatedMillis))}"
                        Text(
                            footer,
                            maxLines = 1,
                            style = TextStyle(
                                color = if (state.error == null) secondary else ColorProvider(R.color.widget_problem),
                                fontSize = 9.sp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardHeader(
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

@Composable
private fun DashboardTabs(
    state: DashboardState,
    appWidgetId: Int,
    primary: ColorProvider,
    secondary: ColorProvider,
    accent: ColorProvider,
) {
    val tabs = state.tabs
    val selectedIndex = tabs.indexOfFirst { it.id == state.selectedTab.id }.coerceAtLeast(0)
    Row(
        modifier = GlanceModifier.fillMaxWidth().background(ColorProvider(R.color.widget_tile))
            .cornerRadius(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier.width(48.dp).height(48.dp).clickable(
                actionRunCallback<DashboardNavigateAction>(
                    actionParametersOf(DashboardWidgetIdKey to appWidgetId, DashboardTabKey to MAIN_TAB_ID),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text("★", style = TextStyle(color = if (selectedIndex == 0) accent else secondary, fontSize = 15.sp))
        }
        Box(
            modifier = GlanceModifier.width(48.dp).height(48.dp).clickable(
                actionRunCallback<DashboardNavigateAction>(
                    actionParametersOf(
                        DashboardWidgetIdKey to appWidgetId,
                        DashboardTabKey to tabs[(selectedIndex - 1 + tabs.size) % tabs.size].id,
                    ),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", style = TextStyle(color = secondary, fontSize = 20.sp))
        }
        Box(
            modifier = GlanceModifier.defaultWeight().height(48.dp).clickable(
                actionRunCallback<DashboardNavigateAction>(
                    actionParametersOf(
                        DashboardWidgetIdKey to appWidgetId,
                        DashboardTabKey to tabs[(selectedIndex + 1) % tabs.size].id,
                    ),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (state.selectedTab.id == MAIN_TAB_ID) "Главное" else state.selectedTab.name,
                maxLines = 1,
                style = TextStyle(
                    color = primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
            )
        }
        Box(
            modifier = GlanceModifier.width(48.dp).height(48.dp).clickable(
                actionRunCallback<DashboardNavigateAction>(
                    actionParametersOf(
                        DashboardWidgetIdKey to appWidgetId,
                        DashboardTabKey to tabs[(selectedIndex + 1) % tabs.size].id,
                    ),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text("›", style = TextStyle(color = secondary, fontSize = 20.sp))
        }
    }
}

@Composable
private fun SectionHeader(
    section: DashboardSection,
    state: DashboardState,
    appWidgetId: Int,
    primary: ColorProvider,
    secondary: ColorProvider,
) {
    val collapsed = section.key in state.collapsedSections
    Text(
        "${if (collapsed) "▶" else "▼"} ${section.icon.orEmpty()} ${section.title} (${section.cards.size})",
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).clickable(
            actionRunCallback<DashboardToggleSectionAction>(
                actionParametersOf(
                    DashboardWidgetIdKey to appWidgetId,
                    DashboardSectionKey to section.key,
                ),
            ),
        ),
        maxLines = 1,
        style = TextStyle(
            color = if (collapsed) secondary else primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}

@Composable
private fun DashboardDeviceCard(
    card: DashboardCard,
    appWidgetId: Int,
    widthDp: Int,
    compact: Boolean,
    operationStatuses: Map<String, DashboardOperationStatus>,
) {
    val primaryControl = card.controls.firstOrNull()
    val unavailable = card.metrics.any { it.rawState == "unavailable" } ||
        card.controls.any { it.state == "unavailable" }
    val unknown = !unavailable && (
        card.metrics.any { it.rawState == "unknown" } || card.controls.any { it.state == "unknown" }
    )
    val active = card.controls.any { it.state in ACTIVE_STATES }
    val semantic = when {
        unavailable -> ColorProvider(R.color.widget_problem)
        primaryControl?.domain == "light" && active -> ColorProvider(R.color.widget_light_on)
        active -> ColorProvider(R.color.widget_switch_on)
        else -> ColorProvider(R.color.widget_secondary)
    }
    val surface = when {
        unavailable || unknown -> ColorProvider(R.color.widget_tile_muted)
        primaryControl?.domain == "light" && active -> ColorProvider(R.color.widget_light_surface)
        active -> ColorProvider(R.color.widget_active_surface)
        else -> ColorProvider(R.color.widget_tile)
    }
    Column(
        modifier = GlanceModifier.fillMaxWidth()
            .background(if (unavailable) ColorProvider(R.color.widget_problem) else surface)
            .cornerRadius(14.dp)
            .padding(if (unavailable) 2.dp else 0.dp),
    ) {
        Column(
            modifier = GlanceModifier.fillMaxWidth().background(surface).cornerRadius(12.dp)
                .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = if (compact) 7.dp else 10.dp),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.title,
                    modifier = GlanceModifier.defaultWeight(),
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(R.color.widget_primary),
                        fontSize = if (compact) 12.sp else 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                if (unavailable) {
                    Text("⚠ Недоступно", style = TextStyle(color = semantic, fontSize = 10.sp))
                } else if (unknown) {
                    Text("?", style = TextStyle(color = semantic, fontSize = 12.sp))
                } else if (card.controls.size == 1) {
                    val control = card.controls.first()
                    Text(
                        controlLabel(control, operationStatuses[control.entityId]),
                        modifier = GlanceModifier.padding(horizontal = 8.dp, vertical = 5.dp).clickable(
                            actionRunCallback<DashboardControlAction>(
                                actionParametersOf(
                                    DashboardWidgetIdKey to appWidgetId,
                                    DashboardDeviceKey to card.key,
                                    DashboardEntityKey to control.entityId,
                                    DashboardDomainKey to control.domain,
                                ),
                            ),
                        ),
                        style = TextStyle(color = semantic, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
            if (card.controls.size > 1 && !unavailable) {
                Spacer(GlanceModifier.height(if (compact) 3.dp else 5.dp))
                val controlColumns = if (widthDp >= 320) 3 else 2
                val controlWidth = ((widthDp - 36) / controlColumns).coerceAtLeast(76)
                card.controls.chunked(controlColumns).forEachIndexed { index, controls ->
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        controls.forEach { control ->
                            val controlActive = control.state in ACTIVE_STATES
                            val controlColor = when {
                                control.domain == "light" && controlActive -> ColorProvider(R.color.widget_light_on)
                                controlActive -> ColorProvider(R.color.widget_switch_on)
                                else -> ColorProvider(R.color.widget_secondary)
                            }
                            Text(
                                "${control.label} ${controlLabel(control, operationStatuses[control.entityId])}",
                                modifier = GlanceModifier.width(controlWidth.dp)
                                    .padding(horizontal = 4.dp, vertical = 5.dp)
                                    .clickable(
                                        actionRunCallback<DashboardControlAction>(
                                            actionParametersOf(
                                                DashboardWidgetIdKey to appWidgetId,
                                                DashboardDeviceKey to card.key,
                                                DashboardEntityKey to control.entityId,
                                                DashboardDomainKey to control.domain,
                                            ),
                                        ),
                                    ),
                                maxLines = 1,
                                style = TextStyle(color = controlColor, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                    if (index < card.controls.chunked(controlColumns).lastIndex) {
                        Spacer(GlanceModifier.height(2.dp))
                    }
                }
            }
            if (card.metrics.isNotEmpty()) {
                Spacer(GlanceModifier.height(if (compact) 3.dp else 5.dp))
                val columns = when {
                    widthDp >= 340 -> 3
                    widthDp >= 230 -> 2
                    else -> 1
                }
                val metricRows = card.metrics.chunked(columns)
                metricRows.forEachIndexed { index, rowMetrics ->
                    MetricLine(rowMetrics, columns, widthDp, compact)
                    if (index < metricRows.lastIndex) {
                        Spacer(GlanceModifier.height(2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricLine(metrics: List<DashboardMetric>, columns: Int, widthDp: Int, compact: Boolean) {
    val cellWidth = ((widthDp - 36) / columns).coerceAtLeast(70)
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        metrics.forEach { metric ->
            val icon = metricIcon(metric)
            val text = if (icon == "•") "${metric.label}: ${metric.state}" else "$icon ${metric.state}"
            Text(
                text,
                modifier = GlanceModifier.width(cellWidth.dp).padding(end = 3.dp),
                maxLines = 1,
                style = TextStyle(
                    color = when {
                        metric.rawState == "unavailable" -> ColorProvider(R.color.widget_problem)
                        batteryHealth(metric) == BatteryHealth.CRITICAL -> ColorProvider(R.color.widget_problem)
                        batteryHealth(metric) == BatteryHealth.LOW -> ColorProvider(R.color.widget_warning)
                        else -> ColorProvider(R.color.widget_primary)
                    },
                    fontSize = if (compact) 11.sp else 12.sp,
                ),
            )
        }
    }
}

private fun dashboardSections(state: DashboardState): List<DashboardSection> {
    val tab = state.selectedTab
    val cards = if (tab.id == MAIN_TAB_ID) {
        state.config.favoriteDeviceKeys.mapNotNull { key -> state.cards.firstOrNull { it.key == key } }
    } else {
        val areaIds = tab.roomAreaIds.toSet()
        val knownAreaIds = state.spaces.flatMap { it.roomAreaIds }.toSet()
        state.cards.filter { card ->
            card.areaId in areaIds ||
                (tab.id == "__unassigned_space__" && (card.areaId == null || card.areaId !in knownAreaIds))
        }
            .orderedBy(state.config.cardOrderBySpace[tab.id])
    }
    val grouping = if (tab.id == MAIN_TAB_ID) DashboardGrouping.NONE
    else state.config.groupingBySpace[tab.id] ?: DashboardGrouping.TYPES
    return when (grouping) {
        DashboardGrouping.NONE -> listOf(DashboardSection("${tab.id}:all", null, null, cards)).filter { cards.isNotEmpty() }
        DashboardGrouping.ROOMS -> {
            if (tab.roomAreaIds.size <= 1) {
                listOf(DashboardSection("${tab.id}:all", null, null, cards)).filter { cards.isNotEmpty() }
            } else cards.groupBy { it.roomName ?: "Без помещения" }.map { (room, items) ->
                DashboardSection("${tab.id}:room:$room", room, "🏠", items)
            }
        }
        DashboardGrouping.TYPES -> cards.groupBy { it.category }.entries
            .sortedBy { it.key.rank }
            .map { (category, items) ->
                DashboardSection("${tab.id}:type:${category.name}", category.title, category.icon, items)
            }
    }
}

private fun List<DashboardCard>.orderedBy(order: List<String>?): List<DashboardCard> {
    if (order.isNullOrEmpty()) return sortedBy { it.title.lowercase() }
    val rank = order.withIndex().associate { it.value to it.index }
    return sortedWith(compareBy<DashboardCard> { rank[it.key] ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })
}

private fun controlLabel(
    control: DashboardControl,
    status: DashboardOperationStatus?,
): String = when {
    status?.isActive == true -> "…"
    status == DashboardOperationStatus.CONFIRMED && control.domain in setOf("button", "script", "scene") -> "✓"
    status == DashboardOperationStatus.FAILED || status == DashboardOperationStatus.TIMEOUT -> "⚠"
    control.domain in setOf("button", "script", "scene") -> "Запустить"
    control.domain == "timer" && control.state == "active" -> "Ⅱ"
    control.domain == "timer" && control.state in setOf("idle", "paused") -> "▶"
    control.domain == "timer" -> "—"
    control.state in ACTIVE_STATES -> "ВКЛ"
    else -> "ВЫКЛ"
}

private fun stableItemId(value: String): Long = DashboardStatePolicy.stableCollectionId(value)
private val ACTIVE_STATES = setOf("on", "open", "active", "playing", "heat", "cool", "home")

class DashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DashboardWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val container = (context.applicationContext as HaWidgetApplication).container
        Log.i(
            TAG,
            "APPWIDGET_UPDATE processStartId=${DashboardDiagnostics.processStartId} " +
                "widgetIds=${appWidgetIds.joinToString()} source=SYSTEM",
        )
        container.dashboardEvents.ensureStarted("APPWIDGET_UPDATE")
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val container = (context.applicationContext as HaWidgetApplication).container
        appWidgetIds.forEach(container.dashboards::delete)
        container.dashboardEvents.stopIfUnused()
        super.onDeleted(context, appWidgetIds)
    }
}

private const val TAG = "HAWidgetDashboard"
