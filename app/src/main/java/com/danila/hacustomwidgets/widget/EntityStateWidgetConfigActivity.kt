package com.danila.hacustomwidgets.widget

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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.glance.appwidget.updateAll
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.tr
import com.danila.hacustomwidgets.data.AppContainer
import com.danila.hacustomwidgets.data.WidgetConfig
import com.danila.hacustomwidgets.data.model.HaCatalog
import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.dashboard.defaultMetricOrder
import com.danila.hacustomwidgets.ui.HaCustomWidgetsTheme
import kotlinx.coroutines.launch

class EntityStateWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val container = (application as HaWidgetApplication).container
        val existing = container.widgets.get(appWidgetId)
        setContent {
            HaCustomWidgetsTheme {
                WidgetConfigurator(
                    container = container,
                    existing = existing,
                    onSave = { group, entities, showLastUpdated ->
                        container.widgets.saveConfiguration(
                            appWidgetId,
                            group,
                            entities,
                            showLastUpdated,
                        )
                        EntityStateWidget().updateAll(this@EntityStateWidgetConfigActivity)
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WidgetConfigurator(
    container: AppContainer,
    existing: WidgetConfig?,
    onSave: suspend (HaDeviceGroup, List<HaEntity>, Boolean) -> Unit,
) {
    var catalog by remember { mutableStateOf<HaCatalog?>(null) }
    var selectedGroup by remember { mutableStateOf<HaDeviceGroup?>(null) }
    var selectedIds by remember { mutableStateOf(existing?.metrics?.map { it.entityId }.orEmpty()) }
    var showLastUpdated by remember { mutableStateOf(existing?.showLastUpdated ?: true) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(tr("Loading devices and entities…", "Загружаю устройства и сущности…")) }
    val connection = remember { container.connectionStore.load() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(connection) {
        if (connection == null) {
            status = tr("Open the app and configure the Home Assistant connection first.", "Сначала откройте приложение и настройте подключение к Home Assistant.")
        } else {
            runCatching { container.client.getCatalog(connection) }
                .onSuccess { loaded ->
                    catalog = loaded
                    selectedGroup = loaded.groups.firstOrNull { group ->
                        (existing?.deviceId != null && group.device?.id == existing.deviceId) ||
                            (selectedIds.isNotEmpty() && selectedIds.all { id -> group.entities.any { it.entityId == id } })
                    }
                    status = ""
                }
                .onFailure { status = tr("Error: ${it.message}", "Ошибка: ${it.message}") }
        }
    }

    BackHandler(enabled = selectedGroup != null) {
        selectedGroup = null
        query = ""
    }

    val title = selectedGroup?.title ?: tr("Choose a device", "Выберите устройство")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (selectedGroup != null) {
                        TextButton(onClick = { selectedGroup = null; query = "" }) { Text(tr("Back", "Назад")) }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(if (selectedGroup == null) tr("Search devices and entities", "Поиск устройств и сущностей") else tr("Search entities", "Поиск сущностей"))
                },
                singleLine = true,
            )
            if (status.isNotBlank()) {
                LoadingStatus(status, connection != null && status == tr("Loading devices and entities…", "Загружаю устройства и сущности…"))
            } else if (selectedGroup == null) {
                DeviceList(
                    groups = catalog.orEmptyGroups().filterByQuery(query),
                    query = query,
                    onOpen = { group ->
                        selectedGroup = group
                        if (existing?.deviceId != group.device?.id &&
                            selectedIds.none { id -> group.entities.any { it.entityId == id } }
                        ) {
                            selectedIds = emptyList()
                        }
                        query = ""
                    },
                )
            } else {
                val group = selectedGroup ?: return@Column
                EntitySelection(
                    group = group,
                    query = query,
                    selectedIds = selectedIds,
                    showLastUpdated = showLastUpdated,
                    onToggle = { entityId ->
                        selectedIds = if (entityId in selectedIds) selectedIds - entityId else selectedIds + entityId
                    },
                    onMove = { entityId, delta -> selectedIds = selectedIds.move(entityId, delta) },
                    onShowUpdatedChanged = { showLastUpdated = it },
                    onSave = {
                        val selected = selectedIds.mapNotNull { id -> group.entities.firstOrNull { it.entityId == id } }
                        scope.launch { onSave(group, selected, showLastUpdated) }
                    },
                )
            }
        }
    }
}

@Composable
private fun LoadingStatus(status: String, loading: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) CircularProgressIndicator()
        Text(status)
    }
}

@Composable
private fun DeviceList(groups: List<HaDeviceGroup>, query: String, onOpen: (HaDeviceGroup) -> Unit) {
    if (groups.isEmpty()) {
        Text(tr("Nothing found", "Ничего не найдено"), modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(groups, key = { it.key }) { group ->
            val matches = if (query.isBlank()) group.entities.size else group.matchingEntityCount(query)
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(group) }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium)
                    group.device?.let { device ->
                        listOfNotNull(device.manufacturer, device.model).joinToString(" · ")
                            .takeIf { it.isNotBlank() }
                            ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text(
                        if (query.isBlank()) tr("Entities: ${group.entities.size}", "Сущностей: ${group.entities.size}") else tr("Matches: $matches", "Совпадений: $matches"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.EntitySelection(
    group: HaDeviceGroup,
    query: String,
    selectedIds: List<String>,
    showLastUpdated: Boolean,
    onToggle: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onShowUpdatedChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    val filtered = remember(group, query, selectedIds) {
        val available = if (query.isBlank()) group.entities else group.entities.filter { it.matches(query) }
        val byId = available.associateBy { it.entityId }
        selectedIds.mapNotNull(byId::get) +
            defaultMetricOrder(available.filter { it.entityId !in selectedIds })
    }
    Text(tr("Select several metrics from one device. Their order follows the list.", "Отметьте несколько показателей одного устройства. Порядок соответствует списку."))
    LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(filtered, key = { it.entityId }) { entity ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle(entity.entityId) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = entity.entityId in selectedIds,
                    onCheckedChange = { onToggle(entity.entityId) },
                )
                Column(Modifier.weight(1f)) {
                    Text(entity.friendlyName, style = MaterialTheme.typography.titleSmall)
                    Text("${entity.displayState} · ${entity.entityId}", style = MaterialTheme.typography.bodySmall)
                }
                if (entity.entityId in selectedIds) {
                    TextButton(onClick = { onMove(entity.entityId, -1) }) { Text("↑") }
                    TextButton(onClick = { onMove(entity.entityId, 1) }) { Text("↓") }
                }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr("Show update time", "Показывать время обновления"))
            Text(tr("Tapping the widget always refreshes data", "Нажатие на виджет обновляет данные всегда"), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = showLastUpdated, onCheckedChange = onShowUpdatedChanged)
    }
    Button(
        onClick = onSave,
        enabled = selectedIds.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Text(tr("Save · selected ${selectedIds.size}", "Сохранить · выбрано ${selectedIds.size}"))
    }
}

private fun HaCatalog?.orEmptyGroups(): List<HaDeviceGroup> = this?.groups.orEmpty()

private fun List<HaDeviceGroup>.filterByQuery(query: String): List<HaDeviceGroup> =
    if (query.isBlank()) this
    else filter { group ->
        group.title.contains(query, ignoreCase = true) ||
            group.device?.manufacturer?.contains(query, ignoreCase = true) == true ||
            group.device?.model?.contains(query, ignoreCase = true) == true ||
            group.entities.any { it.matches(query) }
    }

private fun HaDeviceGroup.matchingEntityCount(query: String): Int = entities.count { it.matches(query) }

private fun HaEntity.matches(query: String): Boolean =
    friendlyName.contains(query, ignoreCase = true) ||
        entityId.contains(query, ignoreCase = true) ||
        displayState.contains(query, ignoreCase = true)

private fun <T> List<T>.move(item: T, delta: Int): List<T> {
    val from = indexOf(item)
    if (from < 0) return this
    val to = (from + delta).coerceIn(0, lastIndex)
    if (from == to) return this
    return toMutableList().apply { removeAt(from); add(to, item) }
}
