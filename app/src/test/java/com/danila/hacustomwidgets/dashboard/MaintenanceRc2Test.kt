package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class MaintenanceRc2Test {
    private fun entity(name: String) = HaEntity("sensor.battery", "49", name, "%", null, deviceId = "d", deviceClass = "battery")
    @Test fun physicalBatteryNames() {
        for ((raw, expected) in listOf("Кнопка коридор Батарея" to "Кнопка коридор", "Выключатель ванная Батарея" to "Выключатель ванная",
            "Honor Magic 5Pro PGT-N19 Battery level" to "Honor Magic 5Pro PGT-N19", "Device Battery" to "Device",
            "Датчик Уровень заряда батареи" to "Датчик")) assertEquals(expected, MaintenancePolicy.batteryTitle(raw))
    }
    @Test fun interiorUserWordsAndLabelOnlyArePreserved() {
        for (name in listOf("Battery room switch", "Батарея отопления", "My battery station", "Battery", "Батарея", "Device Battery-powered", "Device battery room"))
            assertEquals(name, MaintenancePolicy.batteryTitle(name))
    }
    @Test fun deviceRegistryNameHasPriorityAndIsNeverStripped() {
        val e = entity("Honor Battery level")
        val row = MaintenancePolicy.batteries(HaCatalog(listOf(HaDeviceGroup(HaDevice("d", "My Battery"), listOf(e))))).single()
        assertEquals("My Battery", row.title); assertEquals(e, row.entity)
        val restored = MaintenancePolicy.parseBatteries(MaintenancePolicy.batteriesJson(listOf(row))).single()
        assertEquals(row.title, restored.title); assertEquals(row.deviceKey, restored.deviceKey)
        assertEquals(row.metric, restored.metric); assertEquals(row.attention, restored.attention)
    }
    @Test fun missingBlankIdentifierAndMismatchedDevicesUseFallback() {
        for (device in listOf(null, HaDevice("d", ""), HaDevice("d", "d"), HaDevice("other", "Wrong device"))) {
            val row = MaintenancePolicy.batteries(HaCatalog(listOf(HaDeviceGroup(device, listOf(entity("Honor Battery level")))))).single()
            assertEquals("Honor", row.title)
        }
    }
    @Test fun legacyCacheWithoutDeviceNameStillHasSafeTitle() {
        val old = JSONArray().put(MaintenancePolicy.entityJson(entity("Кнопка коридор Батарея")).put("device", "d"))
        assertEquals("Кнопка коридор", MaintenancePolicy.parseBatteries(old).single().title)
    }
    @Test fun systemTabOrderAndCyclicNavigationAllVisibilityCombinations() {
        for (favorites in listOf(false,true)) for (scenarios in listOf(false,true)) for (maintenance in listOf(false,true)) for (rooms in listOf(false,true)) {
            val config = DashboardConfig(1, if (rooms) listOf("a","b") else emptyList(), emptyMap(), emptyList(), emptyMap(), emptyMap(), false, true, showFavorites = favorites, scenariosEnabled = scenarios, showMaintenance = maintenance)
            val state = DashboardState(config, listOf(DashboardSpace("a","A",emptyList()),DashboardSpace("b","B",emptyList())), emptyList(),emptyList(),"a",emptySet(),emptySet(),emptyMap(),0,false,0,null)
            val expected = listOfNotNull(MAIN_TAB_ID.takeIf { favorites }) + (if (rooms) listOf("a","b") else emptyList()) + listOfNotNull(SCENARIOS_TAB_ID.takeIf { scenarios }, MAINTENANCE_TAB_ID.takeIf { maintenance })
            val ids = state.tabs.map { it.id }
            assertEquals(expected.ifEmpty { listOf(EMPTY_TAB_ID) },ids)
            for (i in ids.indices) {
                for (delta in listOf(-1,1)) {
                    val next = ids[(i+delta+ids.size)%ids.size]
                    assertEquals(next,DashboardNavigationPolicy.plan(ids[i],next,ids,favorites).targetTabId)
                }
                if (maintenance) assertEquals(MAINTENANCE_TAB_ID,DashboardNavigationPolicy.plan(ids[i],MAINTENANCE_TAB_ID,ids,favorites).targetTabId)
            }
            for (hidden in listOf(MAIN_TAB_ID,SCENARIOS_TAB_ID,MAINTENANCE_TAB_ID).filterNot { it in ids }) {
                assertEquals(ids.first(),state.copy(selectedTabId = hidden).selectedTab.id)
                assertEquals(ids.first(),DashboardNavigationPolicy.plan(hidden,hidden,ids,favorites).targetTabId)
            }
        }
    }
}
