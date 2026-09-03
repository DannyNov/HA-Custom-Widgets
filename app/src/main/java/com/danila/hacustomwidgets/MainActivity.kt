package com.danila.hacustomwidgets

import android.os.Bundle
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.foundation.Image
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

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
        mutableStateOf(if (hasStoredToken) tr("Connection saved. You can add a widget.", "Подключение сохранено. Можно добавить виджет.") else tr("Connection is not configured yet", "Подключение ещё не настроено"))
    }
    var busy by remember { mutableStateOf(false) }
    var explainRealtime by remember { mutableStateOf(false) }
    var chooseDashboard by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { TopAppBar(
        title = { Text("HA Custom Widgets") },
        actions = {
            TextButton(onClick = { showSupport = true }) {
                Text(tr("Tips", "Поддержать"))
            }
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
            Text(tr("Home Assistant connection", "Подключение к Home Assistant"), style = MaterialTheme.typography.headlineSmall)
            Text(
                tr("Enter an external or local server address and a Long-Lived Access Token. The token is encrypted with Android Keystore and is never stored in source code.",
                    "Введите внешний или локальный адрес сервера и Long-Lived Access Token. Токен шифруется ключом Android Keystore и не записывается в исходный код."),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("Home Assistant address", "Адрес Home Assistant")) },
                placeholder = { Text("https://home.example.com") },
                singleLine = true,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (hasStoredToken) tr("New token (leave blank to keep current)", "Новый токен (оставьте пустым, чтобы сохранить текущий)") else "Long-Lived Access Token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Button(
                enabled = !busy && url.isNotBlank() && (token.isNotBlank() || hasStoredToken),
                onClick = {
                    scope.launch {
                        busy = true
                        status = tr("Checking connection…", "Проверяю подключение…")
                        status = runCatching { onConnect(url, token) }
                            .fold(
                                onSuccess = { tr("Connected. Available entities: $it", "Подключено. Доступно сущностей: $it") },
                                onFailure = { tr("Error: ${it.message ?: "connection failed"}", "Ошибка: ${it.message ?: "не удалось подключиться"}") },
                            )
                        busy = false
                    }
                },
            ) { Text(if (busy) tr("Please wait…", "Подождите…") else tr("Check and save", "Проверить и сохранить")) }

            Card(modifier = Modifier.fillMaxWidth()) {
                Text(status, modifier = Modifier.padding(16.dp))
            }
            Text(tr("Real-time updates", "Real-time обновления"), style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(if (realtimeGranted) tr("Real-time: Enabled", "Real-time: Включено") else tr("Real-time: Disabled", "Real-time: Выключено"))
                    Text(
                        if (realtimeGranted) {
                            tr("Android allows the widget update channel to run in the background.", "Android поддерживает фоновую работу канала обновлений виджетов.")
                        } else {
                            tr("Without system access, external changes update on a best-effort basis and through Refresh.", "Без системного доступа внешние изменения обновляются в режиме best-effort и через Refresh.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!realtimeGranted) {
                        Button(onClick = { explainRealtime = true }) { Text(tr("Enable Real-time", "Включить Real-time")) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(tr("How to add a widget", "Как добавить виджет"), style = MaterialTheme.typography.titleMedium)
            Text(tr("1. Touch and hold an empty area on the Home screen.\n2. Open Widgets.\n3. Find HA Custom Widgets.\n4. Drag HA Dashboard to the Home screen and configure it.", "1. Удерживайте пустое место на домашнем экране.\n2. Откройте «Виджеты».\n3. Найдите HA Custom Widgets.\n4. Перетащите «HA Dashboard» на домашний экран и настройте его."))
            Text(
                tr("HTTPS is preferred for security. HTTP remains available for local Home Assistant addresses.", "Для безопасности предпочтителен HTTPS. HTTP оставлен доступным для локальных адресов Home Assistant."),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = tr("Version ${BuildConfig.VERSION_NAME}", "Версия ${BuildConfig.VERSION_NAME}"),
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
            title = { Text(tr("Real-time updates", "Real-time обновления")) },
            text = {
                Text(
                    tr("For real-time widget updates, Android must allow background operation. This uses Notification access—the same approach as the official Home Assistant app for real-time widgets. HA Custom Widgets does not read or use your notification contents.", "Для обновления виджетов в реальном времени Android должен поддерживать " +
                        "фоновую работу приложения. Для этого используется системный доступ " +
                        "Notification access — тот же принцип применяет официальное приложение " +
                        "Home Assistant для Real-time widgets. HA Custom Widgets не читает и " +
                        "не использует содержимое ваших уведомлений."),
                )
            },
            confirmButton = {
                Button(onClick = { explainRealtime = false; onEnableRealtime() }) {
                    Text(tr("Open settings", "Открыть настройки"))
                }
            },
            dismissButton = {
                Button(onClick = { explainRealtime = false }) { Text(tr("Cancel", "Отмена")) }
            },
        )
    }
    if (chooseDashboard) {
        AlertDialog(
            onDismissRequest = { chooseDashboard = false },
            title = { Text(if (dashboards.isEmpty()) tr("Dashboard not found", "Dashboard не найден") else tr("Choose Dashboard", "Выберите Dashboard")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dashboards.isEmpty()) Text(tr("Add HA Dashboard to the Home screen, then open settings again.", "Добавьте HA Dashboard на домашний экран, затем откройте настройки снова."))
                    dashboards.forEach { (id, label) ->
                        TextButton(onClick = { chooseDashboard = false; onOpenDashboardSettings(id) }) {
                            Text("$label · widget $id")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseDashboard = false }) { Text(tr("Close", "Закрыть")) } },
        )
    }
    if (showSupport) SupportDialog(onDismiss = { showSupport = false })
}

@Composable
internal fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val bep20 = "0xe7FA8d9608d50e1B7C645D8185473BCE3A3c14Df"
    val ton = "UQB2SAZRVJZIHu7hpNSIYHKUPhn_frtrlHITFw6CbQKrNk9c"
    val cloudTips: @Composable () -> Unit = {
        Text("CloudTips", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pay.cloudtips.ru/p/ab27592e"))) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(tr("Open CloudTips", "Открыть CloudTips")) }
    }
    val crypto: @Composable () -> Unit = {
        CryptoAddress("USDT · BEP-20", bep20, clipboard::setText)
        CryptoAddress("USDT · TON", ton, clipboard::setText)
        Text(
            tr(
                "Send only USDT using the exact network shown. Sending another asset or network may permanently lose funds.",
                "Отправляйте только USDT в точно указанной сети. Другая монета или сеть может привести к безвозвратной потере средств.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Support development", "Поддержать разработку")) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isRussianUi()) { cloudTips(); crypto() } else { crypto(); cloudTips() }
                Text(
                    tr(
                        "Support is voluntary and is not payment for goods, services, or additional features.",
                        "Перевод добровольный и не является оплатой товаров, услуг или дополнительных функций.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "Закрыть")) } },
    )
}

@Composable
private fun CryptoAddress(label: String, address: String, copy: (AnnotatedString) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Image(
            bitmap = remember(address) { qrBitmap(address).asImageBitmap() },
            contentDescription = "$label QR",
            modifier = Modifier.size(144.dp),
        )
        Text(address, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { copy(AnnotatedString(address)) }) { Text(tr("Copy address", "Копировать адрес")) }
    }
}

private fun qrBitmap(value: String): Bitmap {
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        pixels[y * matrix.width + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    }
}

private fun dashboardLabel(id: Int, state: com.danila.hacustomwidgets.dashboard.DashboardState?): String =
    state?.selectedTab?.name?.let { "HA Dashboard · $it" } ?: "HA Dashboard #$id"
