package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderEdgeIntentPolicyTest {
    private val geometry = List(5) { index -> ReorderItemGeometry(index, index * 100f, 100) }
    private val delay = 150_000_000L

    private fun candidate(direction: Int, at: Long = 1_000L): ReorderEdgeIntent {
        val zone = if (direction > 0) ReorderEdgeZone.BOTTOM else ReorderEdgeZone.TOP
        val neutral = ReorderDragPolicy.initialEdgeIntent(ReorderEdgeZone.NEUTRAL)
        return ReorderDragPolicy.updateEdgeIntent(neutral, zone, direction, at)
    }

    private fun armed(direction: Int, at: Long = 1_000L): ReorderEdgeIntent {
        val zone = if (direction > 0) ReorderEdgeZone.BOTTOM else ReorderEdgeZone.TOP
        return ReorderDragPolicy.advanceEdgeIntent(candidate(direction, at), zone, at + delay, delay)
    }

    @Test fun smallAdjacentDragFromFirstItemDoesNotArmOrRunAway() {
        var intent = ReorderDragPolicy.initialEdgeIntent(ReorderEdgeZone.TOP)
        intent = ReorderDragPolicy.updateEdgeIntent(intent, ReorderEdgeZone.TOP, 1, 1_000L)
        intent = ReorderDragPolicy.updateEdgeIntent(intent, ReorderEdgeZone.NEUTRAL, 1, 2_000L)
        intent = ReorderDragPolicy.advanceEdgeIntent(intent, ReorderEdgeZone.NEUTRAL, delay * 10, delay)

        val onePointerMove = ReorderDragPolicy.adjacentTarget(0, 151f, 1, geometry)
        assertEquals(1, onePointerMove)
        assertEquals(ReorderEdgePhase.NEUTRAL, intent.phase)
        assertEquals(0, ReorderDragPolicy.armedDirection(intent))
        assertEquals(
            0f,
            ReorderDragPolicy.armedVelocityPxPerSecond(intent, 151f, 0f, 500f, 72f, 1f),
            0.001f,
        )
        assertNull(ReorderDragPolicy.adjacentTarget(1, 151f, 0, geometry))
    }

    @Test fun smallAdjacentDragFromLastItemDoesNotArmOrRunAway() {
        var intent = ReorderDragPolicy.initialEdgeIntent(ReorderEdgeZone.BOTTOM)
        intent = ReorderDragPolicy.updateEdgeIntent(intent, ReorderEdgeZone.BOTTOM, -1, 1_000L)
        intent = ReorderDragPolicy.updateEdgeIntent(intent, ReorderEdgeZone.NEUTRAL, -1, 2_000L)
        intent = ReorderDragPolicy.advanceEdgeIntent(intent, ReorderEdgeZone.NEUTRAL, delay * 10, delay)

        assertEquals(3, ReorderDragPolicy.adjacentTarget(4, 349f, -1, geometry))
        assertEquals(0, ReorderDragPolicy.armedDirection(intent))
        assertEquals(
            0f,
            ReorderDragPolicy.armedVelocityPxPerSecond(intent, 349f, 0f, 500f, 72f, 1f),
            0.001f,
        )
    }

    @Test fun intentionalLongDistanceDownArmsAfterDelayAndSurvivesStationaryPointer() {
        val candidate = candidate(1)
        assertEquals(ReorderEdgePhase.CANDIDATE, candidate.phase)
        assertEquals(
            ReorderEdgePhase.CANDIDATE,
            ReorderDragPolicy.advanceEdgeIntent(candidate, ReorderEdgeZone.BOTTOM, delay - 1, delay).phase,
        )
        val armed = ReorderDragPolicy.advanceEdgeIntent(candidate, ReorderEdgeZone.BOTTOM, delay + 1_000L, delay)
        assertEquals(ReorderEdgePhase.ARMED, armed.phase)
        assertTrue(ReorderDragPolicy.armedVelocityPxPerSecond(armed, 490f, 0f, 500f, 72f, 1f) > 0f)
        assertEquals(
            ReorderEdgePhase.ARMED,
            ReorderDragPolicy.advanceEdgeIntent(armed, ReorderEdgeZone.BOTTOM, delay * 10, delay).phase,
        )
    }

    @Test fun intentionalLongDistanceUpArmsAfterDelayAndSurvivesStationaryPointer() {
        val armed = armed(-1)
        assertEquals(ReorderEdgePhase.ARMED, armed.phase)
        assertTrue(ReorderDragPolicy.armedVelocityPxPerSecond(armed, 10f, 0f, 500f, 72f, 1f) < 0f)
        assertEquals(
            ReorderEdgePhase.ARMED,
            ReorderDragPolicy.advanceEdgeIntent(armed, ReorderEdgeZone.TOP, delay * 10, delay).phase,
        )
    }

    @Test fun startingInsideTopEdgeNeverArmsWithoutNeutralReentry() {
        var intent = ReorderDragPolicy.initialEdgeIntent(ReorderEdgeZone.TOP)
        intent = ReorderDragPolicy.updateEdgeIntent(intent, ReorderEdgeZone.TOP, -1, 1_000L)
        intent = ReorderDragPolicy.advanceEdgeIntent(intent, ReorderEdgeZone.TOP, delay * 10, delay)
        assertEquals(ReorderEdgePhase.NEUTRAL, intent.phase)
        assertEquals(0, ReorderDragPolicy.armedDirection(intent))
    }

    @Test fun startingInsideBottomEdgeNeverArmsWithoutNeutralReentry() {
        var intent = ReorderDragPolicy.initialEdgeIntent(ReorderEdgeZone.BOTTOM)
        intent = ReorderDragPolicy.updateEdgeIntent(intent, ReorderEdgeZone.BOTTOM, 1, 1_000L)
        intent = ReorderDragPolicy.advanceEdgeIntent(intent, ReorderEdgeZone.BOTTOM, delay * 10, delay)
        assertEquals(ReorderEdgePhase.NEUTRAL, intent.phase)
        assertEquals(0, ReorderDragPolicy.armedDirection(intent))
    }

    @Test fun neutralReentryAllowsOppositeBottomEdgeToArm() {
        var intent = ReorderDragPolicy.initialEdgeIntent(ReorderEdgeZone.TOP)
        intent = ReorderDragPolicy.updateEdgeIntent(intent, ReorderEdgeZone.NEUTRAL, 1, 1_000L)
        intent = ReorderDragPolicy.updateEdgeIntent(intent, ReorderEdgeZone.BOTTOM, 1, 2_000L)
        intent = ReorderDragPolicy.advanceEdgeIntent(intent, ReorderEdgeZone.BOTTOM, 2_000L + delay, delay)
        assertEquals(ReorderEdgePhase.ARMED, intent.phase)
        assertEquals(1, intent.direction)
    }

    @Test fun reversalImmediatelyStopsArmedBottomIntent() {
        val reversed = ReorderDragPolicy.updateEdgeIntent(
            armed(1), ReorderEdgeZone.BOTTOM, -1, delay * 2,
        )
        assertEquals(ReorderEdgePhase.NEUTRAL, reversed.phase)
        assertEquals(0, ReorderDragPolicy.armedDirection(reversed))
    }

    @Test fun oppositeEdgeDoesNotRetainOldDirection() {
        var intent = armed(1)
        intent = ReorderDragPolicy.updateEdgeIntent(intent, ReorderEdgeZone.NEUTRAL, -1, delay * 2)
        intent = ReorderDragPolicy.updateEdgeIntent(intent, ReorderEdgeZone.TOP, -1, delay * 2 + 1)
        assertEquals(ReorderEdgePhase.CANDIDATE, intent.phase)
        assertEquals(-1, intent.direction)
    }

    @Test fun briefCandidateResetsWhenPointerReturnsToNeutral() {
        val reset = ReorderDragPolicy.updateEdgeIntent(
            candidate(1), ReorderEdgeZone.NEUTRAL, -1, delay / 2,
        )
        assertEquals(ReorderEdgePhase.NEUTRAL, reset.phase)
        assertTrue(reset.neutralSeen)
    }

    @Test fun frameLoopCannotInventIntentFromPointerPosition() {
        val neutral = ReorderDragPolicy.initialEdgeIntent(ReorderEdgeZone.NEUTRAL)
        val afterFrames = ReorderDragPolicy.advanceEdgeIntent(
            neutral, ReorderEdgeZone.BOTTOM, delay * 10, delay,
        )
        assertEquals(ReorderEdgePhase.NEUTRAL, afterFrames.phase)
        assertEquals(0, ReorderDragPolicy.armedDirection(afterFrames))
    }

    @Test fun effectiveEdgeKeepsSubstantialNeutralZoneForShortAndLargeViewports() {
        val heights = listOf(120f, 200f, 240f, 300f, 800f)
        val expectedEdges = listOf(24f, 40f, 48f, 60f, 72f)
        heights.zip(expectedEdges).forEach { (height, expected) ->
            val edge = ReorderDragPolicy.effectiveEdgePx(0f, height, density = 1f)
            assertEquals(expected, edge, 0.001f)
            assertTrue(edge * 2f < height)
            assertEquals(ReorderEdgeZone.NEUTRAL, ReorderDragPolicy.edgeZone(height / 2f, 0f, height, edge))
        }
    }

    @Test fun effectiveEdgeScalesInDpButRemainsTwentyPercentCapped() {
        assertEquals(40f, ReorderDragPolicy.effectiveEdgePx(0f, 200f, density = 4f), 0.001f)
        assertEquals(72f, ReorderDragPolicy.effectiveEdgePx(0f, 1000f, density = 1f), 0.001f)
    }
}
