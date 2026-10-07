package com.danila.hacustomwidgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.data.security.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class AuthPanelUiTest {
    @get:Rule val compose = createComposeRule()
    private val originalLocale = Locale.getDefault()
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }

    private fun show(russian: Boolean = false, oauth: Boolean = true, pending: Boolean = false,
                     kind: RouteKind = RouteKind.EXTERNAL, token: String = "access") {
        Locale.setDefault(if (russian) Locale("ru") else Locale.ENGLISH)
        val waiting = mutableStateOf(pending)
        val url = "https://ha.example.com"
        val connection = HomeAssistantConnection(url, token, if (oauth) "refresh" else null,
            server = ServerMetadata(routes = listOf(ServerRoute(url, kind)), lastWorkingUrl = url))
        val discovery = HomeAssistantDiscovery(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                AuthPanel(connection, "", discovery, waiting.value, { Text("Long-Lived Access Token") },
                    onLogin = {}, onCancel = { waiting.value = false }, onLogout = {}, onCheck = {},
                    onExternal = {}, onDiscovered = {})
            } }
        }
    }

    @Test fun englishConnectedStateAndSections() {
        show()
        compose.onNodeWithText("Home Assistant connected").assertExists()
        compose.onNodeWithText("Currently using: remote connection").assertExists()
        compose.onNodeWithText("Home Assistant sign-in saved").assertDoesNotExist()
        compose.onNodeWithText("Cancel pending sign-in").assertDoesNotExist()
        compose.onNodeWithText("Long-Lived Access Token").assertDoesNotExist()
        compose.onNodeWithText("Local fallback address").assertDoesNotExist()
        compose.onNodeWithText("EXTERNAL: https://ha.example.com").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Local fallback address").assertExists()
        compose.onNodeWithText("Long-Lived Access Token").assertExists()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Long-Lived Access Token").assertDoesNotExist()
        compose.onNodeWithText("Connection diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("EXTERNAL: https://ha.example.com").assertExists()
    }

    @Test fun russianPendingActionDisappearsAfterCancellation() {
        show(russian = true, pending = true, kind = RouteKind.INTERNAL)
        compose.onNodeWithText("Home Assistant подключён").assertExists()
        compose.onNodeWithText("Сейчас используется: локальная сеть").assertExists()
        compose.onNodeWithText("Отменить ожидающий вход").performScrollTo().performClick()
        compose.onNodeWithText("Отменить ожидающий вход").assertDoesNotExist()
        compose.onNodeWithText("Войти повторно").assertExists()
        compose.onNodeWithText("Проверить подключение").assertExists()
        compose.onNodeWithText("Отключить Home Assistant").assertExists()
    }

    @Test fun legacyConnectionIsConnectedAndMigrationRemainsOptional() {
        show(oauth = false)
        compose.onNodeWithText("Home Assistant connected").assertExists()
        compose.onNodeWithText("Switch to Home Assistant sign-in").assertDoesNotExist()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Switch to Home Assistant sign-in").assertExists()
        compose.onNodeWithText("Long-Lived Access Token").assertExists()
    }

    @Test fun rejectedOAuthSessionRequiresSignIn() {
        show(russian = true, token = "")
        compose.onNodeWithText("Требуется повторный вход").assertExists()
        compose.onNodeWithText("Home Assistant подключён").assertDoesNotExist()
    }

    @Test fun discoveredLanIsLocalizedInEnglish() {
        show(kind = RouteKind.DISCOVERED)
        compose.onNodeWithText("Currently using: local network").assertExists()
    }
}
