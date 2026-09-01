package com.danila.hacustomwidgets.dashboard

import kotlin.math.sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private data class SimMove(val from: Int, val to: Int)

/** Deterministic model of pointer input, layout acknowledgement and frame-driven scrolling. */
private class ReorderGestureSimulator(
    private val viewportEnd: Float = 200f,
    private val itemHeight: Int = 126,
    private val grabOffset: Float = 37f,
    itemCount: Int = 8,
) {
    private val edge = ReorderDragPolicy.effectiveEdgePx(0f, viewportEnd, 1f)
    private val items = List(itemCount) { ReorderItemGeometry(it, it * itemHeight.toFloat(), itemHeight) }
    private var intent = ReorderDragPolicy.initialEdgeIntent(ReorderEdgeZone.TOP)
    private var pointerY = grabOffset
    private var lastPointerY = pointerY
    private var lastFrameMs = 0L
    private var currentIndex = 0
    private var layoutGeneration = 0
    private var awaitingGeneration: Int? = null
    private var scrollOffset = 0f

    val phases = mutableListOf(ReorderEdgePhase.NEUTRAL)
    val moves = mutableListOf<SimMove>()
    val requestedScroll = mutableListOf<Float>()
    val consumedScroll = mutableListOf<Float>()

    fun pointer(timeMs: Long, y: Float) {
        val direction = (y - lastPointerY).sign.toInt()
        pointerY = y
        val zone = zone()
        intent = ReorderDragPolicy.updateEdgeIntent(
            intent,
            zone,
            direction,
            pointerY,
            movementDeadbandPx = 3f,
            nowNanos = timeMs * 1_000_000L,
        )
        phases += intent.phase
        attemptMove(direction)
        lastPointerY = y
    }

    fun frame(timeMs: Long, generation: Int = layoutGeneration, consumed: Float? = null) {
        layoutGeneration = generation
        if (awaitingGeneration?.let { generation >= it } == true) awaitingGeneration = null
        intent = ReorderDragPolicy.advanceEdgeIntent(
            intent,
            zone(),
            timeMs * 1_000_000L,
            150_000_000L,
        )
        phases += intent.phase
        val direction = ReorderDragPolicy.armedDirection(intent)
        val elapsedSeconds = ((timeMs - lastFrameMs).coerceAtLeast(0L)) / 1_000f
        lastFrameMs = timeMs
        val requested = if (direction == 0) 0f else {
            ReorderDragPolicy.armedVelocityPxPerSecond(intent, pointerY, 0f, viewportEnd, edge, 1f) *
                elapsedSeconds
        }
        if (requested != 0f) {
            requestedScroll += requested
            val actual = consumed ?: requested
            consumedScroll += actual
            scrollOffset += actual
            attemptMove(direction)
        }
    }

    fun release() {
        intent = ReorderEdgeIntent()
        phases += intent.phase
    }

    fun index() = currentIndex

    private fun zone() = ReorderDragPolicy.edgeZone(pointerY, 0f, viewportEnd, edge)

    private fun attemptMove(direction: Int) {
        if (direction == 0 || awaitingGeneration != null) return
        val logicalCenter = pointerY - grabOffset + itemHeight / 2f
        val scrolledGeometry = items.map { it.copy(top = it.top - scrollOffset) }
        val target = ReorderDragPolicy.adjacentTarget(currentIndex, logicalCenter, direction, scrolledGeometry)
            ?: return
        moves += SimMove(currentIndex, target)
        currentIndex = target
        awaitingGeneration = layoutGeneration + 1
    }
}

class ReorderStationaryDwellSimulatorTest {
    private fun regressionPrefix(sim: ReorderGestureSimulator) {
        sim.pointer(0, 37f)
        sim.pointer(40, 50f)
        sim.pointer(120, 130f)
        sim.pointer(200, 161f)
        sim.frame(210)
        sim.pointer(220, 164f)
        sim.frame(230, generation = 1)
        sim.pointer(260, 170f)
        sim.frame(280, generation = 1)
        sim.pointer(300, 176f)
        sim.frame(330, generation = 1)
        sim.pointer(350, 180f)
    }

    @Test fun movingThroughEdgeProducesOneAdjacentMoveAndNeverArms() {
        val sim = ReorderGestureSimulator()
        regressionPrefix(sim)
        sim.release()

        assertEquals(listOf(SimMove(0, 1)), sim.moves)
        assertTrue(sim.requestedScroll.isEmpty())
        assertFalse(sim.phases.contains(ReorderEdgePhase.ARMED))
    }

    @Test fun samePrefixThenStationaryHoldArmsAndContinuesLongDistanceReorder() {
        val sim = ReorderGestureSimulator()
        regressionPrefix(sim)
        var generation = 1
        for (time in 400L..1_100L step 50) {
            sim.frame(time, generation = generation, consumed = 13f)
            generation++
        }

        assertTrue(sim.phases.contains(ReorderEdgePhase.ARMED))
        assertTrue(sim.requestedScroll.isNotEmpty())
        assertTrue(sim.moves.size > 1)
        assertTrue(sim.index() > 1)
    }

    @Test fun slowCumulativeMovementResetsDwellAgainstAnchor() {
        val sim = ReorderGestureSimulator()
        sim.pointer(0, 100f)
        sim.pointer(10, 161f)
        var y = 161f
        for (time in 60L..510L step 50) {
            y += 1f
            sim.pointer(time, y)
            sim.frame(time + 20)
        }

        assertFalse(sim.phases.contains(ReorderEdgePhase.ARMED))
        assertTrue(sim.requestedScroll.isEmpty())
    }

    @Test fun microJitterDoesNotPreventStationaryDwell() {
        val sim = ReorderGestureSimulator()
        sim.pointer(0, 100f)
        sim.pointer(10, 180f)
        sim.pointer(50, 181f)
        sim.pointer(90, 179f)
        sim.pointer(130, 182f)
        sim.frame(160)

        assertTrue(sim.phases.contains(ReorderEdgePhase.ARMED))
        assertTrue(sim.requestedScroll.isNotEmpty())
    }

    @Test fun meaningfulInwardReversalImmediatelyStopsArmedScroll() {
        val sim = ReorderGestureSimulator()
        sim.pointer(0, 100f)
        sim.pointer(10, 180f)
        sim.frame(160)
        val scrollsBefore = sim.requestedScroll.size
        sim.pointer(170, 176f)
        sim.frame(200)

        assertEquals(scrollsBefore, sim.requestedScroll.size)
        assertEquals(ReorderEdgePhase.NEUTRAL, sim.phases.last())
    }

    @Test fun layoutAcknowledgementBlocksCascadeUntilArmed() {
        val sim = ReorderGestureSimulator()
        sim.pointer(0, 37f)
        sim.pointer(100, 130f)
        sim.pointer(200, 164f)
        sim.pointer(210, 190f)
        assertEquals(listOf(SimMove(0, 1)), sim.moves)

        sim.frame(220, generation = 0)
        assertEquals(listOf(SimMove(0, 1)), sim.moves)
        sim.frame(230, generation = 1)
        assertEquals(listOf(SimMove(0, 1)), sim.moves)
        assertFalse(sim.phases.contains(ReorderEdgePhase.ARMED))
    }

    @Test fun terminalTargetsRemainReachableWhenScrollConsumesNothing() {
        val geometry = List(4) { ReorderItemGeometry(it, it * 100f, 100) }
        assertEquals(0, ReorderDragPolicy.adjacentTarget(1, 49f, -1, geometry))
        assertEquals(3, ReorderDragPolicy.adjacentTarget(2, 351f, 1, geometry))
    }
}
