package com.danila.hacustomwidgets

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.danila.hacustomwidgets.data.security.HomeAssistantConnection
import com.danila.hacustomwidgets.ui.HaCustomWidgetsTheme
import com.danila.hacustomwidgets.realtime.RealtimeNotificationAccess
import com.danila.hacustomwidgets.dashboard.DashboardWidgetConfigActivity
import com.danila.hacustomwidgets.dashboard.DashboardSettingsDestination
import com.danila.hacustomwidgets.dashboard.DashboardSettingsLaunchPolicy
import android.appwidget.AppWidgetManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val appContainer get() = (application as HaWidgetApplication).container
    private val realtimeGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = appContainer
        realtimeGranted.value = RealtimeNotificationAccess.isGranted(this)
        setContent {
            HaCustomWidgetsTheme {
                ConnectionScreen(
                    initialUrl = container.connectionStore.load()?.baseUrl.orEmpty(),
                    hasStoredToken = container.connectionStore.load() != null,
                    realtimeGranted = realtimeGranted.value,
                    dashboards = container.dashboards.all().map { it.appWidgetId to dashboardLabel(it.appWidgetId, container.dashboards.get(it.appWidgetId)) },
                    onOpenDashboardSettings = { widgetId ->
                        startActivity(
                            Intent(this, DashboardWidgetConfigActivity::class.java)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                                .putExtra(DashboardWidgetConfigActivity.EXTRA_IN_APP_SETTINGS, true),
                        )
                    },
                    onEnableRealtime = {
                        runCatching { startActivity(RealtimeNotificationAccess.settingsIntent(this)) }
                            .onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    },
                    onConnect = { url, token ->
                        val effectiveToken = token.ifBlank {
                            container.connectionStore.load()?.token.orEmpty()
                        }
                        val connection = HomeAssistantConnection(url.trim().trimEnd('/'), effectiveToken)
                        container.client.testConnection(connection)
                        container.connectionStore.save(connection.baseUrl, connection.token)
                        container.dashboardEvents.ensureStarted("CONNECTION_SAVED")
                        container.client.getEntities(connection).size
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = RealtimeNotificationAccess.isGranted(this)
        realtimeGranted.value = granted
        Log.i(
            "HAWidgetRealtime",
            "NLS_PERMISSION_STATE processStartId=${com.danila.hacustomwidgets.dashboard.DashboardDiagnostics.processStartId} " +
                "granted=$granted source=ACTIVITY_RESUME monotonicMs=${SystemClock.elapsedRealtime()}",
        )
        appContainer.dashboardEvents.ensureStarted("ACTIVITY_RESUME")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionScreen(
    initialUrl: String,
    hasStoredToken: Boolean,
    realtimeGranted: Boolean,
    dashboards: List<Pair<Int, String>>,
    onOpenDashboardSettings: (Int) -> Unit,
    onEnableRealtime: () -> Unit,
    onConnect: suspend (String, String) -> Int,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(if (hasStoredToken) "Подключение сохранено. Можно добавить виджет." else "Подключение ещё не настроено")
    }
    var busy by remember { mutableStateOf(false) }
    var explainRealtime by remember { mutableStateOf(false) }
    var chooseDashboard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { TopAppBar(
        title = { Text("HA Custom Widgets") },
        actions = {
            IconButton(onClick = {
                when (val destination = DashboardSettingsLaunchPolicy.resolve(dashboards.map { it.first })) {
                    DashboardSettingsDestination.Empty -> chooseDashboard = true
                    is DashboardSettingsDestination.Direct -> onOpenDashboardSettings(destination.appWidgetId)
                    is DashboardSettingsDestination.Choose -> chooseDashboard = true
                }
            }) { Icon(Icons.Default.Settings, contentDescription = "Настройки Dashboard") }
        },
    ) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Подключение к Home Assistant", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Введите внешний или локальный адрес сервера и Long-Lived Access Token. " +
                    "Токен шифруется ключом Android Keystore и не записывается в исходный код.",
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Адрес Home Assistant") },
                placeholder = { Text("https://home.example.com") },
                singleLine = true,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (hasStoredToken) "Новый токен (оставьте пустым, чтобы сохранить текущий)" else "Long-Lived Access Token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Button(
                enabled = !busy && url.isNotBlank() && (token.isNotBlank() || hasStoredToken),
                onClick = {
                    scope.launch {
                        busy = true
                        status = "Проверяю подключение…"
                        status = runCatching { onConnect(url, token) }
                            .fold(
                                onSuccess = { "Подключено. Доступно сущностей: $it" },
                                onFailure = { "Ошибка: ${it.message ?: "не удалось подключиться"}" },
                            )
                        busy = false
                    }
                },
            ) { Text(if (busy) "Подождите…" else "Проверить и сохранить") }

            Card(modifier = Modifier.fillMaxWidth()) {
                Text(status, modifier = Modifier.padding(16.dp))
            }
            Text("Real-time обновления", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(if (realtimeGranted) "Real-time: Включено" else "Real-time: Выключено")
                    Text(
                        if (realtimeGranted) {
                            "Android поддерживает фоновую работу канала обновлений виджетов."
                        } else {
                            "Без системного доступа внешние изменения обновляются в режиме best-effort и через Refresh."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!realtimeGranted) {
                        Button(onClick = { explainRealtime = true }) { Text("Включить Real-time") }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Как добавить виджет", style = MaterialTheme.typography.titleMedium)
            Text("1. Удерживайте пустое место на домашнем экране.\n2. Откройте «Виджеты».\n3. Найдите HA Custom Widgets.\n4. Перетащите «Состояние сущности HA» и выберите сущность.")
            Text(
                "Для безопасности предпочтителен HTTPS. HTTP оставлен доступным для локальных адресов Home Assistant.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Версия ${BuildConfig.VERSION_NAME}",
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (explainRealtime) {
        AlertDialog(
            onDismissRequest = { explainRealtime = false },
            title = { Text("Real-time обновления") },
            text = {
                Text(
                    "Для обновления виджетов в реальном времени Android должен поддерживать " +
                        "фоновую работу приложения. Для этого используется системный доступ " +
                        "Notification access — тот же принцип применяет официальное приложение " +
                        "Home Assistant для Real-time widgets. HA Custom Widgets не читает и " +
                        "не использует содержимое ваших уведомлений.",
                )
            },
            confirmButton = {
                Button(onClick = { explainRealtime = false; onEnableRealtime() }) {
                    Text("Открыть настройки")
                }
            },
            dismissButton = {
                Button(onClick = { explainRealtime = false }) { Text("Отмена") }
            },
        )
    }
    if (chooseDashboard) {
        AlertDialog(
            onDismissRequest = { chooseDashboard = false },
            title = { Text(if (dashboards.isEmpty()) "Dashboard не найден" else "Выберите Dashboard") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dashboards.isEmpty()) Text("Добавьте HA Dashboard на домашний экран, затем откройте настройки снова.")
                    dashboards.forEach { (id, label) ->
                        TextButton(onClick = { chooseDashboard = false; onOpenDashboardSettings(id) }) {
                            Text("$label · widget $id")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseDashboard = false }) { Text("Закрыть") } },
        )
    }
}

private fun dashboardLabel(id: Int, state: com.danila.hacustomwidgets.dashboard.DashboardState?): String =
    state?.selectedTab?.name?.let { "HA Dashboard · $it" } ?: "HA Dashboard #$id"
