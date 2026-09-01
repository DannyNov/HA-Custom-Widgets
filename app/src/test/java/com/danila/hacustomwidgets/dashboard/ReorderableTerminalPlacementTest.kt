package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderableTerminalPlacementTest {
    private val equal = List(4) { index -> ReorderItemGeometry(index, index * 100f, 100) }

    private fun target(
        draggedIndex: Int,
        pointerY: Float,
        grabOffset: Float,
        draggedHeight: Int,
        direction: Int,
        geometry: List<ReorderItemGeometry> = equal,
        viewportStart: Float = 0f,
        viewportEnd: Float = 400f,
    ): Pair<ReorderDragCoordinates, Int?> {
        val coordinates = ReorderDragPolicy.dragCoordinates(
            pointerY, grabOffset, draggedHeight, viewportStart, viewportEnd,
        )
        return coordinates to ReorderDragPolicy.adjacentTarget(
            draggedIndex, coordinates.logicalCenter, direction, geometry,
        )
    }

    @Test fun equalHeightOneToZero() {
        assertEquals(0, target(1, -1f, 50f, 100, -1).second)
    }

    @Test fun equalHeightZeroToOne() {
        assertEquals(1, target(0, 201f, 50f, 100, 1).second)
    }

    @Test fun penultimateToLast() {
        assertEquals(3, target(2, 401f, 50f, 100, 1).second)
    }

    @Test fun lastToPenultimate() {
        assertEquals(2, target(3, 199f, 50f, 100, -1).second)
    }

    @Test fun completePathThreeToZeroUsesOnlyAdjacentTargets() {
        val path = listOf(3, 2, 1).map { index -> target(index, -20f, 50f, 100, -1).second }
        assertEquals(listOf(2, 1, 0), path)
    }

    @Test fun completePathZeroToLastUsesOnlyAdjacentTargets() {
        val path = listOf(0, 1, 2).map { index -> target(index, 420f, 50f, 100, 1).second }
        assertEquals(listOf(1, 2, 3), path)
    }

    @Test fun draggedItemAboveFirstTargetCrossesItsMidpoint() {
        assertEquals(0, target(1, -80f, 20f, 180, -1).second)
    }

    @Test fun draggedItemBelowFirstTargetDoesNotMoveUp() {
        assertNull(target(1, 80f, 20f, 180, -1).second)
    }

    @Test fun draggedItemAboveLastTargetDoesNotMoveDown() {
        assertNull(target(2, 300f, 80f, 80, 1).second)
    }

    @Test fun draggedItemBelowLastTargetCrossesItsMidpoint() {
        assertEquals(3, target(2, 460f, 80f, 80, 1).second)
    }

    @Test fun upperContentPaddingDoesNotClampLogicalCenter() {
        val geometry = listOf(
            ReorderItemGeometry(0, 24f, 100),
            ReorderItemGeometry(1, 124f, 100),
        )
        val (coordinates, selected) = target(
            1, 0f, 50f, 100, -1, geometry, viewportStart = 24f, viewportEnd = 424f,
        )
        assertEquals(24f, coordinates.visualTop, 0.001f)
        assertTrue(coordinates.logicalCenter < geometry.first().midpoint)
        assertEquals(0, selected)
    }

    @Test fun lowerContentPaddingDoesNotClampLogicalCenter() {
        val geometry = listOf(
            ReorderItemGeometry(0, 20f, 100),
            ReorderItemGeometry(1, 120f, 100),
        )
        val (coordinates, selected) = target(
            0, 260f, 50f, 100, 1, geometry, viewportStart = 20f, viewportEnd = 240f,
        )
        assertEquals(140f, coordinates.visualTop, 0.001f)
        assertTrue(coordinates.logicalCenter > geometry.last().midpoint)
        assertEquals(1, selected)
    }

    @Test fun zeroConsumedScrollAtTopStillAllowsTerminalMove() {
        val compensated = ReorderDragPolicy.compensatedTop(0f, consumedScroll = 0f)
        assertEquals(0f, compensated, 0.001f)
        assertEquals(0, target(1, -1f, 50f, 100, -1).second)
    }

    @Test fun zeroConsumedScrollAtBottomStillAllowsTerminalMove() {
        val compensated = ReorderDragPolicy.compensatedTop(300f, consumedScroll = 0f)
        assertEquals(300f, compensated, 0.001f)
        assertEquals(3, target(2, 401f, 50f, 100, 1).second)
    }

    @Test fun visualGhostRemainsClampedAtBothEdges() {
        assertEquals(0f, target(1, -200f, 50f, 100, -1).first.visualTop, 0.001f)
        assertEquals(300f, target(2, 600f, 50f, 100, 1).first.visualTop, 0.001f)
    }

    @Test fun logicalCenterCanCrossWhileVisualGhostIsClamped() {
        val top = target(1, -100f, 50f, 100, -1)
        val bottom = target(2, 500f, 50f, 100, 1)
        assertEquals(0f, top.first.visualTop, 0.001f)
        assertTrue(top.first.logicalCenter < equal.first().midpoint)
        assertEquals(0, top.second)
        assertEquals(300f, bottom.first.visualTop, 0.001f)
        assertTrue(bottom.first.logicalCenter > equal.last().midpoint)
        assertEquals(3, bottom.second)
    }

    @Test fun thresholdNotCrossedDoesNotReorder() {
        assertNull(target(1, 99f, 50f, 100, -1).second)
        assertNull(target(2, 301f, 50f, 100, 1).second)
    }

    @Test fun exactTerminalMidpointDoesNotJitter() {
        assertNull(target(1, 50f, 50f, 100, -1).second)
        assertNull(target(2, 350f, 50f, 100, 1).second)
        assertEquals(0, target(1, 49f, 50f, 100, -1).second)
        assertEquals(3, target(2, 351f, 50f, 100, 1).second)
    }
}
