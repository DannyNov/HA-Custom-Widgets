package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class DeferredReorderSimulator(
    ids: List<String>,
    private val sourceIndex: Int,
    private var geometry: List<ReorderItemGeometry>,
) {
    val underlyingIds = ids.toList()
    var insertionIndex = sourceIndex
        private set
    var explicitScrollConsumed = 0f
        private set
    val moveCalls = mutableListOf<Pair<Int, Int>>()

    fun pointer(logicalCenter: Float, direction: Int) {
        insertionIndex = ReorderDragPolicy.adjacentInsertionTarget(
            sourceIndex,
            insertionIndex,
            logicalCenter,
            direction,
            underlyingIds.size,
            geometry,
        ) ?: insertionIndex
    }

    fun edgeFrame(logicalCenter: Float, direction: Int, consumedScroll: Float) {
        explicitScrollConsumed += consumedScroll
        geometry = geometry.map { it.copy(top = it.top - consumedScroll) }
        pointer(logicalCenter, direction)
    }

    fun drop(): List<String> {
        if (insertionIndex == sourceIndex) return underlyingIds
        moveCalls += sourceIndex to insertionIndex
        return moveStable(underlyingIds, sourceIndex, insertionIndex)
    }

    fun cancel(): List<String> = underlyingIds
}

class ReorderDeferredMutationTest {
    private fun equalGeometry(count: Int, height: Int = 100, start: Float = 0f) =
        List(count) { ReorderItemGeometry(it, start + it * height, height) }

    @Test fun firstAdjacentMoveDoesNotMutateKeysOrCascadeBeforeDrop() {
        val ids = List(10) { ('A'.code + it).toChar().toString() }
        val sim = DeferredReorderSimulator(ids, 0, equalGeometry(10))

        sim.pointer(151f, 1)
        repeat(50) { sim.pointer(152f + it * 0.1f, 1) }

        assertEquals(ids, sim.underlyingIds)
        assertEquals(1, sim.insertionIndex)
        assertTrue(sim.moveCalls.isEmpty())
        assertEquals(0f, sim.explicitScrollConsumed, 0f)

        assertEquals(listOf("B", "A") + ids.drop(2), sim.drop())
        assertEquals(listOf(0 to 1), sim.moveCalls)
    }

    @Test fun virtualToUnderlyingMappingMatchesBothDirections() {
        assertEquals(listOf(1, 2, 0, 3), (0..3).map {
            ReorderDragPolicy.virtualToUnderlyingIndex(0, 2, it)
        })
        assertEquals(listOf(0, 3, 1, 2), (0..3).map {
            ReorderDragPolicy.virtualToUnderlyingIndex(3, 1, it)
        })
    }

    @Test fun longDistanceEdgeFramesMoveCursorButCommitExactlyOnce() {
        val ids = List(8) { "id-$it" }
        val sim = DeferredReorderSimulator(ids, 2, equalGeometry(8))
        sim.pointer(351f, 1)
        repeat(4) { sim.edgeFrame(351f, 1, 100f) }

        assertEquals(ids, sim.underlyingIds)
        assertEquals(7, sim.insertionIndex)
        assertTrue(sim.moveCalls.isEmpty())
        assertEquals(400f, sim.explicitScrollConsumed, 0f)

        assertEquals(listOf(2 to 7), sim.apply { drop() }.moveCalls)
    }

    @Test fun differentHeightsUseVirtualNeighborUnderlyingGeometry() {
        val geometry = listOf(
            ReorderItemGeometry(0, 0f, 60),
            ReorderItemGeometry(1, 60f, 180),
            ReorderItemGeometry(2, 240f, 80),
            ReorderItemGeometry(3, 320f, 140),
        )
        val sim = DeferredReorderSimulator(listOf("A", "B", "C", "D"), 0, geometry)
        sim.pointer(151f, 1)
        assertEquals(1, sim.insertionIndex)
        sim.pointer(281f, 1)
        assertEquals(2, sim.insertionIndex)
        assertEquals(listOf("A", "B", "C", "D"), sim.underlyingIds)
    }

    @Test fun partialFirstAndLastItemsStillReachTerminalSlots() {
        val top = DeferredReorderSimulator(listOf("A", "B", "C"), 1, equalGeometry(3, start = -40f))
        top.pointer(9f, -1)
        assertEquals(0, top.insertionIndex)

        val bottom = DeferredReorderSimulator(listOf("A", "B", "C"), 1, equalGeometry(3, start = 20f))
        bottom.pointer(271f, 1)
        assertEquals(2, bottom.insertionIndex)
    }

    @Test fun zeroConsumedScrollDoesNotBlockTerminalCursorMove() {
        val down = DeferredReorderSimulator(listOf("A", "B", "C", "D"), 2, equalGeometry(4))
        down.edgeFrame(351f, 1, 0f)
        assertEquals(3, down.insertionIndex)

        val up = DeferredReorderSimulator(listOf("A", "B", "C", "D"), 1, equalGeometry(4))
        up.edgeFrame(49f, -1, 0f)
        assertEquals(0, up.insertionIndex)
    }

    @Test fun firstAndLastCanTraverseEntireLongListVirtually() {
        val ids = List(12) { "id-$it" }
        val down = DeferredReorderSimulator(ids, 0, equalGeometry(12))
        for (index in 1..11) down.pointer(index * 100f + 51f, 1)
        assertEquals(11, down.insertionIndex)
        assertEquals(ids, down.underlyingIds)

        val up = DeferredReorderSimulator(ids, 11, equalGeometry(12))
        for (index in 10 downTo 0) up.pointer(index * 100f + 49f, -1)
        assertEquals(0, up.insertionIndex)
        assertEquals(ids, up.underlyingIds)
    }

    @Test fun cancelAfterLongDragProducesNoMutation() {
        val ids = List(8) { "id-$it" }
        val sim = DeferredReorderSimulator(ids, 1, equalGeometry(8))
        for (index in 2..6) sim.pointer(index * 100f + 51f, 1)

        assertEquals(ids, sim.cancel())
        assertTrue(sim.moveCalls.isEmpty())
    }

    @Test fun dropAtSourceProducesNoCallback() {
        val ids = listOf("A", "B", "C")
        val sim = DeferredReorderSimulator(ids, 1, equalGeometry(3))

        assertEquals(ids, sim.drop())
        assertTrue(sim.moveCalls.isEmpty())
    }

    @Test fun missingOrUncrossedVirtualNeighborDoesNotMoveCursor() {
        val geometry = equalGeometry(3)
        assertNull(ReorderDragPolicy.adjacentInsertionTarget(0, 1, 249f, 1, 3, geometry))
        assertNull(ReorderDragPolicy.adjacentInsertionTarget(0, 2, 999f, 1, 3, geometry))
        assertNull(ReorderDragPolicy.adjacentInsertionTarget(2, 0, -999f, -1, 3, geometry))
    }
}
