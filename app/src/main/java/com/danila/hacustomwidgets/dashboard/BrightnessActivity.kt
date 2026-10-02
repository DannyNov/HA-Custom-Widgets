package com.danila.hacustomwidgets.dashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.launch

class BrightnessActivity : ComponentActivity() {
    override fun finish() {
        // Explicit Close/Back removes only our helper task; recreation never calls this.
        if (isTaskRoot) super.finishAndRemoveTask() else super.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entityId = intent.getStringExtra("brightness_entity") ?: return finish()
        val widgetId = intent.getIntExtra("brightness_widget", -1)
        val container = (application as HaWidgetApplication).container
        container.dashboardEvents.wakeAsync("BRIGHTNESS_DIALOG")
        // Current window bounds support rotation/multi-window; exclude bars and cutouts.
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val density = resources.displayMetrics.density
        val availableWidth = metrics.bounds.width() - insets.left - insets.right
        val availableHeight = metrics.bounds.height() - insets.top - insets.bottom
        val panelWidth = minOf(availableWidth - (48 * density).roundToInt(), (420 * density).roundToInt()).coerceAtLeast(1)
        val panelMaxHeight = ((availableHeight / density) - 48).coerceAtLeast(48f)
        setContent {
            HaCustomWidgetsTheme {
                val dashboard by container.dashboards.observe(widgetId).collectAsState()
                val control = dashboard?.cards?.flatMap { it.controls }?.firstOrNull { it.entityId == entityId }
                var draft by remember { mutableStateOf<Float?>(null) }
                var gesture by remember { mutableStateOf(0L) }
                val scope = rememberCoroutineScope()
                val operationRevision by container.brightness.revisions.collectAsState()
                val selection = remember { BrightnessSelection() }
                val submittedOrConfirmed = operationRevision.let {
                    container.brightness.displayPercent(entityId)
                }
                val selected = draft ?: submittedOrConfirmed?.toFloat()
                val enabled = control?.brightnessCapable == true && control.state in setOf("on", "off") && selected != null
                Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.heightIn(max = panelMaxHeight.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text(control?.friendlyName ?: entityId, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        LightControlHeader(tr("Brightness", "Яркость"), selected?.roundToInt()?.let { "$it%" } ?: "—%")
                        if (selected != null) {
                            LightControlSlider(
                                value = selected!!,
                                onValueChange = { gesture++; draft = it; selection.change(it) },
                                onValueChangeFinished = {
                                    val finishedGesture = gesture
                                    val target = selection.finish(enabled)
                                    scope.launch {
                                        // Keep the draft until the shared coordinator accepts/rejects
                                        // submission, including mutex contention. A newer drag owns itself.
                                        target?.let { container.brightness.submit(entityId, absolute = it).join() }
                                        if (gesture == finishedGesture) draft = null
                                    }
                                },
                                valueRange = 1f..100f, steps = 98, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                    contentDescription = tr("Lamp brightness", "Яркость лампы")
                                },
                            )
                        } else {
                            Text(tr("Waiting for confirmed brightness", "Ожидание подтверждённой яркости"))
                        }
                        LightColorControls(entityId, container.brightness, operationRevision + (dashboard?.stateRevision ?: 0))
                        dashboard?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { finish() }) { Text(tr("Close", "Закрыть")) }
                    }
                }
            }
        }
        window.setLayout(panelWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
