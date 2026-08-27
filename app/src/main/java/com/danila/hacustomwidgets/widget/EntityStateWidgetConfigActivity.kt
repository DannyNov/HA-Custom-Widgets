package com.danila.hacustomwidgets.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.data.AppContainer
import com.danila.hacustomwidgets.data.model.HaEntity

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
        setContent {
            MaterialTheme {
                EntityPicker(
                    container = container,
                    onSelected = { entity ->
                        container.widgets.save(appWidgetId, entity)
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
private fun EntityPicker(container: AppContainer, onSelected: suspend (HaEntity) -> Unit) {
    var entities by remember { mutableStateOf<List<HaEntity>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Загружаю сущности…") }
    val connection = remember { container.connectionStore.load() }

    LaunchedEffect(connection) {
        if (connection == null) {
            status = "Сначала откройте приложение и настройте подключение к Home Assistant."
        } else {
            runCatching { container.client.getEntities(connection) }
                .onSuccess { entities = it; status = "" }
                .onFailure { status = "Ошибка: ${it.message}" }
        }
    }

    val filtered = remember(entities, query) {
        if (query.isBlank()) entities
        else entities.filter {
            it.entityId.contains(query, ignoreCase = true) ||
                it.friendlyName.contains(query, ignoreCase = true)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Выберите сущность") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск по имени или entity_id") },
                singleLine = true,
            )
            if (status.isNotBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (connection != null && status.startsWith("Загружаю")) CircularProgressIndicator()
                    Text(status)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.entityId }) { entity ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                // Selection is handled inside a LaunchedEffect below.
                                query = SELECT_PREFIX + entity.entityId
                            },
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(entity.friendlyName, style = MaterialTheme.typography.titleMedium)
                                Text(entity.displayState, style = MaterialTheme.typography.bodyLarge)
                                Text(entity.entityId, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(query) {
        if (query.startsWith(SELECT_PREFIX)) {
            entities.firstOrNull { it.entityId == query.removePrefix(SELECT_PREFIX) }?.let { onSelected(it) }
        }
    }
}

private const val SELECT_PREFIX = "\u0000selected:"
