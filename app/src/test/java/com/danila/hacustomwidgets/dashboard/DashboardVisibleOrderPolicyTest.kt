package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardVisibleOrderPolicyTest {
    private val full = listOf("A", "X", "B", "Y", "C")
    private val visible = listOf("A", "B", "C")

    @Test fun visibleAtoBPreservesHiddenSlots() {
        assertEquals(
            listOf("B", "X", "A", "Y", "C"),
            DashboardOrderPolicy.reorderVisibleSubset(full, visible, 0, 1),
        )
    }

    @Test fun visibleCtoAPreservesHiddenSlots() {
        assertEquals(
            listOf("C", "X", "A", "Y", "B"),
            DashboardOrderPolicy.reorderVisibleSubset(full, visible, 2, 0),
        )
    }

    @Test fun visibleBtoCPreservesHiddenSlots() {
        assertEquals(
            listOf("A", "X", "C", "Y", "B"),
            DashboardOrderPolicy.reorderVisibleSubset(full, visible, 1, 2),
        )
    }

    @Test fun noopReturnsOriginalOrder() {
        assertEquals(full, DashboardOrderPolicy.reorderVisibleSubset(full, visible, 1, 1))
    }

    @Test fun newlyDiscoveredCatalogIdRemainsInMergedSlot() {
        val merged = DashboardOrderPolicy.merge(full, full + "NEW")
        assertEquals(
            listOf("C", "X", "A", "Y", "B", "NEW"),
            DashboardOrderPolicy.reorderVisibleSubset(merged, visible, 2, 0),
        )
    }

    @Test fun staleIdIsPreservedWhenPresentInFullOrder() {
        val withStale = listOf("A", "STALE", "B", "C")
        assertEquals(
            listOf("C", "STALE", "A", "B"),
            DashboardOrderPolicy.reorderVisibleSubset(withStale, visible, 2, 0),
        )
    }

    @Test fun multipleHiddenIncludingFirstAndLastRemainInTheirSlots() {
        val order = listOf("H0", "A", "H1", "B", "C", "H2")
        assertEquals(
            listOf("H0", "C", "H1", "A", "B", "H2"),
            DashboardOrderPolicy.reorderVisibleSubset(order, visible, 2, 0),
        )
    }

    @Test fun invalidVisibleIndicesDoNotNormalizeFullOrder() {
        assertEquals(full, DashboardOrderPolicy.reorderVisibleSubset(full, visible, -1, 2))
        assertEquals(full, DashboardOrderPolicy.reorderVisibleSubset(full, visible, 0, 9))
    }
}
