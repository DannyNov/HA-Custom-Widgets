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
    fun unavailableEntityDoesNotChangeActionMapping() {
        val light = entity("light.hall", "Свет", null).copy(state = "unavailable")
        assertEquals(ServiceAction("light", "toggle"), serviceAction(light))
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

    private fun entity(id: String, name: String, deviceClass: String?) = HaEntity(
        entityId = id,
        state = "1",
        friendlyName = name,
        unit = null,
        lastUpdated = null,
        deviceClass = deviceClass,
    )
}
