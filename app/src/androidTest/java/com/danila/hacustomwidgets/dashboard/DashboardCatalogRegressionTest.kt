package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.data.WidgetRepository
import com.danila.hacustomwidgets.data.model.*
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.security.SecureConnectionStore
import com.danila.hacustomwidgets.widget.EntityWidgetRenderCoordinator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DashboardCatalogRegressionTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(): Context = object : ContextWrapper(base) {
        private val prefix = "catalog-${UUID.randomUUID()}-"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    }
    private fun entity(id: String, name: String = id, area: String? = null) =
        HaEntity(id, "on", name, null, "2026-09-22T00:00:00Z", areaId = area)
    private fun group(id: String, name: String = id, area: String = "a") =
        HaDeviceGroup(HaDevice(id, name, areaId = area), listOf(entity("switch.$id")))
    private fun catalog(vararg groups: HaDeviceGroup) = HaCatalog(groups.toList(), listOf(HaArea("a", "Room A"), HaArea("b", "Room B")))
    private fun config(id: Int) = DashboardConfig(id, listOf("area:a", "area:b"),
        mapOf("area:a" to DashboardGrouping.NONE), listOf("old"),
        mapOf("old" to listOf("switch.old")), mapOf("area:a" to listOf("old", "hidden")), false, true,
        hiddenDeviceIdsByContext = mapOf("area:a" to listOf("hidden")),
        hiddenEntityIdsByContext = mapOf("area:a" to listOf("sensor.hidden")),
        autoOffTimersByDevice = mapOf("old" to AutoOffTimerConfig(enabled = true, timerEntityId = "timer.old", controlEntityId = "switch.old")))
    private class Fixture(val context: Context, initial: HaCatalog, config: DashboardConfig) {
        val repo = DashboardRepository(context)
        var remote = initial
        var catalogs = 0
        var states = 0
        var fail = false
        val coordinator: DashboardEventCoordinator
        init {
            repo.saveConfiguration(config, initial)
            val connection = SecureConnectionStore(context).also { it.save("https://catalog-test.invalid", "fixture-token") }
            coordinator = DashboardEventCoordinator(context, connection, HomeAssistantClient(), repo,
                WidgetRepository(context), EntityWidgetRenderCoordinator(context),
                fetchCatalog = { catalogs++; if (fail) error("fixture failure"); remote },
                fetchEntities = { _, ids -> states++; remote.groups.flatMap { it.entities }.filter { it.entityId in ids } })
        }
        fun expire(id: Int) {
            val prefs = context.getSharedPreferences("dashboard_structure", Context.MODE_PRIVATE)
            val key = prefs.all.keys.single { it.contains(id.toString()) }
            val json = JSONObject(prefs.getString(key, null)!!).put("catalog_updated_at", 1L)
            prefs.edit().putString(key, json.toString()).commit()
        }
        suspend fun sync() = coordinator.reconcileNow("PERIODIC_WORK", force = true, source = DashboardStateSource.PERIODIC_REFRESH)
    }
    @Test fun automaticAdditionAndRenamePreserveConfigurationAndTab() = runBlocking {
        val f = Fixture(isolated(), catalog(group("old"), group("hidden")), config(801))
        f.repo.setSelectedTab(801, "area:a")
        val before = f.repo.getConfig(801)
        f.remote = catalog(group("old", "Renamed"), group("hidden"), group("new"))
        f.expire(801)
        assertTrue(f.sync())
        val state = f.repo.get(801)!!
        assertEquals("Renamed", state.cards.first { it.key == "old" }.title)
        assertNotNull(DashboardCustomizationPolicy.presentCard(state.config, "area:a", state.cards.first { it.key == "new" }))
        assertNull(DashboardCustomizationPolicy.presentCard(state.config, "area:a", state.cards.first { it.key == "hidden" }))
        assertEquals(before, state.config)
        assertEquals("area:a", state.selectedTabId)
        assertEquals(1, f.catalogs)
        assertEquals(0, f.states)
        assertEquals(state.config, DashboardRepository(f.context).get(801)!!.config)
    }
    @Test fun deletionMoveAreaRenameAndReappearance() = runBlocking {
        val f = Fixture(isolated(), catalog(group("old"), group("gone")), config(802))
        f.repo.setSelectedTab(802, "area:a")
        val before = f.repo.getConfig(802)
        f.remote = HaCatalog(listOf(group("old", area = "b")), listOf(HaArea("b", "Renamed Room")))
        f.expire(802); assertTrue(f.sync())
        assertEquals(listOf("old"), f.repo.get(802)!!.cards.map { it.key })
        assertEquals("b", f.repo.get(802)!!.cards.single().areaId)
        assertEquals("Renamed Room", f.repo.get(802)!!.cards.single().roomName)
        assertFalse("switch.gone" in f.repo.entityIds(802))
        assertEquals(before, f.repo.getConfig(802))
        f.remote = catalog(group("old"), group("gone"))
        f.expire(802); assertTrue(f.sync())
        assertEquals(before, f.repo.getConfig(802))
        assertTrue(f.repo.get(802)!!.tabs.any { it.id == "area:a" })
        assertEquals("area:a", f.repo.get(802)!!.selectedTabId)
    }
    @Test fun scenesScriptsAutomationsAndEntityAreaChanges() = runBlocking {
        val f = Fixture(isolated(), catalog(group("old")), config(803))
        val scenarios = listOf("automation", "script", "scene").map {
            HaDeviceGroup(null, listOf(entity("$it.new", "New $it", "b")), "entity:$it.new", "New $it")
        }
        f.remote = catalog(group("old").copy(entities = listOf(entity("switch.old", "Renamed entity", "b"))), *scenarios.toTypedArray())
        f.expire(803); assertTrue(f.sync())
        val state = f.repo.get(803)!!
        assertEquals(3, state.scenarioActions.size)
        assertTrue(state.scenarioActions.all { it.spaceId == "area:b" })
        assertEquals("b", state.cards.single().areaId)
        assertEquals("Renamed entity", state.cards.single().controls.single().label)
        f.remote = catalog(group("old"))
        f.expire(803); assertTrue(f.sync())
        assertTrue(f.repo.get(803)!!.scenarioActions.isEmpty())
    }
    @Test fun stateOnlyReconciliationAndEventsStayLightAndFailureRetries() = runBlocking {
        val f = Fixture(isolated(), catalog(group("old")), config(804))
        repeat(20) { f.repo.updateEntityStates(804, listOf(entity("switch.old")), DashboardStateSource.EVENT) }
        assertTrue(f.sync())
        assertEquals(0, f.catalogs)
        assertEquals(1, f.states)
        f.expire(804); f.fail = true
        assertFalse(f.sync())
        assertTrue(f.repo.requiresCatalogRefresh(804))
        assertEquals("old", f.repo.get(804)!!.cards.single().key)
        f.fail = false; assertTrue(f.sync())
        assertFalse(f.repo.requiresCatalogRefresh(804))
    }
    @Test fun v061SnapshotWithoutTimestampRefreshesAndMultipleWidgetsShareFetch() = runBlocking {
        val f = Fixture(isolated(), catalog(group("old")), config(805))
        f.repo.saveConfiguration(config(806), f.remote)
        val prefs = f.context.getSharedPreferences("dashboard_structure", Context.MODE_PRIVATE)
        prefs.all.forEach { (key, value) ->
            val json = JSONObject(value as String).also { it.remove("catalog_updated_at") }
            prefs.edit().putString(key, json.toString()).commit()
        }
        val before = f.repo.all()
        assertTrue(f.sync())
        assertEquals(1, f.catalogs)
        assertEquals(before, f.repo.all())
        assertFalse(f.repo.requiresCatalogRefresh(805))
        assertFalse(f.repo.requiresCatalogRefresh(806))
    }
    @Test fun manualRefreshBypassesFreshCatalogAndMissingTimerIsNotPolledForever() = runBlocking {
        val initial = catalog(group("old").copy(entities = listOf(entity("switch.old"), entity("timer.old"))))
        val f = Fixture(isolated(), initial, config(807))
        assertTrue("timer.old" in f.repo.entityIds(807))
        f.remote = catalog(group("old", "New name"))
        assertTrue(f.coordinator.reconcileNow("MANUAL", force = true, source = DashboardStateSource.MANUAL_REFRESH))
        assertEquals(1, f.catalogs)
        assertEquals("New name", f.repo.get(807)!!.cards.single().title)
        assertFalse("timer.old" in f.repo.entityIds(807))
        assertNotNull(f.repo.getConfig(807)!!.autoOffTimersByDevice["old"])
    }
    @Test fun floorRenameAndMoveAreReflectedWithoutLosingPreferences() = runBlocking {
        val initial = HaCatalog(listOf(group("old")), listOf(HaArea("a", "Room", "f")), listOf(HaFloor("f", "Floor")))
        val cfg = config(808).copy(visibleSpaceIds = listOf("floor:f"), spaceOrderIds = listOf("floor:f"))
        val f = Fixture(isolated(), initial, cfg)
        f.remote = initial.copy(floors = listOf(HaFloor("f", "Renamed floor")))
        f.expire(808); assertTrue(f.sync())
        assertEquals("Renamed floor", f.repo.get(808)!!.spaces.single().name)
        assertEquals(cfg, f.repo.getConfig(808))
    }
    @Test fun renameRetainsDefaultCardPositionWithoutAnExplicitUserOrder() = runBlocking {
        val cfg = config(809).copy(cardOrderBySpace = emptyMap())
        val f = Fixture(isolated(), catalog(group("old", "A"), group("other", "B")), cfg)
        assertEquals(listOf("old", "other"), f.repo.get(809)!!.cards.map { it.key })
        // getCatalog returns groups sorted by their current names, reversing these two cards.
        f.remote = catalog(group("other", "B"), group("old", "Z"), group("new", "C"))
        f.expire(809); assertTrue(f.sync())
        assertEquals(listOf("old", "other", "new"), f.repo.get(809)!!.cards.map { it.key })
        assertEquals(listOf("old", "other", "new"), f.repo.get(809)!!.cards.orderedBy(null).map { it.key })
        assertEquals(listOf("other", "old", "new"), f.repo.get(809)!!.cards.orderedBy(listOf("other")).map { it.key })
        assertEquals(cfg, f.repo.getConfig(809))
        assertEquals(listOf("old", "other", "new"), DashboardRepository(f.context).get(809)!!.cards.map { it.key })
    }
}
