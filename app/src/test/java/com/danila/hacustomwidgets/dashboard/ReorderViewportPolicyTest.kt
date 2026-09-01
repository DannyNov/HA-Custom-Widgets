package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderViewportPolicyTest {
    private fun pending(
        before: List<String> = listOf("A", "B", "C", "D", "E"),
        from: Int = 0,
        to: Int = 1,
        slot: ReorderViewportSlot = ReorderViewportSlot(0, 0),
    ) = PendingViewportRestore(
        draggedId = before[from],
        sourceIndex = from,
        insertionIndex = to,
        beforeOrder = before,
        expectedOrder = ReorderViewportPolicy.expectedOrder(before, from, to),
        slot = slot,
    )

    @Test fun firstVisibleDraggedOneSlotPreservesSlotZero() {
        val plan = pending()
        assertEquals(listOf("B", "A", "C", "D", "E"), plan.expectedOrder)
        assertEquals(ReorderViewportSlot(0, 0), ReorderViewportPolicy.target(plan.slot, 5))
    }

    @Test fun firstVisibleDraggedFarDownStillPreservesSlotZero() {
        val plan = pending(to = 4)
        assertEquals(listOf("B", "C", "D", "E", "A"), plan.expectedOrder)
        assertEquals(0, ReorderViewportPolicy.target(plan.slot, 5)?.index)
    }

    @Test fun partiallyVisibleFirstItemPreservesOffset() {
        val plan = pending(slot = ReorderViewportSlot(0, 37))
        assertEquals(ReorderViewportSlot(0, 37), ReorderViewportPolicy.target(plan.slot, 5))
    }

    @Test fun middleMoveNotCrossingAnchorPreservesSameSlot() {
        val plan = pending(from = 3, to = 4, slot = ReorderViewportSlot(1, 12))
        assertEquals(ReorderViewportSlot(1, 12), ReorderViewportPolicy.target(plan.slot, 5))
    }

    @Test fun anotherItemCrossingFirstVisibleAnchorPreservesIndexNotKey() {
        val plan = pending(from = 4, to = 0, slot = ReorderViewportSlot(2, 8))
        assertEquals(ReorderViewportSlot(2, 8), ReorderViewportPolicy.target(plan.slot, 5))
    }

    @Test fun upwardMoveCrossingAnchorPreservesIndexAndOffset() {
        val plan = pending(from = 4, to = 1, slot = ReorderViewportSlot(2, 21))
        assertEquals(ReorderViewportSlot(2, 21), ReorderViewportPolicy.target(plan.slot, 5))
    }

    @Test fun edgeScrollOffsetImmediatelyBeforeDropIsRetained() {
        val plan = pending(from = 2, to = 4, slot = ReorderViewportSlot(3, 64))
        assertEquals(ReorderViewportSlot(3, 64), ReorderViewportPolicy.target(plan.slot, 5))
    }

    @Test fun varyingItemHeightsDoNotAlterSlotBasedRestorePlan() {
        val slot = ReorderViewportSlot(2, 99)
        assertEquals(slot, ReorderViewportPolicy.target(slot, 5))
    }

    @Test fun nearEndTargetIsClampedOnceForBackfillSafety() {
        assertEquals(ReorderViewportSlot(2, 17), ReorderViewportPolicy.target(ReorderViewportSlot(9, 17), 3))
        assertNull(ReorderViewportPolicy.target(ReorderViewportSlot(0, 0), 0))
    }

    @Test fun unchangedDatasetWaitsForActualMutation() {
        val plan = pending()
        assertEquals(ViewportRestoreDecision.WAIT, ReorderViewportPolicy.decision(plan, plan.beforeOrder))
    }

    @Test fun cancelCreatesNoPendingRestore() {
        assertNull(
            ReorderViewportPolicy.plan(
                false, "A", 0, 1, listOf("A", "B"), ReorderViewportSlot(0, 0),
            ),
        )
    }

    @Test fun noopDropCreatesNoPendingRestore() {
        assertNull(
            ReorderViewportPolicy.plan(
                true, "A", 0, 0, listOf("A", "B"), ReorderViewportSlot(0, 0),
            ),
        )
    }

    @Test fun expectedDatasetAppliesExactlyOnceInController() {
        val plan = pending()
        var restoreCount = 0
        var active: PendingViewportRestore? = plan
        if (ReorderViewportPolicy.decision(plan, plan.expectedOrder) == ViewportRestoreDecision.APPLY) {
            restoreCount++
            active = null
        }
        assertEquals(1, restoreCount)
        assertNull(active)
    }

    @Test fun unexpectedDatasetCancelsInsteadOfRestoringLater() {
        val plan = pending()
        assertEquals(
            ViewportRestoreDecision.CANCEL,
            ReorderViewportPolicy.decision(plan, listOf("A", "C", "B", "D", "E")),
        )
    }

    @Test fun callerSpecificSubsetOrderAppliesWhenDraggedIdReachedInsertionSlot() {
        val plan = pending(before = listOf("A", "X", "B"), from = 0, to = 2)
        assertEquals(
            ViewportRestoreDecision.APPLY,
            ReorderViewportPolicy.decision(plan, listOf("B", "X", "A")),
        )
    }

    @Test fun restoreDecisionNeverCreatesAdditionalOrderMutation() {
        val plan = pending()
        var onMoveCalls = 1
        val decision = ReorderViewportPolicy.decision(plan, plan.expectedOrder)
        assertEquals(ViewportRestoreDecision.APPLY, decision)
        assertEquals(1, onMoveCalls)
        assertTrue(plan.expectedOrder != plan.beforeOrder)
    }
}
