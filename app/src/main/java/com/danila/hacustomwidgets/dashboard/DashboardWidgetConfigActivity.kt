package com.danila.hacustomwidgets.dashboard

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.data.AppContainer
import com.danila.hacustomwidgets.data.model.HaCatalog
import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.model.HaCatalog.Companion.UNASSIGNED_SPACE_ID
import com.danila.hacustomwidgets.ui.HaCustomWidgetsTheme
import kotlinx.coroutines.launch

class DashboardWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var inAppMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inAppMode = intent?.getBooleanExtra(EXTRA_IN_APP_SETTINGS, false) == true
        if (!inAppMode) setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val container = (application as HaWidgetApplication).container
        container.dashboardEvents.ensureStarted("CONFIGURATION_OPEN")
        setContent {
            HaCustomWidgetsTheme {
                DashboardConfigurator(
                    container = container,
                    appWidgetId = appWidgetId,
                    existing = container.dashboards.getConfig(appWidgetId),
                    onSave = { config, catalog ->
                        container.dashboards.saveConfiguration(config, catalog)
                        container.dashboardEvents.ensureStarted("CONFIGURATION_SAVE", reconcileIfStale = false)
                        container.dashboardEvents.requestReconciliation(
                            "CONFIGURATION_SAVE", force = true, appWidgetId = appWidgetId,
                        )
                        updateDashboardWidget(
                            this@DashboardWidgetConfigActivity,
                            appWidgetId,
                            "configuration",
                        )
                        if (!inAppMode) {
                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                            )
                        }
                        finish()
                    },
                )
            }
        }
    }

    companion object { const val EXTRA_IN_APP_SETTINGS = "dashboard_in_app_settings" }
}

internal enum class ConfigScreen { OVERVIEW, FAVORITES, ENTITIES, TIMER, SPACE_CARDS, GROUP_ORDER, SCENARIOS }

internal fun previousConfigScreen(screen: ConfigScreen): ConfigScreen = when (screen) {
    ConfigScreen.TIMER -> ConfigScreen.ENTITIES
    ConfigScreen.ENTITIES -> ConfigScreen.FAVORITES
    else -> ConfigScreen.OVERVIEW
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardConfigurator(
    container: AppContainer,
    appWidgetId: Int,
    existing: DashboardConfig?,
    onSave: suspend (DashboardConfig, HaCatalog) -> Unit,
) {
    var catalog by remember { mutableStateOf<HaCatalog?>(null) }
    var status by remember { mutableStateOf("Синхронизирую структуру Home Assistant…") }
    var screen by remember { mutableStateOf(ConfigScreen.OVERVIEW) }
    var currentDevice by remember { mutableStateOf<HaDeviceGroup?>(null) }
    var currentSpaceId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var visibleSpaces by remember { mutableStateOf(existing?.visibleSpaceIds.orEmpty()) }
    var spaceOrder by remember { mutableStateOf(existing?.spaceOrderIds.orEmpty()) }
    var grouping by remember { mutableStateOf(existing?.groupingBySpace.orEmpty()) }
    var favorites by remember { mutableStateOf(existing?.favoriteDeviceKeys.orEmpty()) }
    var entityOrder by remember { mutableStateOf(existing?.entityOrderByDevice.orEmpty()) }
    var cardOrder by remember { mutableStateOf(existing?.cardOrderBySpace.orEmpty()) }
    var showUpdated by remember { mutableStateOf(existing?.showLastUpdated ?: true) }
    var compact by remember { mutableStateOf(existing?.compactDensity ?: true) }
    var scenariosEnabled by remember { mutableStateOf(existing?.scenariosEnabled ?: true) }
    var automationVisible by remember { mutableStateOf(existing?.scenarioAutomationVisible ?: true) }
    var scriptVisible by remember { mutableStateOf(existing?.scenarioScriptVisible ?: true) }
    var scenarioOrder by remember { mutableStateOf(existing?.scenarioOrderBySpaceAndDomain.orEmpty()) }
    var autoOffTimers by remember { mutableStateOf(existing?.autoOffTimersByDevice.orEmpty()) }
    var typeGroupOrder by remember { mutableStateOf(existing?.typeGroupOrderByContext.orEmpty()) }
    var hiddenDevices by remember { mutableStateOf(existing?.hiddenDeviceIdsByContext.orEmpty()) }
    var hiddenEntities by remember { mutableStateOf(existing?.hiddenEntityIdsByContext.orEmpty()) }
    val connection = remember { container.connectionStore.load() }
    val scope = rememberCoroutineScope()
    // These states live above the conditional sub-screens, so opening entity settings does not
    // dispose the list position that the user must return to.
    val favoriteCardsListState = rememberLazyListState()
    val spaceCardsListState = rememberLazyListState()

    LaunchedEffect(connection) {
        if (connection == null) {
            status = "Сначала настройте подключение к Home Assistant в основном приложении."
        } else runCatching { container.client.getCatalog(connection) }
            .onSuccess { loaded ->
                catalog = loaded
                val ids = loaded.spaces().map { it.id }
                visibleSpaces = if (existing == null) ids else visibleSpaces.filter { it in ids }
                spaceOrder = DashboardOrderPolicy.merge(spaceOrder, ids)
                grouping = ids.associateWith { grouping[it] ?: DashboardGrouping.TYPES }
                status = ""
            }
            .onFailure { status = "Ошибка: ${it.message}" }
    }

    fun closeSubscreen() {
        screen = when (screen) {
            ConfigScreen.TIMER -> ConfigScreen.ENTITIES
            ConfigScreen.ENTITIES -> if (currentSpaceId == null) ConfigScreen.FAVORITES else ConfigScreen.SPACE_CARDS
            else -> ConfigScreen.OVERVIEW
        }
        if (screen == ConfigScreen.OVERVIEW || screen == ConfigScreen.FAVORITES) {
            currentDevice = null
            if (screen == ConfigScreen.OVERVIEW) currentSpaceId = null
        }
        query = ""
    }
    BackHandler(enabled = screen != ConfigScreen.OVERVIEW) { closeSubscreen() }

    val title = when (screen) {
        ConfigScreen.OVERVIEW -> "Настройка HA Dashboard"
        ConfigScreen.FAVORITES -> "Главное и карточки"
        ConfigScreen.ENTITIES -> currentDevice?.title ?: "Параметры устройства"
        ConfigScreen.TIMER -> "Таймер автоотключения"
        ConfigScreen.SPACE_CARDS -> "Порядок карточек"
        ConfigScreen.GROUP_ORDER -> "Порядок групп"
        ConfigScreen.SCENARIOS -> "Сценарии"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (screen != ConfigScreen.OVERVIEW) {
                        TextButton(onClick = ::closeSubscreen) { Text("Назад") }
                    }
                },
            )
        },
    ) { padding ->
        if (status.isNotBlank()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (status.startsWith("Синхронизирую")) CircularProgressIndicator()
                Text(status)
            }
            return@Scaffold
        }
        val loaded = catalog ?: return@Scaffold
        when (screen) {
            ConfigScreen.OVERVIEW -> DashboardOverview(
                modifier = Modifier.padding(padding),
                catalog = loaded,
                visibleSpaces = visibleSpaces,
                spaceOrder = spaceOrder,
                grouping = grouping,
                showUpdated = showUpdated,
                compact = compact,
                onToggleSpace = { id ->
                    visibleSpaces = if (id in visibleSpaces) visibleSpaces - id else visibleSpaces + id
                },
                onSpaceOrderChanged = { spaceOrder = it },
                onCycleGrouping = { id -> grouping = grouping + (id to grouping.getValue(id).next()) },
                onShowUpdated = { showUpdated = it },
                onCompact = { compact = it },
                onFavorites = { screen = ConfigScreen.FAVORITES },
                onScenarios = { screen = ConfigScreen.SCENARIOS },
                onOrderCards = { spaceId ->
                    currentSpaceId = spaceId
                    if (cardOrder[spaceId].isNullOrEmpty()) {
                        val space = loaded.spaces().first { it.id == spaceId }
                        cardOrder = cardOrder + (spaceId to loaded.groupsForSpace(space).dashboardGroups().map { it.key })
                    }
                    screen = ConfigScreen.SPACE_CARDS
                },
                onOrderGroups = { spaceId -> currentSpaceId = spaceId; screen = ConfigScreen.GROUP_ORDER },
                onSave = {
                    scope.launch {
                        onSave(
                            DashboardConfig(
                                appWidgetId, visibleSpaces, grouping, favorites, entityOrder,
                                cardOrder, showUpdated, compact,
                                spaceOrder, scenariosEnabled, automationVisible, scriptVisible, scenarioOrder,
                                autoOffTimers,
                                typeGroupOrder, hiddenDevices, hiddenEntities,
                            ),
                            loaded,
                        )
                    }
                },
            )
            ConfigScreen.FAVORITES -> FavoriteCardsScreen(
                modifier = Modifier.padding(padding),
                groups = loaded.groups.dashboardGroups(),
                favorites = favorites,
                query = query,
                onQuery = { query = it },
                onToggle = { key -> favorites = if (key in favorites) favorites - key else favorites + key },
                onOrderChanged = { favorites = it },
                hiddenDevices = hiddenDevices[MAIN_TAB_ID].orEmpty(),
                listState = favoriteCardsListState,
                onToggleVisibility = { key ->
                    hiddenDevices = DashboardCustomizationPolicy.toggleHidden(hiddenDevices, MAIN_TAB_ID, key)
                },
                onOpenEntities = { group -> currentSpaceId = null; currentDevice = group; screen = ConfigScreen.ENTITIES },
            )
            ConfigScreen.ENTITIES -> currentDevice?.let { group ->
                EntityOrderScreen(
                    modifier = Modifier.padding(padding),
                    group = group,
                    selectedOrder = entityOrder[group.key]
                        ?: defaultMetricOrder(group.entities).take(5).map { it.entityId },
                    onChange = { order -> entityOrder = entityOrder + (group.key to order) },
                    hiddenEntityIds = hiddenEntities[currentSpaceId ?: MAIN_TAB_ID].orEmpty(),
                    onToggleVisibility = { id ->
                        hiddenEntities = DashboardCustomizationPolicy.toggleHidden(
                            hiddenEntities, currentSpaceId ?: MAIN_TAB_ID, id,
                        )
                    },
                    onOpenTimer = { screen = ConfigScreen.TIMER },
                )
            }
            ConfigScreen.TIMER -> currentDevice?.let { group ->
                TimerSettingsScreen(
                    modifier = Modifier.padding(padding),
                    group = group,
                    timers = loaded.groups.flatMap { it.entities }.filter { it.domain == "timer" },
                    config = autoOffTimers[group.key] ?: AutoOffTimerConfig(),
                    onChange = { autoOffTimers = autoOffTimers + (group.key to it) },
                )
            }
            ConfigScreen.SPACE_CARDS -> currentSpaceId?.let { spaceId ->
                val space = loaded.spaces().firstOrNull { it.id == spaceId } ?: return@let
                SpaceCardOrderScreen(
                    modifier = Modifier.padding(padding),
                    spaceName = space.name,
                    groups = loaded.groupsForSpace(space).dashboardGroups(),
                    order = cardOrder[spaceId].orEmpty(),
                    onOrderChanged = { cardOrder = cardOrder + (spaceId to it) },
                    hiddenDeviceIds = hiddenDevices[spaceId].orEmpty(),
                    listState = spaceCardsListState,
                    onToggleVisibility = { key ->
                        hiddenDevices = DashboardCustomizationPolicy.toggleHidden(hiddenDevices, spaceId, key)
                    },
                    onOpenEntities = { group -> currentDevice = group; screen = ConfigScreen.ENTITIES },
                )
            }
            ConfigScreen.GROUP_ORDER -> currentSpaceId?.let { spaceId ->
                val space = loaded.spaces().firstOrNull { it.id == spaceId } ?: return@let
                TypeGroupOrderScreen(
                    modifier = Modifier.padding(padding),
                    spaceName = space.name,
                    groups = loaded.groupsForSpace(space).dashboardGroups(),
                    savedOrder = typeGroupOrder[spaceId].orEmpty(),
                    onOrderChanged = { typeGroupOrder = typeGroupOrder + (spaceId to it) },
                )
            }
            ConfigScreen.SCENARIOS -> ScenarioSettingsScreen(
                modifier = Modifier.padding(padding),
                catalog = loaded,
                enabled = scenariosEnabled,
                automationVisible = automationVisible,
                scriptVisible = scriptVisible,
                order = scenarioOrder,
                onEnabled = { scenariosEnabled = it },
                onAutomationVisible = { automationVisible = it },
                onScriptVisible = { scriptVisible = it },
                onOrderChanged = { key, value -> scenarioOrder = scenarioOrder + (key to value) },
            )
        }
    }
}

@Composable
private fun DashboardOverview(
    modifier: Modifier,
    catalog: HaCatalog,
    visibleSpaces: List<String>,
    spaceOrder: List<String>,
    grouping: Map<String, DashboardGrouping>,
    showUpdated: Boolean,
    compact: Boolean,
    onToggleSpace: (String) -> Unit,
    onSpaceOrderChanged: (List<String>) -> Unit,
    onCycleGrouping: (String) -> Unit,
    onShowUpdated: (Boolean) -> Unit,
    onCompact: (Boolean) -> Unit,
    onFavorites: () -> Unit,
    onScenarios: () -> Unit,
    onOrderCards: (String) -> Unit,
    onOrderGroups: (String) -> Unit,
    onSave: () -> Unit,
) {
    val spaces = catalog.spaces()
    val byId = spaces.associateBy { it.id }
    val orderedIds = DashboardOrderPolicy.merge(spaceOrder, spaces.map { it.id })
    val orderedSpaces = orderedIds.mapNotNull(byId::get)
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Пространства синхронизируются из Home Assistant. Для изменения порядка удерживайте ⠿ и перетащите карточку.")
        ReorderableList(
            items = orderedSpaces,
            stableId = { it.id },
            canDrag = { it.id in visibleSpaces },
            modifier = Modifier.weight(1f),
            onMove = { from, to ->
                val fullOrder = orderedSpaces.map { it.id }
                val visibleOrder = fullOrder.filter { it in visibleSpaces }
                val fromId = fullOrder.getOrNull(from)
                val toId = fullOrder.getOrNull(to)
                val fromVisible = if (fromId == null) -1 else visibleOrder.indexOf(fromId)
                val toVisible = if (toId == null) -1 else visibleOrder.indexOf(toId)
                onSpaceOrderChanged(
                    DashboardOrderPolicy.reorderVisibleSubset(
                        fullOrder,
                        visibleSpaces,
                        fromVisible,
                        toVisible,
                    ),
                )
            },
        ) { space, dragging, itemModifier, dragHandle ->
                val enabled = space.id in visibleSpaces
                Card(
                    itemModifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 10.dp else 1.dp),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = enabled, onCheckedChange = { onToggleSpace(space.id) })
                            Icon(HaSemanticIcon.SPACE.imageVector(), contentDescription = "Пространство")
                            Text(space.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (enabled) dragHandle()
                        }
                        if (enabled) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onCycleGrouping(space.id) }) {
                                    Text("Группировка: ${grouping[space.id].label()}")
                                }
                                IconButton(onClick = { onOrderCards(space.id) }) {
                                    Icon(Icons.Default.SwapVert, contentDescription = "Порядок карточек")
                                }
                            }
                            if (grouping[space.id] == DashboardGrouping.TYPES) {
                                TextButton(onClick = { onOrderGroups(space.id) }) { Text("Порядок групп ›") }
                            }
                        }
                    }
                }
        }
        Button(onClick = onFavorites, modifier = Modifier.fillMaxWidth()) { Text("Карточки, параметры и таймеры") }
        Button(onClick = onScenarios, modifier = Modifier.fillMaxWidth()) { Text("Настроить Сценарии") }
        SettingSwitch("Показывать время обновления", showUpdated, onShowUpdated)
        SettingSwitch("Компактная плотность карточек", compact, onCompact)
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text("Сохранить Dashboard")
        }
    }
}

@Composable
private fun SpaceCardOrderScreen(
    modifier: Modifier,
    spaceName: String,
    groups: List<HaDeviceGroup>,
    order: List<String>,
    onOrderChanged: (List<String>) -> Unit,
    hiddenDeviceIds: List<String>,
    listState: LazyListState,
    onToggleVisibility: (String) -> Unit,
    onOpenEntities: (HaDeviceGroup) -> Unit,
) {
    val byKey = groups.associateBy { it.key }
    val ordered = order.mapNotNull(byKey::get) + groups.filter { it.key !in order }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$spaceName: настройте отображаемые устройства и их порядок.")
        ReorderableList(
            items = ordered,
            stableId = { it.key },
            modifier = Modifier.weight(1f),
            listState = listState,
            onMove = { from, to -> onOrderChanged(moveStable(ordered, from, to).map { it.key }) },
        ) { group, dragging, itemModifier, dragHandle ->
                Card(
                    itemModifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 10.dp else 1.dp),
                ) {
                    Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(group.key !in hiddenDeviceIds, { onToggleVisibility(group.key) })
                        val type = HaEntityIconPolicy.primary(group.entities)
                        Icon(type.imageVector(), contentDescription = HaEntityIconPolicy.label(type))
                        Column(Modifier.weight(1f).clickable { onOpenEntities(group) }.padding(start = 10.dp)) {
                            Text(group.title, style = MaterialTheme.typography.titleSmall)
                            Text("${cardTypeLabel(group)} · параметры ›", style = MaterialTheme.typography.bodySmall)
                        }
                        dragHandle()
                    }
                }
        }
    }
}

@Composable
private fun FavoriteCardsScreen(
    modifier: Modifier,
    groups: List<HaDeviceGroup>,
    favorites: List<String>,
    query: String,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
    onOrderChanged: (List<String>) -> Unit,
    hiddenDevices: List<String>,
    listState: LazyListState,
    onToggleVisibility: (String) -> Unit,
    onOpenEntities: (HaDeviceGroup) -> Unit,
) {
    val filtered = groups.filter { group ->
        query.isBlank() || group.title.contains(query, true) || group.entities.any {
            it.friendlyName.contains(query, true) || it.entityId.contains(query, true)
        }
    }
    val byKey = filtered.associateBy { it.key }
    val ordered = favorites.mapNotNull(byKey::get) + filtered.filter { it.key !in favorites }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Отметьте карточки для ★ Главного. Нажмите название устройства, чтобы выбрать и упорядочить параметры.")
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), label = { Text("Поиск устройств и сущностей") }, singleLine = true)
        ReorderableList(
            items = ordered,
            stableId = { it.key },
            modifier = Modifier.weight(1f),
            listState = listState,
            canDrag = { query.isBlank() && it.key in favorites },
            onMove = { from, to ->
                val moved = moveStable(ordered, from, to)
                onOrderChanged(moved.map { it.key }.filter { it in favorites })
            },
        ) { group, dragging, itemModifier, dragHandle ->
                val selected = group.key in favorites
                Card(
                    itemModifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 10.dp else 1.dp),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected, { onToggle(group.key) })
                        val type = HaEntityIconPolicy.primary(group.entities)
                        Icon(type.imageVector(), contentDescription = HaEntityIconPolicy.label(type))
                        Column(
                            Modifier.weight(1f).clickable { onOpenEntities(group) }.padding(start = 10.dp, top = 7.dp, bottom = 7.dp),
                        ) {
                            Text(group.title, style = MaterialTheme.typography.titleSmall)
                            Text("${cardTypeLabel(group)} · настроить параметры ›", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onToggleVisibility(group.key) }) {
                            Text(if (group.key in hiddenDevices) "Показать" else "Скрыть")
                        }
                        if (selected) dragHandle()
                    }
                }
        }
    }
}

@Composable
private fun EntityOrderScreen(
    modifier: Modifier,
    group: HaDeviceGroup,
    selectedOrder: List<String>,
    onChange: (List<String>) -> Unit,
    hiddenEntityIds: List<String>,
    onToggleVisibility: (String) -> Unit,
    onOpenTimer: () -> Unit,
) {
    val byId = group.entities.associateBy { it.entityId }
    val sorted = selectedOrder.mapNotNull(byId::get) +
        defaultMetricOrder(group.entities.filter { it.entityId !in selectedOrder })
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Отметьте параметры, которые нужно показывать. Для изменения порядка удерживайте выбранный параметр; батарея по умолчанию последняя.")
        ReorderableList(
            items = sorted,
            stableId = { it.entityId },
            modifier = Modifier.weight(1f),
            canDrag = { it.entityId in selectedOrder },
            onMove = { from, to ->
                val moved = moveStable(sorted, from, to)
                onChange(moved.map { it.entityId }.filter { it in selectedOrder })
            },
        ) { entity, dragging, itemModifier, dragHandle ->
                val selected = entity.entityId in selectedOrder && entity.entityId !in hiddenEntityIds
                Card(
                    itemModifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 10.dp else 1.dp),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            selected,
                            {
                                if (selected) {
                                    onToggleVisibility(entity.entityId)
                                } else {
                                    if (entity.entityId !in selectedOrder) onChange(selectedOrder + entity.entityId)
                                    if (entity.entityId in hiddenEntityIds) onToggleVisibility(entity.entityId)
                                }
                            },
                        )
                        val type = HaEntityIconPolicy.resolve(entity.domain, entity.deviceClass)
                        Icon(type.imageVector(), contentDescription = HaEntityIconPolicy.label(type))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(entity.friendlyName, style = MaterialTheme.typography.titleSmall)
                            Text("${HaEntityIconPolicy.label(type)} · ${entity.displayState}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (selected) dragHandle()
                    }
                }
        }
        if (AutoOffTimerPolicy.controls(group.entities).isNotEmpty()) Button(onClick = onOpenTimer, modifier = Modifier.fillMaxWidth()) {
            Text("Таймер автоотключения")
        }
    }
}

@Composable
private fun TypeGroupOrderScreen(
    modifier: Modifier,
    spaceName: String,
    groups: List<HaDeviceGroup>,
    savedOrder: List<String>,
    onOrderChanged: (List<String>) -> Unit,
) {
    val categories = groups.map(::deviceCategory).distinct()
    val ordered = DashboardCustomizationPolicy.orderedCategories(savedOrder, categories)
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$spaceName: удерживайте ⠿ и перетащите группу. Пустые группы автоматически не показываются.")
        ReorderableList(
            items = ordered,
            stableId = DashboardCustomizationPolicy::groupId,
            modifier = Modifier.weight(1f),
            onMove = { from, to ->
                onOrderChanged(DashboardCustomizationPolicy.reorderCategories(savedOrder, categories, from, to))
            },
        ) { category, dragging, itemModifier, dragHandle ->
            Card(
                itemModifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 10.dp else 1.dp),
            ) {
                Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(category.icon)
                    Text(category.title, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleSmall)
                    dragHandle()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerSettingsScreen(
    modifier: Modifier,
    group: HaDeviceGroup,
    timers: List<HaEntity>,
    config: AutoOffTimerConfig,
    onChange: (AutoOffTimerConfig) -> Unit,
) {
    val eligibleControls = AutoOffTimerPolicy.controls(group.entities)
    val selectedControl = config.controlEntityId?.let { id -> eligibleControls.firstOrNull { it.entityId == id } }
        ?: eligibleControls.singleOrNull()
    val selectedTimer = timers.firstOrNull { it.entityId == config.timerEntityId }
    var timerMenuExpanded by remember { mutableStateOf(false) }
    var controlMenuExpanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (eligibleControls.size > 1) {
            Text("Управляемая сущность", style = MaterialTheme.typography.titleSmall)
            ExposedDropdownMenuBox(
                expanded = controlMenuExpanded,
                onExpandedChange = { controlMenuExpanded = !controlMenuExpanded },
            ) {
                OutlinedTextField(
                    value = selectedControl?.let { "${it.friendlyName} · ${it.entityId}" } ?: "Выбрать сущность",
                    onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(controlMenuExpanded) },
                )
                ExposedDropdownMenu(expanded = controlMenuExpanded, onDismissRequest = { controlMenuExpanded = false }) {
                    eligibleControls.forEach { control -> DropdownMenuItem(
                        text = { Text("${control.friendlyName}\n${control.entityId}") },
                        onClick = { onChange(config.copy(controlEntityId = control.entityId)); controlMenuExpanded = false },
                    ) }
                }
            }
        }
        SettingSwitch("Использовать таймер", config.enabled) { enabled ->
            onChange(config.copy(enabled = enabled && selectedControl != null &&
                (config.timerEntityId != null || timers.isNotEmpty()),
                timerEntityId = config.timerEntityId ?: timers.firstOrNull()?.entityId,
                controlEntityId = config.controlEntityId ?: eligibleControls.singleOrNull()?.entityId))
        }
        Text("Таймер Home Assistant", style = MaterialTheme.typography.titleSmall)
        if (timers.isEmpty()) Text("В Home Assistant не найдено ни одного timer.*")
        ExposedDropdownMenuBox(
            expanded = timerMenuExpanded,
            onExpandedChange = { if (timers.isNotEmpty()) timerMenuExpanded = !timerMenuExpanded },
        ) {
            OutlinedTextField(
                value = selectedTimer?.let { "${it.friendlyName} · ${it.entityId}" } ?: "Выбрать timer.*",
                onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(timerMenuExpanded) },
            )
            ExposedDropdownMenu(expanded = timerMenuExpanded, onDismissRequest = { timerMenuExpanded = false }) {
                timers.forEach { timer ->
                    DropdownMenuItem(
                        text = { Text("${timer.friendlyName}\n${timer.entityId}") },
                        onClick = {
                            onChange(config.copy(timerEntityId = timer.entityId))
                            timerMenuExpanded = false
                        },
                    )
                }
            }
        }
        Text("Варианты времени", style = MaterialTheme.typography.titleSmall)
        ReorderableList(
            items = config.durations,
            stableId = { it.id },
            modifier = Modifier.weight(1f),
            onMove = { from, to -> onChange(config.copy(durations = moveStable(config.durations, from, to))) },
        ) { preset, dragging, itemModifier, dragHandle ->
            var text by remember(preset.id, preset.minutes) { mutableStateOf(preset.minutes.toString()) }
            Card(itemModifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(if (dragging) 10.dp else 1.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { value ->
                            text = value.filter(Char::isDigit)
                            val minutes = text.toIntOrNull()
                            if (minutes != null && AutoOffTimerPolicy.validMinutes(minutes) &&
                                config.durations.none { it.id != preset.id && it.minutes == minutes }
                            ) onChange(config.copy(durations = config.durations.map {
                                if (it.id == preset.id) it.copy(minutes = minutes) else it
                            }))
                        },
                        modifier = Modifier.weight(1f),
                        label = { Text("Минуты") }, singleLine = true,
                    )
                    if (config.durations.size > 1) TextButton(onClick = {
                        onChange(config.copy(durations = config.durations.filterNot { it.id == preset.id }))
                    }) { Text("Удалить") }
                    dragHandle()
                }
            }
        }
        Button(onClick = {
            val used = config.durations.map { it.minutes }.toSet()
            val value = (1..1440).firstOrNull { it !in used } ?: return@Button
            onChange(config.copy(durations = config.durations + TimerDurationPreset.create(value)))
        }, modifier = Modifier.fillMaxWidth(), enabled = config.durations.size < 48) { Text("+ Добавить время") }
        Text("Для автоматического выключения после окончания timer должна быть настроена автоматизация Home Assistant.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ScenarioSettingsScreen(
    modifier: Modifier,
    catalog: HaCatalog,
    enabled: Boolean,
    automationVisible: Boolean,
    scriptVisible: Boolean,
    order: Map<String, List<String>>,
    onEnabled: (Boolean) -> Unit,
    onAutomationVisible: (Boolean) -> Unit,
    onScriptVisible: (Boolean) -> Unit,
    onOrderChanged: (String, List<String>) -> Unit,
) {
    val spaces = catalog.spaces()
    val spaceById = spaces.associateBy { it.id }
    val actions = catalog.groups.flatMap { group ->
        group.entities.filter { it.domain in setOf("automation", "script") }.map { entity ->
            val spaceId = ScenarioPolicy.resolveSpaceId(entity.areaId, group.device?.areaId, catalog)
            DashboardScenarioAction(entity.entityId, entity.friendlyName, entity.domain, entity.state, spaceId)
        }
    }.distinctBy { it.entityId }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingSwitch("Показывать вкладку «Сценарии»", enabled, onEnabled)
        SettingSwitch("Показывать автоматизации", automationVisible, onAutomationVisible)
        SettingSwitch("Показывать скрипты", scriptVisible, onScriptVisible)
        val sections = (spaces.map { it.id } + UNASSIGNED_SPACE_ID).distinct().flatMap { spaceId ->
            listOf("automation", "script").mapNotNull { domain ->
                val values = actions.filter { it.spaceId == spaceId && it.domain == domain }
                values.takeIf { it.isNotEmpty() }?.let { Triple(spaceId, domain, it) }
            }
        }
        ReorderableList(
            items = sections.flatMap { (spaceId, domain, values) ->
                val key = "$spaceId:$domain"
                val byId = values.associateBy { it.entityId }
                DashboardOrderPolicy.merge(order[key].orEmpty(), values.map { it.entityId }).mapNotNull(byId::get)
            },
            stableId = { it.entityId },
            modifier = Modifier.weight(1f),
            onMove = { from, to ->
                val flat = sections.flatMap { (spaceId, domain, values) ->
                    val key = "$spaceId:$domain"
                    val byId = values.associateBy { it.entityId }
                    DashboardOrderPolicy.merge(order[key].orEmpty(), values.map { it.entityId }).mapNotNull(byId::get)
                }
                val source = flat.getOrNull(from) ?: return@ReorderableList
                val target = flat.getOrNull(to) ?: return@ReorderableList
                if (source.spaceId != target.spaceId || source.domain != target.domain) return@ReorderableList
                val key = "${source.spaceId}:${source.domain}"
                val subsection = flat.filter { it.spaceId == source.spaceId && it.domain == source.domain }
                val localFrom = subsection.indexOfFirst { it.entityId == source.entityId }
                val localTo = subsection.indexOfFirst { it.entityId == target.entityId }
                onOrderChanged(key, moveStable(subsection, localFrom, localTo).map { it.entityId })
            },
        ) { action, dragging, itemModifier, dragHandle ->
            val type = HaEntityIconPolicy.resolve(action.domain, null)
            Card(itemModifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(if (dragging) 10.dp else 1.dp)) {
                Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(type.imageVector(), contentDescription = HaEntityIconPolicy.label(type))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(action.title, style = MaterialTheme.typography.titleSmall)
                        Text("${spaceById[action.spaceId]?.name ?: "Без пространства"} · ${HaEntityIconPolicy.label(type)}", style = MaterialTheme.typography.bodySmall)
                    }
                    dragHandle()
                }
            }
        }
    }
}

private fun cardTypeLabel(group: HaDeviceGroup): String {
    val labels = group.entities.sortedBy(::semanticMetricRank).map {
        HaEntityIconPolicy.label(HaEntityIconPolicy.resolve(it.domain, it.deviceClass))
    }.distinct().take(2)
    return labels.joinToString(" · ").ifBlank { "Устройство" }
}

private fun List<HaDeviceGroup>.dashboardGroups(): List<HaDeviceGroup> = mapNotNull { group ->
    group.copy(entities = group.entities.filterNot { it.domain in setOf("automation", "script") })
        .takeIf { it.entities.isNotEmpty() }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

private fun DashboardGrouping.next() = when (this) {
    DashboardGrouping.ROOMS -> DashboardGrouping.TYPES
    DashboardGrouping.TYPES -> DashboardGrouping.NONE
    DashboardGrouping.NONE -> DashboardGrouping.ROOMS
}

private fun DashboardGrouping?.label() = when (this) {
    DashboardGrouping.ROOMS -> "по помещениям"
    DashboardGrouping.TYPES -> "по типам устройств"
    DashboardGrouping.NONE, null -> "без группировки"
}
