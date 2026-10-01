package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.data.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DashboardFavoritesHostTest {
    private fun isolated(): Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        private val prefix = "favorites-${UUID.randomUUID()}-"
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    }
    private val catalog = HaCatalog(listOf(HaDeviceGroup(HaDevice("device", "Socket", areaId = "room"),
        listOf(HaEntity("switch.socket", "on", "Socket", null, "2026-09-30T00:00:00Z")))), listOf(HaArea("room", "Room")))
    private fun config(id: Int) = DashboardConfig(id, listOf("area:room"), emptyMap(), listOf("device"),
        emptyMap(), emptyMap(), false, true)

    @Test fun upgradeDefaultIsTrueWithoutRewritingOrLosingFavorites() {
        val c = isolated(); val repo = DashboardRepository(c); repo.saveConfiguration(config(801), catalog)
        val prefs = c.getSharedPreferences("dashboard_widgets", 0)
        val key = "dashboard_801_config"
        val legacy = JSONObject(prefs.getString(key, null)!!).apply { remove("show_favorites") }.toString()
        assertTrue(prefs.edit().putString(key, legacy).commit())
        val loaded = DashboardRepository(c)
        assertTrue(loaded.getConfig(801)!!.showFavorites)
        assertEquals(listOf("device"), loaded.getConfig(801)!!.favoriteDeviceKeys)
        assertEquals(MAIN_TAB_ID, loaded.get(801)!!.selectedTabId)
        assertEquals(legacy, prefs.getString(key, null))
    }

    @Test fun threeWidgetsHideShowNavigationAndReloadAreIndependent() {
        val c = isolated(); var repo = DashboardRepository(c)
        for (id in 811..813) repo.saveConfiguration(config(id), catalog)
        repo.saveConfiguration(config(811).copy(showFavorites = false), catalog)
        repo.saveConfiguration(config(813).copy(showFavorites = false), catalog)
        for (id in listOf(811, 813)) {
            assertEquals("area:room", repo.get(id)!!.selectedTabId)
            assertFalse(repo.get(id)!!.tabs.any { it.id == MAIN_TAB_ID })
            assertEquals("area:room", c.getSharedPreferences("dashboard_widgets", 0).getString("dashboard_${id}_selected_tab", null))
            repo.setSelectedTab(id, SCENARIOS_TAB_ID)
            repo.setSelectedTab(id, MAIN_TAB_ID) // stale click cannot select the hidden tab
            assertEquals("area:room", repo.get(id)!!.selectedTabId)
        }
        assertTrue(c.getSharedPreferences("dashboard_widgets", 0).edit().commit())
        repo = DashboardRepository(c)
        for (id in 811..813) {
            assertEquals(id == 812, repo.getConfig(id)!!.showFavorites)
            assertEquals(listOf("device"), repo.getConfig(id)!!.favoriteDeviceKeys)
            repo.updateFromCatalog(id, catalog)
            assertEquals(id == 812, repo.getConfig(id)!!.showFavorites)
        }
        repo.saveConfiguration(repo.getConfig(811)!!.copy(showFavorites = true), catalog)
        repo.setSelectedTab(811, MAIN_TAB_ID)
        assertEquals(listOf("device"), repo.getConfig(811)!!.favoriteDeviceKeys)
        assertEquals(MAIN_TAB_ID, repo.get(811)!!.selectedTabId)
        assertEquals(listOf("device"), dashboardSections(repo.get(811)!!).flatMap { it.cards }.map { it.key })
        assertFalse(repo.getConfig(813)!!.showFavorites)
        repeat(6) {
            val state = repo.get(813)!!
            val ids = state.tabs.map { it.id }
            repo.setSelectedTab(813, ids[(ids.indexOf(state.selectedTabId) + 1) % ids.size])
            assertNotEquals(MAIN_TAB_ID, repo.get(813)!!.selectedTabId)
        }
    }

    @Test fun fallbackFollowsSavedOrderAndNoTabsShowsExplicitEmptyState() {
        val c = isolated(); val repo = DashboardRepository(c)
        repo.saveConfiguration(config(821).copy(visibleSpaceIds = emptyList(), scenariosEnabled = false), catalog)
        repo.saveConfiguration(repo.getConfig(821)!!.copy(showFavorites = false), catalog)
        assertEquals(EMPTY_TAB_ID, repo.get(821)!!.selectedTabId)
        assertEquals(listOf(EMPTY_TAB_ID), repo.get(821)!!.tabs.map { it.id })
        assertEquals(listOf("device"), repo.getConfig(821)!!.favoriteDeviceKeys)
        repo.saveConfiguration(repo.getConfig(821)!!.copy(showFavorites = true), catalog)
        assertEquals(MAIN_TAB_ID, repo.get(821)!!.selectedTabId)
        val orderedCatalog = catalog.copy(areas = listOf(HaArea("room", "Room"), HaArea("office", "Office")))
        repo.saveConfiguration(config(822).copy(visibleSpaceIds = listOf("area:room", "area:office"),
            spaceOrderIds = listOf("area:office", "area:room")), orderedCatalog)
        repo.saveConfiguration(repo.getConfig(822)!!.copy(showFavorites = false), orderedCatalog)
        assertEquals("area:office", repo.get(822)!!.selectedTabId)
        assertEquals(listOf("area:office", "area:room", SCENARIOS_TAB_ID), repo.get(822)!!.tabs.map { it.id })
    }
}
