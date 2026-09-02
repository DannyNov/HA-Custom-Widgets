package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaEntity
import org.junit.Assert.*
import org.junit.Test

class DashboardV0511CorrectivePolicyTest {
    private fun metric(deviceClass: String?, label: String = "Показание") = DashboardMetric(
        "sensor.test", label, "42", "42", "sensor", deviceClass,
    )
    private fun control(id: String, domain: String = "switch") = DashboardControl(id, id, domain, "off")

    @Test fun timerBackReturnsToSameEntityScreen() {
        assertEquals(ConfigScreen.ENTITIES, previousConfigScreen(ConfigScreen.TIMER))
    }

    @Test fun eligibleEntryDoesNotDependOnTimerEnabledOrFavoriteState() {
        val entities = listOf(HaEntity(
            entityId = "switch.dryer", state = "off", friendlyName = "Dryer", unit = null, lastUpdated = null,
        ))
        assertEquals(listOf("switch.dryer"), AutoOffTimerPolicy.controls(entities).map { it.entityId })
        assertFalse(AutoOffTimerConfig().enabled)
    }

    @Test fun oneEligibleControlIsResolvedAutomatically() {
        assertEquals("switch.only", AutoOffTimerPolicy.resolveControl(listOf(control("switch.only")), AutoOffTimerConfig())?.entityId)
    }

    @Test fun selectedControlWinsForMultipleControls() {
        val controls = listOf(control("switch.first"), control("light.second", "light"))
        assertEquals("light.second", AutoOffTimerPolicy.resolveControl(
            controls, AutoOffTimerConfig(controlEntityId = "light.second"),
        )?.entityId)
    }

    @Test fun legacyMultipleControlConfigKeepsBackwardCompatibleFirstControl() {
        val controls = listOf(control("switch.first"), control("light.second", "light"))
        assertEquals("switch.first", AutoOffTimerPolicy.resolveControl(controls, AutoOffTimerConfig())?.entityId)
    }

    @Test fun unsupportedControlIsNeverSelected() {
        assertNull(AutoOffTimerPolicy.resolveControl(listOf(control("button.x", "button")), AutoOffTimerConfig()))
    }

    @Test fun semanticMeasurementsShareIconAndLabelCertainty() {
        val expected = mapOf(
            "temperature" to HaSemanticIcon.TEMPERATURE, "humidity" to HaSemanticIcon.HUMIDITY,
            "battery" to HaSemanticIcon.BATTERY, "voltage" to HaSemanticIcon.VOLTAGE,
            "power" to HaSemanticIcon.POWER, "current" to HaSemanticIcon.CURRENT,
            "energy" to HaSemanticIcon.ENERGY, "pressure" to HaSemanticIcon.PRESSURE,
            "illuminance" to HaSemanticIcon.ILLUMINANCE,
        )
        expected.forEach { (deviceClass, semantic) ->
            val presentation = MetricPresentationPolicy.resolve(metric(deviceClass))
            assertEquals(semantic, presentation.semantic)
            assertFalse(deviceClass, presentation.showLabel)
        }
    }

    @Test fun unknownAndNullDeviceClassRetainFriendlyLabel() {
        assertTrue(MetricPresentationPolicy.resolve(metric(null)).showLabel)
        assertTrue(MetricPresentationPolicy.resolve(metric("future_measurement")).showLabel)
        assertEquals(HaSemanticIcon.SENSOR, MetricPresentationPolicy.resolve(metric("future_measurement")).semantic)
    }

    @Test fun compactSuppressionIsLimitedToSensorMeasurements() {
        val metric = DashboardMetric("binary_sensor.battery", "Battery warning", "off", "off", "binary_sensor", "battery")
        assertTrue(MetricPresentationPolicy.resolve(metric).showLabel)
    }

    @Test fun misleadingFriendlyNameDoesNotChangeSemanticClassification() {
        val result = MetricPresentationPolicy.resolve(metric(null, "Температура батареи 230 V"))
        assertEquals(HaSemanticIcon.SENSOR, result.semantic)
        assertTrue(result.showLabel)
    }

    @Test fun optionalControlIdRoundTripDataModelPreservesExistingFields() {
        val config = AutoOffTimerConfig(
            enabled = true, timerEntityId = "timer.dryer",
            durations = listOf(TimerDurationPreset("stable", 120)), selectedDurationIndex = 0,
            controlEntityId = "switch.dryer",
        )
        assertEquals("timer.dryer", config.timerEntityId)
        assertEquals("stable", config.durations.single().id)
        assertEquals("switch.dryer", config.controlEntityId)
    }
}
