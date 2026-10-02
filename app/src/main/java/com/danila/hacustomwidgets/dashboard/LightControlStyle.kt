package com.danila.hacustomwidgets.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/** Visual slots only: Material Slider continues to own gestures, steps and accessibility. */
object LightControlStyle {
    val trackHeight = 10.dp
    val thumbDiameter = 20.dp
    val hitHeight = 48.dp
    val temperatureColors = listOf(Color(0xffffbf69), Color(0xffffedcf), Color(0xfff5f8ff), Color(0xff82beff))
}

@Composable
fun LightControlHeader(label: String, value: String) {
    val style = MaterialTheme.typography.titleMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val required = measurer.measure(label, style).size.width + measurer.measure(value, style).size.width + with(density) { 12.dp.toPx() }
        if (required <= with(density) { maxWidth.toPx() }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = style)
                Spacer(Modifier.width(12.dp))
                Text(value, style = style)
            }
        } else Column {
            Text(label, style = style)
            Text(value, style = style)
        }
    }
}

@Composable
fun LightControlTrack(brush: Brush, modifier: Modifier = Modifier, fraction: Float? = null, activeColor: Color = Color.Transparent) {
    Canvas(modifier.fillMaxWidth().height(LightControlStyle.trackHeight)) {
        val radius = size.height / 2
        val path = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))) }
        clipPath(path) {
            drawRect(brush)
            fraction?.let { drawRect(activeColor, size = Size(size.width * it.coerceIn(0f, 1f), size.height)) }
        }
    }
}

@Composable
fun LightControlColorTrack(brush: Brush, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = LightControlStyle.hitHeight), contentAlignment = Alignment.Center) {
        LightControlTrack(brush, Modifier.padding(horizontal = LightControlStyle.thumbDiameter / 2))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightControlSlider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null, gradient: Brush? = null,
) {
    val active = MaterialTheme.colorScheme.primary
    val remaining = MaterialTheme.colorScheme.surfaceVariant
    val thumb = if (enabled) active else active.copy(alpha = 0.6f)
    Slider(value = value, onValueChange = onValueChange, enabled = enabled, valueRange = valueRange,
        steps = steps, onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.fillMaxWidth().heightIn(min = LightControlStyle.hitHeight),
        thumb = {
            Canvas(Modifier.size(LightControlStyle.thumbDiameter)) {
                drawCircle(Color(0xff10171c))
                drawCircle(Color(0xfff5f8ff), radius = size.minDimension / 2 - 1.dp.toPx())
                drawCircle(thumb, radius = size.minDimension / 2 - 3.dp.toPx())
            }
        },
        track = { state ->
            LightControlTrack(gradient ?: Brush.horizontalGradient(listOf(remaining, remaining)),
                fraction = if (gradient == null) (state.value - valueRange.start) / (valueRange.endInclusive - valueRange.start) else null,
                activeColor = if (enabled) active else active.copy(alpha = 0.6f))
        })
}
