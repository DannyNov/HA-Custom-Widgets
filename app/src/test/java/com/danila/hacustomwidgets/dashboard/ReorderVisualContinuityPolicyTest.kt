package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderVisualContinuityPolicyTest {
    private fun assertFloat(expected: Float, actual: Float) = assertEquals(expected, actual, 0.001f)

    private fun tops(heights: List<Int>): List<Float> {
        var top = 0f
        return heights.map { height -> top.also { top += height } }
    }

    private fun visualTops(heights: List<Int>, source: Int, insertion: Float, progress: Float = 1f): List<Float> {
        val layoutTops = tops(heights)
        return heights.indices.map { index ->
            layoutTops[index] + ReorderGapPolicy.activeTranslation(
                source, index, insertion, heights.size, heights[source].toFloat(), progress,
            )
        }
    }

    @Test fun reportedHonorInteriorDefectClosesFirstSourceSlotCompletely() {
        val visual = visualTops(listOf(100, 100, 100, 100), 0, 2f)
        assertFloat(0f, visual[1])
        assertFloat(100f, visual[2])
        assertFloat(270f, visual[3])
    }

    @Test fun reportedHonorBottomDefectKeepsNextCardAtAbsoluteTop() {
        val visual = visualTops(listOf(100, 100, 100, 100), 0, 3f)
        assertFloat(0f, visual[1])
        assertFloat(100f, visual[2])
        assertFloat(200f, visual[3])
    }

    @Test fun equalHeightDirectionalEndpointsMatchReferenceFormula() {
        assertEquals(listOf(0f, 0f, 170f, 270f), visualTops(listOf(100, 100, 100, 100), 0, 1f))
        assertEquals(listOf(0f, 0f, 100f, 270f), visualTops(listOf(100, 100, 100, 100), 0, 2f))
        assertEquals(listOf(0f, 0f, 100f, 200f), visualTops(listOf(100, 100, 100, 100), 0, 3f))
        assertEquals(listOf(0f, 100f, 100f, 270f), visualTops(listOf(100, 100, 100, 100), 1, 2f))
        assertEquals(listOf(0f, 170f, 200f, 270f), visualTops(listOf(100, 100, 100, 100), 2, 1f))
        assertEquals(listOf(70f, 170f, 270f, 300f), visualTops(listOf(100, 100, 100, 100), 3, 0f))
    }

    @Test fun varyingHeightFirstDownUsesMeasuredSourceHeight() {
        val heights = listOf(60, 180, 80, 140)
        assertEquals(listOf(0f, 0f, 222f, 302f), visualTops(heights, 0, 1f))
        assertEquals(listOf(0f, 0f, 180f, 302f), visualTops(heights, 0, 2f))
        assertEquals(listOf(0f, 0f, 180f, 260f), visualTops(heights, 0, 3f))
    }

    @Test fun varyingHeightMiddleAndTopDirectionsUseSourceHeightOnly() {
        val heights = listOf(60, 180, 80, 140)
        assertEquals(listOf(0f, 60f, 60f, 266f), visualTops(heights, 1, 2f))
        assertEquals(listOf(56f, 116f, 240f, 296f), visualTops(heights, 2, 0f))
        assertEquals(listOf(98f, 158f, 338f, 320f), visualTops(heights, 3, 0f))
    }

    @Test fun activeRetargetIsContinuousAtIntermediateFraction() {
        val atOne = visualTops(listOf(100, 100, 100, 100), 0, 1f)
        val half = visualTops(listOf(100, 100, 100, 100), 0, 1.5f)
        val atTwo = visualTops(listOf(100, 100, 100, 100), 0, 2f)
        assertFloat((atOne[2] + atTwo[2]) / 2f, half[2])
        assertTrue(half[2] in atTwo[2]..atOne[2])
    }

    @Test fun returnToNoopScalesTranslationsContinuously() {
        val full = visualTops(listOf(100, 100, 100), 0, 2f, 1f)
        val half = visualTops(listOf(100, 100, 100), 0, 2f, 0.5f)
        val none = visualTops(listOf(100, 100, 100), 0, 2f, 0f)
        assertFloat((full[1] + none[1]) / 2f, half[1])
        assertEquals(listOf(0f, 100f, 200f), none)
    }

    @Test fun releaseResidualMakesFirstPostCommitFrameIdentical() {
        val pre = mapOf("B" to 0f, "C" to 100f, "A" to 215f, "D" to 270f)
        val post = mapOf("B" to 0f, "C" to 100f, "A" to 200f, "D" to 300f)
        pre.forEach { (id, preTop) ->
            val residual = ReorderCommitPresentationPolicy.residualTranslation(preTop, post.getValue(id))
            assertFloat(preTop, ReorderCommitPresentationPolicy.displayedTop(post.getValue(id), residual, 1f))
            assertFloat(post.getValue(id), ReorderCommitPresentationPolicy.displayedTop(post.getValue(id), residual, 0f))
        }
    }

    @Test fun unfinishedActiveAnimationSnapshotUsesDisplayedNotTargetPosition() {
        val layoutTop = 200f
        val currentTranslation = -42f
        val pre = ReorderCommitPresentationPolicy.screenTop(layoutTop, currentTranslation)
        val post = 100f
        val residual = ReorderCommitPresentationPolicy.residualTranslation(pre, post)
        assertFloat(158f, pre)
        assertFloat(158f, ReorderCommitPresentationPolicy.displayedTop(post, residual, 1f))
    }

    @Test fun delayedAcknowledgementRetainsFrozenPreCommitCoordinates() {
        val frozenTop = 65f
        val oldLayoutTop = 100f
        assertFloat(-35f, ReorderCommitPresentationPolicy.residualTranslation(frozenTop, oldLayoutTop))
        assertFloat(frozenTop, oldLayoutTop + (frozenTop - oldLayoutTop))
    }

    @Test fun immediateAcknowledgementUsesSameResidualTransaction() {
        val preTop = 270f
        val immediatePostTop = 300f
        val residual = ReorderCommitPresentationPolicy.residualTranslation(preTop, immediatePostTop)
        assertFloat(-30f, residual)
        assertFloat(270f, ReorderCommitPresentationPolicy.displayedTop(immediatePostTop, residual, 1f))
    }

    @Test fun ghostReturnAndRealSourceCrossfadeShareOneProgress() {
        assertFloat(180f, ReorderCommitPresentationPolicy.lerp(100f, 200f, 0.8f))
        assertFloat(100f, ReorderCommitPresentationPolicy.lerp(100f, 200f, 0f))
    }

    @Test fun visualAnimationDoesNotMutateLogicalMidpoints() {
        val geometry = listOf(ReorderItemGeometry(0, 0f, 100), ReorderItemGeometry(1, 100f, 100))
        val before = geometry.map { it.midpoint }
        repeat(20) { frame ->
            ReorderGapPolicy.activeTranslation(0, 1, frame / 20f, 2, 100f, 1f)
        }
        assertEquals(before, geometry.map { it.midpoint })
    }
}
