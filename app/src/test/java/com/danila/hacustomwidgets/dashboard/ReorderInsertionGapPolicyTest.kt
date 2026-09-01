package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderInsertionGapPolicyTest {
    private fun assertFloat(expected: Float, actual: Float) = assertEquals(expected, actual, 0.001f)

    @Test fun noopHasNoGap() {
        assertFloat(0f, ReorderGapPolicy.targetGapHeight(0, 0, 126f))
    }

    @Test fun gapHeightIsSeventyPercentOfDraggedCard() {
        assertFloat(70f, ReorderGapPolicy.gapHeight(100f))
        assertFloat(88.2f, ReorderGapPolicy.gapHeight(126f))
        assertFloat(140f, ReorderGapPolicy.gapHeight(200f))
    }

    @Test fun virtualMappingSupportsFirstMovingDown() {
        assertEquals(listOf(2, 0, 1, 3), (0..3).map {
            ReorderGapPolicy.underlyingToVirtualIndex(0, 2, it)
        })
    }

    @Test fun virtualMappingSupportsLastMovingUp() {
        assertEquals(listOf(0, 2, 3, 1), (0..3).map {
            ReorderGapPolicy.underlyingToVirtualIndex(3, 1, it)
        })
    }

    @Test fun animatedVirtualMappingIsContinuousAcrossAdjacentSlots() {
        assertFloat(1f, ReorderGapPolicy.underlyingToAnimatedVirtualIndex(0, 0f, 1))
        assertFloat(0.5f, ReorderGapPolicy.underlyingToAnimatedVirtualIndex(0, 0.5f, 1))
        assertFloat(0f, ReorderGapPolicy.underlyingToAnimatedVirtualIndex(0, 1f, 1))

        assertFloat(2f, ReorderGapPolicy.underlyingToAnimatedVirtualIndex(3, 3f, 2))
        assertFloat(2.5f, ReorderGapPolicy.underlyingToAnimatedVirtualIndex(3, 2.5f, 2))
        assertFloat(3f, ReorderGapPolicy.underlyingToAnimatedVirtualIndex(3, 2f, 2))
    }

    @Test fun middleDownOpensGapAroundVirtualInsertionSlot() {
        val offsets = (0..4).map {
            ReorderGapPolicy.activeTranslation(1, it, 3f, 5, 100f, 1f)
        }
        assertEquals(listOf(0f, 0f, -100f, -100f, -30f), offsets)
    }

    @Test fun middleUpOpensGapAroundVirtualInsertionSlot() {
        val offsets = (0..4).map {
            ReorderGapPolicy.activeTranslation(3, it, 1f, 5, 100f, 1f)
        }
        assertEquals(listOf(0f, 70f, 70f, 0f, -30f), offsets)
    }

    @Test fun terminalFirstSlotUsesFullInViewportGap() {
        val offsets = (0..3).filter { it != 3 }.map {
            ReorderGapPolicy.activeTranslation(3, it, 0f, 4, 100f, 1f)
        }
        assertTrue(offsets.all { it == 70f })
    }

    @Test fun terminalLastSlotUsesFullInViewportGap() {
        val offsets = (0..3).filter { it != 0 }.map {
            ReorderGapPolicy.activeTranslation(0, it, 3f, 4, 100f, 1f)
        }
        assertTrue(offsets.all { it == -100f })
    }

    @Test fun animationInterpolatesSlotWithoutChangingLogicalGeometry() {
        val logicalGeometry = listOf(
            ReorderItemGeometry(0, 0f, 100),
            ReorderItemGeometry(1, 100f, 100),
            ReorderItemGeometry(2, 200f, 100),
        )
        val before = logicalGeometry.map { it.midpoint }
        val visualOffsets = logicalGeometry.indices.map {
            ReorderGapPolicy.activeTranslation(0, it, 1.5f, 3, 100f, 1f)
        }

        assertEquals(listOf(50f, 150f, 250f), before)
        assertEquals(before, logicalGeometry.map { it.midpoint })
        assertTrue(visualOffsets.any { it != 0f })
    }

    @Test fun stationaryPointerCannotCascadeFromVisualGap() {
        val geometry = listOf(
            ReorderItemGeometry(0, 0f, 100),
            ReorderItemGeometry(1, 100f, 100),
            ReorderItemGeometry(2, 200f, 100),
        )
        val firstMove = ReorderDragPolicy.adjacentInsertionTarget(0, 0, 151f, 1, 3, geometry)
        assertEquals(1, firstMove)

        // Gap animation is graphics-only; the same stationary center has not crossed item 2.
        val whileAnimating = ReorderDragPolicy.adjacentInsertionTarget(
            0, firstMove!!, 151f, 1, 3, geometry,
        )
        assertEquals(null, whileAnimating)
    }

    @Test fun stableKeyOrderRemainsUnchangedAcrossGapSlotsUntilDrop() {
        val ids = listOf("A", "B", "C", "D")
        for (slot in 1..3) {
            (ids.indices).forEach {
                ReorderGapPolicy.activeTranslation(0, it, slot.toFloat(), ids.size, 100f, 1f)
            }
            assertEquals(listOf("A", "B", "C", "D"), ids)
        }
    }

    @Test fun gapStateIsAbsentForCancelAndNoopModel() {
        assertFloat(0f, ReorderGapPolicy.targetGapHeight(2, 2, 100f))
        assertFloat(70f, ReorderGapPolicy.targetGapHeight(2, 1, 100f))
        // Production clears ReorderDragSession before cancel/drop; no session means no translations.
    }
}
