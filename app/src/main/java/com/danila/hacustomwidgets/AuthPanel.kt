package com.danila.hacustomwidgets

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danila.hacustomwidgets.data.security.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun AuthPanel(
    connection: HomeAssistantConnection?,
    status: String,
    discovery: HomeAssistantDiscovery,
    pendingLogin: Boolean,
    legacyContent: @Composable () -> Unit,
    onLogin: suspend (String) -> Unit,
    onCancel: () -> Unit,
    onLogout: suspend (Boolean) -> Unit,
    onCheck: suspend () -> Unit,
    onExternal: suspend (String) -> Unit,
    onDiscovered: suspend (String) -> Unit,
) {
    var scan by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var servers by remember { mutableStateOf(emptyList<DiscoveredServer>()) }
    var manual by remember { mutableStateOf(false) }
    var address by remember(connection?.baseUrl) { mutableStateOf(connection?.baseUrl.orEmpty()) }
    var external by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var dismissedMigration by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var httpLogin by remember { mutableStateOf<String?>(null) }
    var localAddress by remember { mutableStateOf("") }
    var confirmLocal by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun action(block: suspend () -> Unit) { scope.launch {
        busy = true
        try { block(); message = "" }
        catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            message = error.message ?: tr("Connection failed", "Не удалось подключиться")
        } finally { busy = false }
    } }
    fun login(url: String) {
        if (url.trim().startsWith("http://")) httpLogin = url else action { onLogin(url) }
    }
    LaunchedEffect(scan, connection == null) {
        if (connection == null || scan > 0) {
            searching = true; servers = emptyList()
            try { withTimeoutOrNull(12_000) { discovery.discover().collect { servers = it } } }
            finally { searching = false }
        }
    }
    if (connection == null) {
        Text(if (searching) tr("Searching for Home Assistant…", "Поиск Home Assistant…")
            else if (servers.isEmpty()) tr("Home Assistant was not found automatically", "Не удалось найти Home Assistant автоматически")
            else tr("Choose your Home Assistant", "Выберите свой Home Assistant"))
        servers.forEach { server -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text(server.name); Text(server.url, style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy, onClick = { login(server.url) }) { Text(tr("Connect", "Подключиться")) }
        } } }
        TextButton(enabled = !busy && !searching, onClick = { scan++ }) { Text(tr("Search again", "Повторить поиск")) }
        TextButton(onClick = { manual = !manual }) { Text(tr("Enter address manually", "Ввести адрес вручную")) }
    } else {
        Text(if (connection.isOAuth && connection.token.isEmpty()) tr("Sign in again", "Требуется повторный вход")
            else tr("Home Assistant connected", "Home Assistant подключён"), style = MaterialTheme.typography.titleMedium)
        val current = connection.server.routes.firstOrNull { it.url == connection.server.lastWorkingUrl }
        Text(when (current?.kind) {
            RouteKind.EXTERNAL, RouteKind.CLOUD -> tr("Currently using: remote connection", "Сейчас используется: удалённое подключение")
            RouteKind.INTERNAL, RouteKind.DISCOVERED -> tr("Currently using: local network", "Сейчас используется: локальная сеть")
            null -> tr("Connection mode will appear after a successful check", "Режим подключения появится после успешной проверки")
        })
        TextButton(enabled = !busy, onClick = { login(connection.server.lastWorkingUrl ?: connection.baseUrl) }) {
            Text(tr("Sign in again", "Войти повторно"))
        }
        TextButton(enabled = !busy, onClick = { action { onCheck() } }) { Text(tr("Check connection", "Проверить подключение")) }
        TextButton(onClick = { diagnostics = !diagnostics }) { Text(tr("Connection diagnostics", "Диагностика подключения")) }
        if (diagnostics) {
            val current = connection.server.routes.firstOrNull { it.url == connection.server.lastWorkingUrl }
            Text(tr("Last working route", "Последний рабочий маршрут") + ": " + when (current?.kind) {
                RouteKind.CLOUD -> "Home Assistant Cloud"
                RouteKind.EXTERNAL -> tr("Remote", "Удалённый")
                RouteKind.INTERNAL -> tr("Local", "Локальный")
                else -> tr("Initial address", "Первоначальный адрес")
            })
            connection.server.routes.forEach { Text("${it.kind}: ${it.url}", style = MaterialTheme.typography.bodySmall) }
            Text(tr("Last successful connection: ", "Последнее успешное подключение: ") +
                if (connection.server.lastSuccessAt > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(connection.server.lastSuccessAt)) else "—")
            Text(tr("Known addresses are not a live availability check.", "Список адресов не означает, что каждый из них сейчас доступен."))
        }
        TextButton(enabled = !busy, onClick = { logout = true }) { Text(tr("Disconnect Home Assistant", "Отключить Home Assistant")) }
    }
    TextButton(onClick = { advanced = !advanced }) { Text(tr("Advanced", "Дополнительно")) }
    if (advanced) {
        if (connection != null) {
        if (!connection.isOAuth && !dismissedMigration) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text(tr("A new sign-in method is available. Your dashboards and settings will be kept.",
                "Доступен новый способ входа через Home Assistant. Виджеты и настройки сохранятся."))
            Button(enabled = !busy, onClick = { login(connection.baseUrl) }) { Text(tr("Switch to Home Assistant sign-in", "Перейти на новый способ")) }
            TextButton(onClick = { dismissedMigration = true }) { Text(tr("Later", "Позже")) }
        } }
        if (connection.server.routes.none { it.kind == RouteKind.EXTERNAL || it.kind == RouteKind.CLOUD }) {
            Text(tr("Home Assistant did not provide a remote address.", "Home Assistant не сообщил внешний адрес."))
            OutlinedTextField(external, { external = it }, label = { Text(tr("External HTTPS address", "Внешний HTTPS-адрес")) }, singleLine = true)
            TextButton(enabled = !busy && external.isNotBlank(), onClick = { action { onExternal(external) } }) { Text(tr("Save remote access", "Сохранить удалённый доступ")) }
            TextButton(onClick = { message = tr("Local network only", "Работа только в локальной сети") }) { Text(tr("Local network only", "Только локальная сеть")) }
        }
        OutlinedTextField(localAddress, { localAddress = it }, label = { Text(tr("Local fallback address", "Локальный резервный адрес")) }, singleLine = true)
        TextButton(enabled = !busy && localAddress.isNotBlank(), onClick = { confirmLocal = true }) {
            Text(tr("Add local fallback", "Добавить локальный резервный адрес"))
        }
        }
        legacyContent()
    }
    if (manual && connection == null) {
        OutlinedTextField(address, { address = it }, label = { Text(tr("Home Assistant address", "Адрес Home Assistant")) }, singleLine = true)
        Button(enabled = !busy && address.isNotBlank(), onClick = { login(address) }) { Text(tr("Sign in to Home Assistant", "Войти в Home Assistant")) }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (status.isNotBlank()) Text(status)
    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
    if (pendingLogin) TextButton(enabled = !busy, onClick = { onCancel(); message = tr("Pending sign-in cancelled", "Ожидающий вход отменён") }) {
        Text(tr("Cancel pending sign-in", "Отменить ожидающий вход"))
    }
    if (httpLogin != null) AlertDialog(onDismissRequest = { httpLogin = null },
        title = { Text(tr("Unencrypted local connection", "Незашифрованное локальное подключение")) },
        text = { Text(tr("HTTP exposes sign-in and tokens to the network. Continue only on a trusted network, or use HTTPS.",
            "HTTP передаёт данные входа и токены без шифрования. Продолжайте только в доверенной сети или используйте HTTPS.")) },
        confirmButton = { TextButton(onClick = { val url = httpLogin!!; httpLogin = null; action { onLogin(url) } }) { Text(tr("Continue", "Продолжить")) } },
        dismissButton = { TextButton(onClick = { httpLogin = null }) { Text(tr("Cancel", "Отмена")) } })
    if (confirmLocal) AlertDialog(onDismissRequest = { confirmLocal = false },
        title = { Text(tr("Trust this Home Assistant address?", "Доверять этому адресу Home Assistant?")) },
        text = { Text(tr("Confirm that $localAddress belongs to the same Home Assistant. Your saved access will be sent there. HTTP exposes it on the local network. An address advertised by discovery alone is not proof.",
            "Подтвердите, что $localAddress принадлежит тому же Home Assistant. На этот адрес будет отправлен сохранённый доступ. HTTP передаёт его открыто в локальной сети. Одного обнаружения адреса недостаточно для проверки.")) },
        confirmButton = { TextButton(onClick = { confirmLocal = false; action { onDiscovered(localAddress) } }) { Text(tr("Trust and check", "Доверять и проверить")) } },
        dismissButton = { TextButton(onClick = { confirmLocal = false }) { Text(tr("Cancel", "Отмена")) } })
    if (logout) AlertDialog(onDismissRequest = { logout = false },
        title = { Text(tr("Disconnect Home Assistant?", "Отключить Home Assistant?")) },
        text = { Text(tr("OAuth access will be revoked. Widget settings stay on this device. Legacy tokens must be revoked in your HA profile. If HA is offline, local removal leaves server credentials valid.",
            "OAuth-доступ будет отозван. Настройки виджетов останутся. LLAT нужно отозвать в профиле HA. При локальном удалении без связи с HA серверный доступ останется действительным.")) },
        confirmButton = { TextButton(onClick = { logout = false; action { onLogout(false) } }) { Text(tr("Revoke and disconnect", "Отозвать и отключить")) } },
        dismissButton = { Column {
            TextButton(onClick = { logout = false; action { onLogout(true) } }) { Text(tr("Remove locally only", "Удалить только локально")) }
            TextButton(onClick = { logout = false }) { Text(tr("Cancel", "Отмена")) }
        } })
}
