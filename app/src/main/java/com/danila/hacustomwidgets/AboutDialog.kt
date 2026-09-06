package com.danila.hacustomwidgets

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

object AboutLinks {
    const val GITHUB = "https://github.com/DannyNov/HA-Custom-Widgets"
    const val TELEGRAM = "https://t.me/HACustomWidgets"
    const val PRIVACY = "https://dannynov.github.io/HA-Custom-Widgets/privacy-policy/"
    const val ISSUES = "$GITHUB/issues"
    const val LICENSE = "$GITHUB/blob/main/LICENSE"
    const val CONTACT = "mailto:hacustomwidgets@gmail.com"
    val all = listOf(GITHUB, TELEGRAM, PRIVACY, ISSUES, LICENSE, CONTACT)
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("About", "О приложении")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.drawable.ha_custom_widgets_brand), "HA Custom Widgets", Modifier.size(80.dp))
                Text("HA Custom Widgets", style = MaterialTheme.typography.titleLarge)
                Text(tr("Version ${BuildConfig.VERSION_NAME}", "Версия ${BuildConfig.VERSION_NAME}"))
                listOf(
                    "GitHub" to AboutLinks.GITHUB,
                    "Telegram" to AboutLinks.TELEGRAM,
                    tr("Privacy Policy", "Политика конфиденциальности") to AboutLinks.PRIVACY,
                    tr("Report a bug", "Сообщить об ошибке") to AboutLinks.ISSUES,
                    tr("License: Apache-2.0", "Лицензия: Apache-2.0") to AboutLinks.LICENSE,
                    "hacustomwidgets@gmail.com" to AboutLinks.CONTACT,
                ).forEach { (label, uri) ->
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(if (uri.startsWith("mailto:")) Intent.ACTION_SENDTO else Intent.ACTION_VIEW, Uri.parse(uri)))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, tr("No app available to open this link", "Нет приложения для открытия ссылки"), Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "Закрыть")) } },
    )
}
