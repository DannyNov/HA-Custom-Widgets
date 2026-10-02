package com.danila.hacustomwidgets.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.danila.hacustomwidgets.data.model.LightColor
import com.danila.hacustomwidgets.tr
import kotlin.math.*
import kotlinx.coroutines.launch

private val whiteScale = LightControlStyle.temperatureColors
private val rainbow = (0..6).map { Color.hsv((it * 60f).coerceAtMost(359.99f), 1f, 1f) }
private fun LightColor.preview() = Color.hsv(hue.toFloat().coerceAtMost(359.99f), saturation.toFloat()/100f, 1f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LightColorControls(entityId: String, coordinator: BrightnessCoordinator, revision: Long) {
    val state = revision.let { coordinator.state(entityId) } ?: return
    val light = state.brightness
    val enabled = state.confirmedRawState in setOf("on", "off") && state.optimisticOverlay == null
    var draftTemperature by remember(entityId) { mutableStateOf<Float?>(null) }
    var picker by remember(entityId) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var gesture by remember { mutableStateOf(0L) }
    if (light.temperatureCapable) {
        Spacer(Modifier.height(16.dp))
        val range = light.kelvinRange
        val temperature = draftTemperature?.roundToInt() ?: coordinator.temperatureTarget(entityId)
            ?: light.temperatureKelvin?.takeIf { light.colorMode == "color_temp" }
            ?: state.lastConfirmedTemperature
        LightControlHeader(tr("Color temperature", "Цветовая температура"), temperature?.let { "$it K" } ?: "— K")
        if (range != null) {
            if (range.minimum < range.maximum) {
                LightControlSlider(value = (temperature ?: range.clamp(4000)).coerceIn(range.minimum, range.maximum).toFloat(),
                    onValueChange = { gesture++; draftTemperature = it },
                    onValueChangeFinished = {
                        val finished = gesture
                        val target = draftTemperature?.roundToInt()
                        scope.launch {
                            target?.let { coordinator.temperature(entityId, it).join() }
                            if (gesture == finished) draftTemperature = null
                        }
                    }, valueRange = range.minimum.toFloat()..range.maximum.toFloat(), enabled = enabled,
                    gradient = Brush.horizontalGradient(whiteScale),
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                        contentDescription = tr("Lamp color temperature in Kelvin", "Цветовая температура лампы в Кельвинах")
                        progressBarRangeInfo = ProgressBarRangeInfo((temperature ?: range.clamp(4000)).coerceIn(range.minimum,range.maximum).toFloat(),range.minimum.toFloat()..range.maximum.toFloat())
                        if (!enabled) disabled()
                        setProgress { target ->
                            if (enabled && target.isFinite()) { coordinator.temperature(entityId,range.clamp(target.roundToInt())); true } else false
                        }
                    })
            }
            val names = listOf(tr("Warm", "Тёплый"), tr("Medium", "Средний"), tr("Cool", "Холодный"))
            val largeFont = LocalConfiguration.current.fontScale > 1.3f
            @Composable fun preset(i: Int, target: Int, modifier: Modifier) {
                TextButton(onClick = { coordinator.temperature(entityId, target) }, enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "${names[i]}: $target K"
                    }) { Text(if (largeFont) "${names[i]} · $target K" else "${names[i]}\n$target K", textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
            if (largeFont) Column(Modifier.fillMaxWidth()) {
                range.presets().forEachIndexed { i, target -> preset(i, target, Modifier.fillMaxWidth()) }
            } else Row(Modifier.fillMaxWidth()) {
                range.presets().forEachIndexed { i, target -> preset(i, target, Modifier.weight(1f)) }
            }
        } else Text(tr("Waiting for the lamp's Kelvin range", "Ожидание диапазона лампы в Кельвинах"))
    }
    if (light.colorCapable) {
        Spacer(Modifier.height(16.dp))
        Text(tr("Color", "Цвет"), style = MaterialTheme.typography.titleMedium)
        val known = coordinator.colorTarget(entityId) ?: light.color?.takeIf { light.colorMode in LightColor.MODES }
            ?: state.lastConfirmedColor
        LightControlColorTrack(known?.let { Brush.horizontalGradient(listOf(it.preview(), it.preview())) }
                ?: Brush.horizontalGradient(rainbow), Modifier.fillMaxWidth()
            .semantics { contentDescription = if (known == null) tr("Choose lamp color", "Выбрать цвет лампы")
                else tr("Turn on lamp color. Hold to choose another color.", "Включить цвет лампы. Удерживайте для выбора другого цвета.") }
            .combinedClickable(enabled = enabled,
                onClick = { if (known == null) picker = true else coordinator.color(entityId, known) },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); picker = true }))
        Text(if (known == null) tr("Tap to choose a color", "Нажмите, чтобы выбрать цвет")
            else tr("Tap — turn on color • Hold — choose", "Нажать — включить цвет • Удерживать — выбрать"), style = MaterialTheme.typography.bodySmall)
        if (picker) LightColorPicker(known, { picker = false }) { coordinator.color(entityId, it) }
    }
}

@Composable
private fun LightColorPicker(initial: LightColor?, close: () -> Unit, apply: (LightColor) -> Unit) {
    var selected by remember { mutableStateOf(initial ?: LightColor(0.0,100.0)) }
    var touched by remember { mutableStateOf(false) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f
    val wheelMargin = with(LocalDensity.current) { 8.dp.toPx() }
    Dialog(onDismissRequest = close) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.heightIn(max = maxHeight).padding(20.dp)) {
                Text(tr("Choose color", "Выбрать цвет"), style = MaterialTheme.typography.titleMedium)
                Box(Modifier.fillMaxWidth().height(32.dp).background(selected.preview(), RoundedCornerShape(8.dp)))
                fun select(position: Offset, width: Int, height: Int) {
                    val x = position.x - width/2f; val y = position.y-height/2f
                    val hue = (atan2(y.toDouble(), x.toDouble())*180/PI+360)%360
                    val margin = minOf(wheelMargin, minOf(width,height)/4f)
                    val saturation = (hypot(x.toDouble(), y.toDouble())/(minOf(width,height)/2f-margin)*100).coerceIn(0.0,100.0)
                    selected = LightColor(hue,saturation); touched = true
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f)
                    .semantics { contentDescription = tr("Color wheel. Hue and saturation sliders are below.", "Цветовой круг. Ползунки оттенка и насыщенности ниже.") }
                    .pointerInput(Unit) { detectTapGestures { select(it,size.width,size.height) } }
                    .pointerInput(Unit) { detectDragGestures { change, _ -> change.consume(); select(change.position,size.width,size.height) } }) {
                    val margin = minOf(wheelMargin, size.minDimension/4)
                    val wheelRadius = size.minDimension/2-margin
                    for (hue in 0..359) drawArc(Color.hsv(hue.toFloat(),1f,1f), hue.toFloat(),2f,true,
                        topLeft=Offset(margin,margin),size=androidx.compose.ui.geometry.Size(size.width-2*margin,size.height-2*margin))
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, wheelRadius),radius=wheelRadius)
                    val angle = selected.hue * PI/180
                    val radius = wheelRadius*selected.saturation/100
                    val cursor = center + Offset((cos(angle)*radius).toFloat(), (sin(angle)*radius).toFloat())
                    drawCircle(Color.Black, 7.dp.toPx(),cursor)
                    drawCircle(Color.White, 5.dp.toPx(),cursor)
                }
                Text(tr("Hue", "Оттенок"))
                LightControlSlider(selected.hue.toFloat(), { selected = selected.copy(hue=it.toDouble()); touched=true },
                    valueRange=0f..360f, modifier=Modifier.clearAndSetSemantics {
                        contentDescription=tr("Hue", "Оттенок")
                        progressBarRangeInfo=ProgressBarRangeInfo(selected.hue.toFloat(),0f..360f)
                        setProgress { target -> if (target.isFinite()) {selected=selected.copy(hue=target.coerceIn(0f,360f).toDouble());touched=true;true}else false }
                    })
                Text(tr("Saturation", "Насыщенность"))
                LightControlSlider(selected.saturation.toFloat(), { selected = selected.copy(saturation=it.toDouble()); touched=true },
                    valueRange=0f..100f, modifier=Modifier.clearAndSetSemantics {
                        contentDescription=tr("Saturation", "Насыщенность")
                        progressBarRangeInfo=ProgressBarRangeInfo(selected.saturation.toFloat(),0f..100f)
                        setProgress { target -> if (target.isFinite()) {selected=selected.copy(saturation=target.coerceIn(0f,100f).toDouble());touched=true;true}else false }
                    })
                }
                Row {
                    TextButton(onClick=close) { Text(tr("Cancel", "Отмена")) }
                    TextButton(onClick={ apply(selected); close() },enabled=touched || initial != null) { Text(tr("Apply", "Применить")) }
                }
            }
        }
    }
}
