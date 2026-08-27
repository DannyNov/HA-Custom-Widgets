package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaArea
import com.danila.hacustomwidgets.data.model.HaCatalog
import com.danila.hacustomwidgets.data.model.HaDevice
import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.model.HaFloor
import com.danila.hacustomwidgets.data.model.HaSpaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardModelsTest {
    @Test
    fun semanticOrderPutsTemperatureHumidityAndBatteryLast() {
        val items = listOf(
            entity("sensor.battery", "Батарея", "battery"),
            entity("sensor.humidity", "Влажность", "humidity"),
            entity("sensor.pressure", "Давление", "pressure"),
            entity("sensor.temperature", "Температура", "temperature"),
        )
        assertEquals(
            listOf("temperature", "humidity", "pressure", "battery"),
            defaultMetricOrder(items).map { it.deviceClass },
        )
    }

    @Test
    fun floorsBecomeSpacesAndAreasBecomeRooms() {
        val device = HaDevice("device", "Датчик", areaId = "living")
        val group = HaDeviceGroup(device, listOf(entity("sensor.temperature", "Температура", "temperature")))
        val catalog = HaCatalog(
            groups = listOf(group),
            areas = listOf(HaArea("living", "Гостиная", "home")),
            floors = listOf(HaFloor("home", "Квартира", 1)),
        )
        val space = catalog.spaces().single()
        assertEquals(HaSpaceKind.FLOOR, space.kind)
        assertEquals("Квартира", space.name)
        assertEquals(listOf("living"), space.areaIds)
    }

    @Test
    fun areasBecomeSpacesWhenFloorsAreAbsent() {
        val catalog = HaCatalog(emptyList(), areas = listOf(HaArea("office", "Офис")))
        assertEquals("Офис", catalog.spaces().single().name)
        assertEquals(HaSpaceKind.AREA, catalog.spaces().single().kind)
    }

    @Test
    fun stateAwareActionsAvoidUnsafeToggleCalls() {
        val lightOn = entity("light.hall", "Свет", null).copy(state = "on")
        val lightOff = lightOn.copy(state = "off")
        assertEquals(ServiceAction("light", "turn_off"), serviceAction(lightOn))
        assertEquals(ServiceAction("light", "turn_on"), serviceAction(lightOff))
    }

    @Test
    fun hiddenEntityCannotBecomeAControl() {
        val hidden = entity("switch.hidden", "Скрытый выключатель", null).copy(hiddenBy = "user")
        assertEquals(null, serviceAction(hidden))
    }

    @Test
    fun entityAreaOverridesDeviceArea() {
        val group = HaDeviceGroup(
            HaDevice("device", "Датчик", areaId = "device_area"),
            listOf(entity("sensor.temperature", "Температура", "temperature").copy(areaId = "entity_area")),
        )
        assertEquals("entity_area", group.effectiveAreaId)
    }

    @Test
    fun syntheticUnassignedGroupsHaveIndependentKeys() {
        val first = HaDeviceGroup(null, listOf(entity("script.one", "Первый", null)), "entity:script.one", "Первый")
        val second = HaDeviceGroup(null, listOf(entity("script.two", "Второй", null)), "entity:script.two", "Второй")
        assertTrue(first.key != second.key)
        assertEquals("Первый", first.title)
    }

    @Test
    fun classificationUsesDeviceClassAndDomain() {
        val opening = HaDeviceGroup(
            HaDevice("door", "Дверь"),
            listOf(entity("binary_sensor.front_door", "Входная дверь", "door")),
        )
        assertEquals(DeviceCategory.OPENINGS, deviceCategory(opening))
        assertTrue(metricIcon(DashboardMetric("sensor.x", "Влажность", "42 %", "42", "sensor", "humidity")) == "💧")
    }

    @Test
    fun batteryThresholdsDistinguishLowAndCritical() {
        fun battery(value: String) = DashboardMetric(
            "sensor.battery", "Батарея", "$value %", value, "sensor", "battery",
        )
        assertEquals(BatteryHealth.NORMAL, batteryHealth(battery("75")))
        assertEquals(BatteryHealth.LOW, batteryHealth(battery("20")))
        assertEquals(BatteryHealth.CRITICAL, batteryHealth(battery("10")))
    }

    private fun entity(id: String, name: String, deviceClass: String?) = HaEntity(
        entityId = id,
        state = "1",
        friendlyName = name,
        unit = null,
        lastUpdated = null,
        deviceClass = deviceClass,
    )
}
