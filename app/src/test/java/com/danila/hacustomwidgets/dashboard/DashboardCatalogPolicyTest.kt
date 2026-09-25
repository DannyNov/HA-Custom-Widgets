package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.*
import org.junit.Test

class DashboardCatalogPolicyTest {
    @Test fun missingTimestampUpgradesWithoutSchemaReset() {
        assertTrue(DashboardCatalogPolicy.isDue(0, 1000))
    }
    @Test fun boundaryAndClockRollback() {
        val start = 1_000L
        assertFalse(DashboardCatalogPolicy.isDue(start, start + DashboardCatalogPolicy.MAX_AGE_MS - 1))
        assertTrue(DashboardCatalogPolicy.isDue(start, start + DashboardCatalogPolicy.MAX_AGE_MS))
        assertTrue(DashboardCatalogPolicy.isDue(start, start - 1))
    }
    @Test fun stateTrafficDoesNotExtendCatalogFreshness() {
        val catalogAt = 1_000L
        val now = catalogAt + DashboardCatalogPolicy.MAX_AGE_MS
        assertTrue(DashboardEventPolicy.isSnapshotFresh(now - 1, now))
        assertTrue(DashboardCatalogPolicy.isDue(catalogAt, now))
    }
}
