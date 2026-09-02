package com.danila.hacustomwidgets.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mediation
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SmokeFree
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Window
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.ElectricMeter
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Speed
import androidx.compose.ui.graphics.vector.ImageVector

fun HaSemanticIcon.imageVector(): ImageVector = when (this) {
    HaSemanticIcon.LIGHT -> Icons.Default.Lightbulb
    HaSemanticIcon.SWITCH -> Icons.Default.Power
    HaSemanticIcon.SENSOR, HaSemanticIcon.BINARY_SENSOR, HaSemanticIcon.CONNECTIVITY -> Icons.Default.Sensors
    HaSemanticIcon.BUTTON -> Icons.Default.TouchApp
    HaSemanticIcon.TOGGLE -> Icons.Default.ToggleOn
    HaSemanticIcon.THERMOSTAT, HaSemanticIcon.TEMPERATURE -> Icons.Default.Thermostat
    HaSemanticIcon.FAN -> Icons.Default.WbTwilight
    HaSemanticIcon.COVER -> Icons.Default.Blinds
    HaSemanticIcon.LOCK -> Icons.Default.Lock
    HaSemanticIcon.MEDIA -> Icons.Default.Speaker
    HaSemanticIcon.CAMERA -> Icons.Default.CameraAlt
    HaSemanticIcon.AUTOMATION -> Icons.Default.Mediation
    HaSemanticIcon.SCRIPT, HaSemanticIcon.SCENE -> Icons.Default.PlayArrow
    HaSemanticIcon.TIMER -> Icons.Default.Timer
    HaSemanticIcon.HUMIDITY, HaSemanticIcon.MOISTURE -> Icons.Default.WaterDrop
    HaSemanticIcon.BATTERY -> Icons.Default.BatteryFull
    HaSemanticIcon.VOLTAGE, HaSemanticIcon.CURRENT -> Icons.Default.ElectricBolt
    HaSemanticIcon.POWER, HaSemanticIcon.ENERGY -> Icons.Default.ElectricMeter
    HaSemanticIcon.PRESSURE -> Icons.Default.Speed
    HaSemanticIcon.ILLUMINANCE -> Icons.Default.LightMode
    HaSemanticIcon.DOOR -> Icons.Default.DoorFront
    HaSemanticIcon.WINDOW -> Icons.Default.Window
    HaSemanticIcon.MOTION, HaSemanticIcon.OCCUPANCY -> Icons.Default.MotionPhotosOn
    HaSemanticIcon.SMOKE -> Icons.Default.SmokeFree
    HaSemanticIcon.SPACE -> Icons.Default.Bolt
    HaSemanticIcon.GENERIC -> Icons.Default.DeviceUnknown
}
