package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaArea
import com.danila.hacustomwidgets.data.model.HaCatalog
import com.danila.hacustomwidgets.data.model.HaDevice
import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.model.HaFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardV050PolicyTest {
    @Test fun automationIsToggle() = assertEquals(ScenarioActionKind.TOGGLE, scenarioActionKind("automation"))
    @Test fun scriptIsRun() = assertEquals(ScenarioActionKind.RUN, scenarioActionKind("script"))
    @Test fun sceneIsActivate() = assertEquals(ScenarioActionKind.ACTIVATE, scenarioActionKind("scene"))
    @Test fun buttonIsPress() = assertEquals(ScenarioActionKind.PRESS, scenarioActionKind("button"))
    @Test fun unknownScenarioIsUnsupported() = assertEquals(ScenarioActionKind.UNSUPPORTED, scenarioActionKind("vacuum"))

    @Test fun automationOffUsesTurnOn() = assertEquals("turn_on", DashboardOperationPlanner.plan("automation", "off").service)
    @Test fun automationOnUsesTurnOff() = assertEquals("turn_off", DashboardOperationPlanner.plan("automation", "on").service)
    @Test fun automationHasOptimisticDesiredState() = assertEquals("on", DashboardOperationPlanner.plan("automation", "off").optimisticState)
    @Test fun scriptUsesTurnOn() = assertEquals("turn_on", DashboardOperationPlanner.plan("script", "off").service)
    @Test fun scriptHasNoPersistentOptimisticState() = assertEquals(null, DashboardOperationPlanner.plan("script", "off").optimisticState)

    @Test fun entityAreaResolvesToFloorSpace() = assertEquals("floor:f1", ScenarioPolicy.resolveSpaceId("a1", catalog()))
    @Test fun entityAreaOverridesDeviceArea() = assertEquals("area:a2", ScenarioPolicy.resolveSpaceId("a2", "a1", catalog()))
    @Test fun deviceAreaIsFallback() = assertEquals("floor:f1", ScenarioPolicy.resolveSpaceId(null, "a1", catalog()))
    @Test fun standaloneAreaResolvesToAreaSpace() = assertEquals("area:a2", ScenarioPolicy.resolveSpaceId("a2", catalog()))
    @Test fun missingAreaResolvesUnassigned() = assertEquals(HaCatalog.UNASSIGNED_SPACE_ID, ScenarioPolicy.resolveSpaceId(null, catalog()))
    @Test fun unknownAreaResolvesUnassigned() = assertEquals(HaCatalog.UNASSIGNED_SPACE_ID, ScenarioPolicy.resolveSpaceId("missing", catalog()))

    @Test fun savedScenarioOrderIsRespected() = assertEquals(listOf("b", "a"), ScenarioPolicy.orderedIds(listOf("b", "a"), listOf("a", "b")))
    @Test fun newScenarioIdsAreAppended() = assertEquals(listOf("b", "a", "c"), ScenarioPolicy.orderedIds(listOf("b", "a"), listOf("a", "b", "c")))
    @Test fun missingScenarioIdsAreRemoved() = assertEquals(listOf("b"), ScenarioPolicy.orderedIds(listOf("gone", "b"), listOf("b")))
    @Test fun duplicateScenarioIdsAreRemoved() = assertEquals(listOf("a", "b"), ScenarioPolicy.orderedIds(listOf("a", "a"), listOf("a", "b")))

    @Test fun zeroDashboardsIsEmptyDestination() = assertTrue(DashboardSettingsLaunchPolicy.resolve(emptyList()) is DashboardSettingsDestination.Empty)
    @Test fun oneDashboardIsDirectDestination() = assertEquals(DashboardSettingsDestination.Direct(47), DashboardSettingsLaunchPolicy.resolve(listOf(47)))
    @Test fun multipleDashboardsUseChooser() = assertEquals(DashboardSettingsDestination.Choose(listOf(2, 7)), DashboardSettingsLaunchPolicy.resolve(listOf(2, 7)))
    @Test fun duplicateDashboardIdsAreDeduplicated() = assertEquals(DashboardSettingsDestination.Direct(2), DashboardSettingsLaunchPolicy.resolve(listOf(2, 2)))

    @Test fun scenarioTabIsLastByDefault() {
        val config = config()
        val state = DashboardState(config, listOf(DashboardSpace("s", "Дом", listOf("a"))), emptyList(), emptyList(), MAIN_TAB_ID, emptySet(), emptySet(), emptyMap(), 0, false, 0, null)
        assertEquals(SCENARIOS_TAB_ID, state.tabs.last().id)
    }

    @Test fun scenarioTabCanBeHidden() {
        val config = config().copy(scenariosEnabled = false)
        val state = DashboardState(config, emptyList(), emptyList(), emptyList(), MAIN_TAB_ID, emptySet(), emptySet(), emptyMap(), 0, false, 0, null)
        assertFalse(state.tabs.any { it.id == SCENARIOS_TAB_ID })
    }

    @Test fun allRequiredDomainsHaveIcons() {
        val domains = listOf("light", "switch", "sensor", "binary_sensor", "button", "input_button", "input_boolean", "climate", "fan", "cover", "lock", "media_player", "camera", "automation", "script", "scene", "timer")
        assertTrue(domains.all { HaEntityIconPolicy.resolve(it, null) != HaSemanticIcon.GENERIC })
    }

    @Test fun temperatureClassOverridesSensor() = assertEquals(HaSemanticIcon.TEMPERATURE, HaEntityIconPolicy.resolve("sensor", "temperature"))
    @Test fun humidityClassOverridesSensor() = assertEquals(HaSemanticIcon.HUMIDITY, HaEntityIconPolicy.resolve("sensor", "humidity"))
    @Test fun batteryClassOverridesSensor() = assertEquals(HaSemanticIcon.BATTERY, HaEntityIconPolicy.resolve("sensor", "battery"))
    @Test fun doorClassOverridesBinarySensor() = assertEquals(HaSemanticIcon.DOOR, HaEntityIconPolicy.resolve("binary_sensor", "door"))
    @Test fun windowClassOverridesBinarySensor() = assertEquals(HaSemanticIcon.WINDOW, HaEntityIconPolicy.resolve("binary_sensor", "window"))
    @Test fun motionClassOverridesBinarySensor() = assertEquals(HaSemanticIcon.MOTION, HaEntityIconPolicy.resolve("binary_sensor", "motion"))
    @Test fun occupancyClassOverridesBinarySensor() = assertEquals(HaSemanticIcon.OCCUPANCY, HaEntityIconPolicy.resolve("binary_sensor", "occupancy"))
    @Test fun moistureClassOverridesBinarySensor() = assertEquals(HaSemanticIcon.MOISTURE, HaEntityIconPolicy.resolve("binary_sensor", "moisture"))
    @Test fun smokeClassOverridesBinarySensor() = assertEquals(HaSemanticIcon.SMOKE, HaEntityIconPolicy.resolve("binary_sensor", "smoke"))
    @Test fun connectivityClassOverridesBinarySensor() = assertEquals(HaSemanticIcon.CONNECTIVITY, HaEntityIconPolicy.resolve("binary_sensor", "connectivity"))
    @Test fun unknownDomainIsGeneric() = assertEquals(HaSemanticIcon.GENERIC, HaEntityIconPolicy.resolve("future_domain", null))
    @Test fun unknownDeviceClassFallsBackToDomain() = assertEquals(HaSemanticIcon.LIGHT, HaEntityIconPolicy.resolve("light", "future_class"))

    @Test fun lightBeatsBatteryForCard() = assertEquals(HaSemanticIcon.LIGHT, HaEntityIconPolicy.primary(listOf(entity("sensor.b", "battery"), entity("light.a"))))
    @Test fun temperatureBeatsHumidityAndBatteryForCard() = assertEquals(HaSemanticIcon.TEMPERATURE, HaEntityIconPolicy.primary(listOf(entity("sensor.h", "humidity"), entity("sensor.b", "battery"), entity("sensor.t", "temperature"))))
    @Test fun doorBeatsBatteryForCard() = assertEquals(HaSemanticIcon.DOOR, HaEntityIconPolicy.primary(listOf(entity("sensor.b", "battery"), entity("binary_sensor.d", "door"))))
    @Test fun buttonBeatsBatteryForCard() = assertEquals(HaSemanticIcon.BUTTON, HaEntityIconPolicy.primary(listOf(entity("sensor.b", "battery"), entity("button.a"))))
    @Test fun humidityBeatsBatteryForCard() = assertEquals(HaSemanticIcon.HUMIDITY, HaEntityIconPolicy.primary(listOf(entity("sensor.b", "battery"), entity("sensor.h", "humidity"))))
    @Test fun batteryOnlyCardUsesBattery() = assertEquals(HaSemanticIcon.BATTERY, HaEntityIconPolicy.primary(listOf(entity("sensor.b", "battery"))))

    private fun entity(id: String, deviceClass: String? = null) = HaEntity(id, "off", id, null, null, deviceClass = deviceClass)
    private fun catalog() = HaCatalog(
        groups = listOf(HaDeviceGroup(HaDevice("d", "D", areaId = "a1"), listOf(entity("automation.a")))),
        areas = listOf(HaArea("a1", "Room", "f1"), HaArea("a2", "Outside")),
        floors = listOf(HaFloor("f1", "Home")),
    )
    private fun config() = DashboardConfig(1, listOf("s"), emptyMap(), emptyList(), emptyMap(), emptyMap(), true, true)
}
