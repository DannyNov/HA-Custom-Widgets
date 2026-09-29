package com.danila.hacustomwidgets.dashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.tr
import com.danila.hacustomwidgets.ui.HaCustomWidgetsTheme
import kotlin.math.roundToInt

class BrightnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entityId = intent.getStringExtra("brightness_entity") ?: return finish()
        val widgetId = intent.getIntExtra("brightness_widget", -1)
        val container = (application as HaWidgetApplication).container
        container.dashboardEvents.wakeAsync("BRIGHTNESS_DIALOG")
        setContent {
            HaCustomWidgetsTheme {
                val dashboard by container.dashboards.observe(widgetId).collectAsState()
                val control = dashboard?.cards?.flatMap { it.controls }?.firstOrNull { it.entityId == entityId }
                var selected by remember { mutableStateOf<Float?>(null) }
                var dragging by remember { mutableStateOf(false) }
                LaunchedEffect(control?.brightnessPercent, dragging) {
                    if (!dragging) selected = control?.brightnessPercent?.toFloat()
                }
                val enabled = control?.brightnessCapable == true && control.state in setOf("on", "off") && selected != null
                Surface {
                    Column(Modifier.padding(24.dp).widthIn(max = 360.dp)) {
                        Text(control?.label ?: entityId, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        Text(selected?.roundToInt()?.let { "$it%" } ?: "—%", style = MaterialTheme.typography.headlineMedium)
                        if (selected != null) {
                            Slider(
                                value = selected!!,
                                onValueChange = { dragging = true; selected = it },
                                onValueChangeFinished = {
                                    if (dragging && enabled) selected?.roundToInt()?.let { container.brightness.submit(entityId, absolute = it) }
                                    dragging = false
                                },
                                valueRange = 1f..100f, steps = 98, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                    contentDescription = tr("Lamp brightness", "Яркость лампы")
                                },
                            )
                        } else {
                            Text(tr("Waiting for confirmed brightness", "Ожидание подтверждённой яркости"))
                        }
                        dashboard?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { finish() }) { Text(tr("Close", "Закрыть")) }
                    }
                }
            }
        }
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
