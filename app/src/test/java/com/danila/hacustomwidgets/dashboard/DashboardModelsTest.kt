package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaArea
import com.danila.hacustomwidgets.data.model.HaCatalog
import com.danila.hacustomwidgets.data.model.HaDevice
import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.model.HaFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardModelsTest {
    @Test fun entityWithoutDeviceHasIndependentCardKey() {
        val one = HaDeviceGroup(null, listOf(entity("script.one")), "entity:script.one", "One")
        val two = HaDeviceGroup(null, listOf(entity("script.two")), "entity:script.two", "Two")
        assertNotEquals(one.key, two.key)
    }

    @Test fun multipleControlsOfOneDeviceRemainIndependent() {
        val entities = listOf(entity("light.main"), entity("switch.ambient"))
        assertEquals(2, entities.mapNotNull(::serviceAction).size)
    }

    @Test fun entityAreaOverridesDeviceArea() {
        val group = HaDeviceGroup(HaDevice("d", "Device", areaId = "device"), listOf(entity("sensor.x").copy(areaId = "entity")))
        assertEquals("entity", group.effectiveAreaId)
    }

    @Test fun hiddenAndDisabledEntitiesCannotControl() {
        assertEquals(null, serviceAction(entity("switch.hidden").copy(hiddenBy = "user")))
        assertEquals(null, serviceAction(entity("switch.disabled").copy(disabledBy = "integration")))
    }

    @Test fun mainOnlyDashboardKeepsMainAndDefaultScenariosTab() {
        val config = DashboardConfig(1, emptyList(), emptyMap(), emptyList(), emptyMap(), emptyMap(), true, true)
        val state = DashboardState(
            config, emptyList(), emptyList(), emptyList(), MAIN_TAB_ID,
            emptySet(), emptySet(), emptyMap(), 0, false, 0, null,
        )
        assertEquals(listOf(MAIN_TAB_ID, SCENARIOS_TAB_ID), state.tabs.map { it.id })
    }

    @Test fun catalogKeepsFloorsAndAreas() {
        val catalog = HaCatalog(emptyList(), listOf(HaArea("room", "Room", "floor")), listOf(HaFloor("floor", "Home", 1)))
        assertEquals("Home", catalog.spaces().single().name)
    }

    @Test fun binaryControlNeverUsesToggle() {
        assertTrue(serviceAction(entity("switch.x").copy(state = "on"))?.service == "turn_off")
        assertTrue(serviceAction(entity("switch.x").copy(state = "off"))?.service == "turn_on")
    }

    private fun entity(id: String) = HaEntity(id, "off", id, null, null)
}
