package com.danila.hacustomwidgets

import android.os.Bundle
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.danila.hacustomwidgets.data.security.HomeAssistantConnection
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as HaWidgetApplication).container
        setContent {
            MaterialTheme {
                ConnectionScreen(
                    initialUrl = container.connectionStore.load()?.baseUrl.orEmpty(),
                    hasStoredToken = container.connectionStore.load() != null,
                    onConnect = { url, token ->
                        val effectiveToken = token.ifBlank {
                            container.connectionStore.load()?.token.orEmpty()
                        }
                        val connection = HomeAssistantConnection(url.trim().trimEnd('/'), effectiveToken)
                        container.client.testConnection(connection)
                        container.connectionStore.save(connection.baseUrl, connection.token)
                        container.client.getEntities(connection).size
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionScreen(
    initialUrl: String,
    hasStoredToken: Boolean,
    onConnect: suspend (String, String) -> Int,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(if (hasStoredToken) "Подключение сохранено. Можно добавить виджет." else "Подключение ещё не настроено")
    }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { TopAppBar(title = { Text("HA Custom Widgets") }) }) { padding ->
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
            Spacer(Modifier.height(4.dp))
            Text("Как добавить виджет", style = MaterialTheme.typography.titleMedium)
            Text("1. Удерживайте пустое место на домашнем экране.\n2. Откройте «Виджеты».\n3. Найдите HA Custom Widgets.\n4. Перетащите «Состояние сущности HA» и выберите сущность.")
            Text(
                "Для безопасности предпочтителен HTTPS. HTTP оставлен доступным для локальных адресов Home Assistant.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
