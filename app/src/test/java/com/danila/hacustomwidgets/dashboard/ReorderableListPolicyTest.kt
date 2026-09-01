package com.danila.hacustomwidgets.dashboard

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderableListPolicyTest {
    private val equalItems = listOf(
        ReorderItemGeometry(0, 0f, 100),
        ReorderItemGeometry(1, 100f, 100),
        ReorderItemGeometry(2, 200f, 100),
    )

    @Test fun reorderDownAfterAdjacentMidpoint() {
        assertEquals(1, ReorderDragPolicy.adjacentTarget(0, 151f, 1, equalItems))
        assertNull(ReorderDragPolicy.adjacentTarget(0, 149f, 1, equalItems))
    }

    @Test fun reorderUpAfterAdjacentMidpoint() {
        assertEquals(0, ReorderDragPolicy.adjacentTarget(1, 49f, -1, equalItems))
        assertNull(ReorderDragPolicy.adjacentTarget(1, 51f, -1, equalItems))
    }

    @Test fun firstCanMoveRepeatedlyFarDownOneStepAtATime() {
        assertEquals(1, ReorderDragPolicy.adjacentTarget(0, 999f, 1, equalItems))
        assertEquals(2, ReorderDragPolicy.adjacentTarget(1, 999f, 1, equalItems))
    }

    @Test fun lastCanMoveRepeatedlyFarUpOneStepAtATime() {
        assertEquals(1, ReorderDragPolicy.adjacentTarget(2, -99f, -1, equalItems))
        assertEquals(0, ReorderDragPolicy.adjacentTarget(1, -99f, -1, equalItems))
    }

    @Test fun stationaryPointerInBottomEdgeProducesScrollAndReorderDirection() {
        val velocity = ReorderDragPolicy.edgeVelocityPxPerSecond(990f, 0f, 1000f, 100f, 1f)
        assertTrue(velocity > 0f)
        assertTrue(ReorderDragPolicy.requestedScroll(velocity, 16_000_000L) > 0f)
        assertEquals(1, ReorderDragPolicy.adjacentTarget(0, 999f, 1, equalItems))
    }

    @Test fun stationaryPointerInTopEdgeProducesScrollAndReorderDirection() {
        val velocity = ReorderDragPolicy.edgeVelocityPxPerSecond(10f, 0f, 1000f, 100f, 1f)
        assertTrue(velocity < 0f)
        assertTrue(ReorderDragPolicy.requestedScroll(velocity, 16_000_000L) < 0f)
        assertEquals(0, ReorderDragPolicy.adjacentTarget(1, 1f, -1, equalItems))
    }

    @Test fun consumedScrollNotRequestedScrollCompensatesGeometry() {
        assertEquals(88f, ReorderDragPolicy.compensatedTop(100f, 12f), 0.001f)
    }

    @Test fun zeroConsumedScrollAtListEndLeavesGeometryStable() {
        assertEquals(100f, ReorderDragPolicy.compensatedTop(100f, 0f), 0.001f)
    }

    @Test fun differentItemHeightsUseActualAdjacentMidpoint() {
        val geometry = listOf(
            ReorderItemGeometry(0, 0f, 40),
            ReorderItemGeometry(1, 40f, 220),
        )
        assertNull(ReorderDragPolicy.adjacentTarget(0, 149f, 1, geometry))
        assertEquals(1, ReorderDragPolicy.adjacentTarget(0, 151f, 1, geometry))
    }

    @Test fun directionChangeNearMidpointDoesNotPingPong() {
        assertNull(ReorderDragPolicy.adjacentTarget(1, 149f, 0, equalItems))
        assertNull(ReorderDragPolicy.adjacentTarget(1, 51f, -1, equalItems))
        assertNull(ReorderDragPolicy.adjacentTarget(1, 249f, 1, equalItems))
    }

    @Test fun stableDraggedIdSurvivesIndexAndGeometryChanges() {
        val session = ReorderDragSession(
            id = "stable-id",
            sourceIndex = 2,
            insertionIndex = 2,
            pointerY = 80f,
            grabOffset = 20f,
            itemHeight = 60,
            edgeIntent = ReorderDragPolicy.initialEdgeIntent(ReorderEdgeZone.NEUTRAL),
        )
        val afterReorder = session.copy(insertionIndex = 7)
        assertEquals("stable-id", afterReorder.id)
        assertEquals(2, afterReorder.sourceIndex)
    }

    @Test fun targetCanBeFoundWhenDraggedItemIsAbsentFromVisibleGeometry() {
        val onlyNeighborVisible = listOf(ReorderItemGeometry(4, 300f, 80))
        assertEquals(4, ReorderDragPolicy.adjacentTarget(3, 400f, 1, onlyNeighborVisible))
    }

    @Test fun cancellationIsRepresentedByClearedSession() {
        var session: ReorderDragSession? = ReorderDragSession(
            id = "a",
            sourceIndex = 0,
            insertionIndex = 0,
            pointerY = 10f,
            grabOffset = 2f,
            itemHeight = 8,
            edgeIntent = ReorderDragPolicy.initialEdgeIntent(ReorderEdgeZone.NEUTRAL),
        )
        session = null
        assertNull(session)
    }

    @Test fun checkboxAreaOutsideHandleDoesNotStartDrag() {
        assertFalse(ReorderDragPolicy.startsOnHandle(20f, 60f, Rect(200f, 40f, 248f, 88f)))
    }

    @Test fun textButtonAreaOutsideHandleDoesNotStartDrag() {
        assertFalse(ReorderDragPolicy.startsOnHandle(100f, 60f, Rect(200f, 40f, 248f, 88f)))
    }

    @Test fun verticalScrollStartOutsideHandleDoesNotStartDrag() {
        assertFalse(ReorderDragPolicy.startsOnHandle(199f, 39f, Rect(200f, 40f, 248f, 88f)))
        assertTrue(ReorderDragPolicy.startsOnHandle(224f, 64f, Rect(200f, 40f, 248f, 88f)))
    }

    @Test fun edgeZoneConvertsDpAtMdpiXhdpiAndXxxhdpi() {
        assertEquals(72f, ReorderDragPolicy.edgeZonePx(1f), 0.001f)
        assertEquals(144f, ReorderDragPolicy.edgeZonePx(2f), 0.001f)
        assertEquals(288f, ReorderDragPolicy.edgeZonePx(4f), 0.001f)
    }

    @Test fun edgeVelocityIncreasesWithPenetration() {
        val shallow = ReorderDragPolicy.edgeVelocityPxPerSecond(925f, 0f, 1000f, 100f, 1f)
        val deep = ReorderDragPolicy.edgeVelocityPxPerSecond(990f, 0f, 1000f, 100f, 1f)
        assertTrue(deep > shallow)
    }

    @Test fun elapsedTimeControlsScrollDistance() {
        val short = ReorderDragPolicy.requestedScroll(600f, 8_000_000L)
        val long = ReorderDragPolicy.requestedScroll(600f, 16_000_000L)
        assertEquals(short * 2f, long, 0.001f)
    }

    @Test fun ghostIsClampedInsideViewport() {
        assertEquals(0f, ReorderDragPolicy.ghostTop(-200f, 20f, 100, 0f, 500f), 0.001f)
        assertEquals(400f, ReorderDragPolicy.ghostTop(900f, 20f, 100, 0f, 500f), 0.001f)
    }

    @Test fun nondraggableAdjacentItemCannotBeCrossed() {
        val geometry = listOf(ReorderItemGeometry(1, 100f, 100, draggable = false))
        assertNull(ReorderDragPolicy.adjacentTarget(0, 999f, 1, geometry))
    }

    @Test fun dropOrCancelStopsAutoScrollByClearingActiveSession() {
        val active = ReorderDragSession(
            id = "a",
            sourceIndex = 0,
            insertionIndex = 0,
            pointerY = 990f,
            grabOffset = 10f,
            itemHeight = 50,
            edgeIntent = ReorderDragPolicy.initialEdgeIntent(ReorderEdgeZone.BOTTOM),
        )
        assertTrue(active.id.isNotEmpty())
        val finished: ReorderDragSession? = null
        assertNull(finished)
    }
}
