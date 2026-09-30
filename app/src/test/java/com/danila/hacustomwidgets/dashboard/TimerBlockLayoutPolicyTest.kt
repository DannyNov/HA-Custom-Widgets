package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.*
import org.junit.Test

class TimerBlockLayoutPolicyTest {
    @Test fun bothStringsAndTimerKeepOneFixedAnchor() {
        for (width in listOf(180, 230, 250, 320, 360, 600))
            for (scale in listOf(1f, 1.5f, 2f)) for (power in listOf(false, true)) {
                val first = TimerBlockLayoutPolicy.resolve(width, true, power, 48f, 0f, scale)
                for (interval in listOf(30f, 40f, 48f, 60f))
                    for (remaining in listOf(0f, 40f, 120f, 200f, 600f)) {
                        val changed = TimerBlockLayoutPolicy.resolve(width, true, power, interval * scale, remaining * scale, scale)
                        assertEquals(first.leadingSpace, changed.leadingSpace)
                        assertEquals(first.remainingLeading, changed.remainingLeading)
                        assertEquals(changed.leadingSpace + 56, changed.remainingLeading)
                        val full = width - 18 - (if (width < 250) 16 else 20)
                        assertTrue(changed.leadingSpace >= 0)
                        assertTrue(changed.leadingSpace + changed.blockWidth <= full - (if (power) 48 else 0) - 4)
                        assertEquals(full, changed.remainingLeading + changed.remainingWidth)
                        assertTrue(changed.intervalHeight >= 48)
                    }
            }
    }
    @Test fun normalCaptionUsesFullLowerRowBeyondPowerColumn() {
        val layout = TimerBlockLayoutPolicy.resolve(320, true, true, 48f, 120f, 1f)
        assertEquals(119, layout.remainingLeading)
        assertEquals(63, layout.leadingSpace)
        assertEquals(163, layout.remainingWidth)
        assertTrue(layout.remainingWidth >= 124)
        assertEquals(48, layout.intervalHeight)
    }
    @Test fun absentPowerDoesNotRepositionNormalTextColumn() {
        val withPower = TimerBlockLayoutPolicy.resolve(360, true, true, 48f, 120f, 1f)
        val withoutPower = TimerBlockLayoutPolicy.resolve(360, true, false, 48f, 120f, 1f)
        assertEquals(withPower.leadingSpace, withoutPower.leadingSpace)
        assertEquals(withPower.remainingLeading, withoutPower.remainingLeading)
        assertEquals(withPower.remainingWidth, withoutPower.remainingWidth)
    }
    @Test fun compactAndRegularCardsRetainSafeLowerRowCapacity() {
        for (compact in listOf(false, true)) for (width in listOf(180, 230, 320, 360)) {
            val layout = TimerBlockLayoutPolicy.resolve(width, compact, true, 96f, 500f, 2f)
            val capacity = width - (if (compact) 18 else 24) - (if (width < 250) 16 else 20)
            assertEquals(capacity, layout.remainingLeading + layout.remainingWidth)
            assertEquals(layout.leadingSpace + 56, layout.remainingLeading)
            assertTrue(layout.remainingWidth > 0)
        }
    }
    @Test fun narrowIntervalWrapsVerticallyWithoutShrinkingTimerTarget() {
        val normal = TimerBlockLayoutPolicy.resolve(180, true, true, 48f, 120f, 1f)
        val large = TimerBlockLayoutPolicy.resolve(180, true, true, 96f, 240f, 2f)
        assertEquals(normal.leadingSpace, large.leadingSpace)
        assertEquals(normal.remainingLeading, large.remainingLeading)
        assertTrue(large.textWidth < 96)
        assertTrue(large.intervalHeight > normal.intervalHeight)
        assertTrue(normal.intervalHeight >= 48)
        assertEquals(56 + large.textWidth, large.blockWidth)
    }
}
