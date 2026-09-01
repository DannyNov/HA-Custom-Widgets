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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.danila.hacustomwidgets.ui.HaCustomWidgetsTheme
import kotlinx.coroutines.launch

class DashboardWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
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
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                        )
                        finish()
                    },
                )
            }
        }
    }
}

private enum class ConfigScreen { OVERVIEW, FAVORITES, ENTITIES, SPACE_CARDS }

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
    val connection = remember { container.connectionStore.load() }
    val scope = rememberCoroutineScope()

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
        currentDevice = null
        currentSpaceId = null
        screen = if (screen == ConfigScreen.ENTITIES) ConfigScreen.FAVORITES else ConfigScreen.OVERVIEW
        query = ""
    }
    BackHandler(enabled = screen != ConfigScreen.OVERVIEW) { closeSubscreen() }

    val title = when (screen) {
        ConfigScreen.OVERVIEW -> "Настройка HA Dashboard"
        ConfigScreen.FAVORITES -> "Главное и карточки"
        ConfigScreen.ENTITIES -> currentDevice?.title ?: "Параметры устройства"
        ConfigScreen.SPACE_CARDS -> "Порядок карточек"
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
                onOrderCards = { spaceId ->
                    currentSpaceId = spaceId
                    if (cardOrder[spaceId].isNullOrEmpty()) {
                        val space = loaded.spaces().first { it.id == spaceId }
                        cardOrder = cardOrder + (spaceId to loaded.groupsForSpace(space).map { it.key })
                    }
                    screen = ConfigScreen.SPACE_CARDS
                },
                onSave = {
                    scope.launch {
                        onSave(
                            DashboardConfig(
                                appWidgetId, visibleSpaces, grouping, favorites, entityOrder,
                                cardOrder, showUpdated, compact,
                                spaceOrder,
                            ),
                            loaded,
                        )
                    }
                },
            )
            ConfigScreen.FAVORITES -> FavoriteCardsScreen(
                modifier = Modifier.padding(padding),
                groups = loaded.groups,
                favorites = favorites,
                query = query,
                onQuery = { query = it },
                onToggle = { key -> favorites = if (key in favorites) favorites - key else favorites + key },
                onOrderChanged = { favorites = it },
                onOpenEntities = { group -> currentDevice = group; screen = ConfigScreen.ENTITIES },
            )
            ConfigScreen.ENTITIES -> currentDevice?.let { group ->
                EntityOrderScreen(
                    modifier = Modifier.padding(padding),
                    group = group,
                    selectedOrder = entityOrder[group.key]
                        ?: defaultMetricOrder(group.entities).take(5).map { it.entityId },
                    onChange = { order -> entityOrder = entityOrder + (group.key to order) },
                )
            }
            ConfigScreen.SPACE_CARDS -> currentSpaceId?.let { spaceId ->
                val space = loaded.spaces().firstOrNull { it.id == spaceId } ?: return@let
                SpaceCardOrderScreen(
                    modifier = Modifier.padding(padding),
                    spaceName = space.name,
                    groups = loaded.groupsForSpace(space),
                    order = cardOrder[spaceId].orEmpty(),
                    onOrderChanged = { cardOrder = cardOrder + (spaceId to it) },
                )
            }
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
    onOrderCards: (String) -> Unit,
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
                            Text(space.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (enabled) dragHandle()
                        }
                        if (enabled) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onCycleGrouping(space.id) }) {
                                    Text("Группировка: ${grouping[space.id].label()}")
                                }
                                TextButton(onClick = { onOrderCards(space.id) }) { Text("Порядок ›") }
                            }
                        }
                    }
                }
        }
        Button(onClick = onFavorites, modifier = Modifier.fillMaxWidth()) { Text("Настроить ★ Главное и параметры") }
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
) {
    val byKey = groups.associateBy { it.key }
    val ordered = order.mapNotNull(byKey::get) + groups.filter { it.key !in order }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$spaceName: порядок используется внутри секций и в режиме без группировки.")
        ReorderableList(
            items = ordered,
            stableId = { it.key },
            modifier = Modifier.weight(1f),
            onMove = { from, to -> onOrderChanged(moveStable(ordered, from, to).map { it.key }) },
        ) { group, dragging, itemModifier, dragHandle ->
                Card(
                    itemModifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 10.dp else 1.dp),
                ) {
                    Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
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
                        Column(
                            Modifier.weight(1f).clickable { onOpenEntities(group) }.padding(vertical = 7.dp),
                        ) {
                            Text(group.title, style = MaterialTheme.typography.titleSmall)
                            Text("${group.entities.size} сущн. · настроить параметры ›", style = MaterialTheme.typography.bodySmall)
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
) {
    val byId = group.entities.associateBy { it.entityId }
    val sorted = selectedOrder.mapNotNull(byId::get) +
        defaultMetricOrder(group.entities.filter { it.entityId !in selectedOrder })
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Выберите параметры. Для изменения порядка удерживайте выбранный параметр; батарея по умолчанию последняя.")
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
                val selected = entity.entityId in selectedOrder
                Card(
                    itemModifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 10.dp else 1.dp),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            selected,
                            {
                                onChange(
                                    if (selected) selectedOrder - entity.entityId
                                    else selectedOrder + entity.entityId,
                                )
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(entity.friendlyName, style = MaterialTheme.typography.titleSmall)
                            Text("${entity.displayState} · ${entity.entityId}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (selected) dragHandle()
                    }
                }
        }
    }
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
