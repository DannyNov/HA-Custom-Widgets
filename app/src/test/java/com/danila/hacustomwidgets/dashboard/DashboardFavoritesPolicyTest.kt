package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.*
import org.junit.Test

class DashboardFavoritesPolicyTest {
    @Test fun defaultAndFallbackPreserveExistingFavoritesNavigation() {
        assertEquals(MAIN_TAB_ID, DashboardStatePolicy.resolveSelectedTab(null, listOf("room")))
        assertEquals(MAIN_TAB_ID, DashboardStatePolicy.resolveSelectedTab(MAIN_TAB_ID, listOf("room")))
    }
    @Test fun hiddenFavoritesUsesFirstOrdinaryTabAndExcludesItFromNavigation() {
        val ordered = listOf("office", "home", SCENARIOS_TAB_ID)
        assertEquals("office", DashboardStatePolicy.resolveSelectedTab(MAIN_TAB_ID, ordered, false))
        assertEquals("home", DashboardStatePolicy.resolveSelectedTab("home", ordered, false))
        assertEquals("office", DashboardNavigationPolicy.plan("home", MAIN_TAB_ID, ordered, false).targetTabId)
        assertEquals(0, DashboardNavigationPolicy.plan("office", MAIN_TAB_ID, ordered, false).publicationCount)
    }
    @Test fun noOrdinaryTabsUsesExplicitEmptyState() {
        assertEquals(EMPTY_TAB_ID, DashboardStatePolicy.resolveSelectedTab(MAIN_TAB_ID, emptyList(), false))
        assertEquals(EMPTY_TAB_ID, DashboardNavigationPolicy.plan(EMPTY_TAB_ID, MAIN_TAB_ID, emptyList(), false).targetTabId)
    }
}
