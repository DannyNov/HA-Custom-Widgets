package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.*
import org.junit.Test

class TimerBlockLayoutPolicyTest {
    @Test fun primaryAnchorMovesRightOfRc5AndIgnoresRemainingWidth() {
        val available = 360 - 18 - 20 - 48 - 4
        val rc5Start = (available - 180) / 2
        val layout = TimerBlockLayoutPolicy.resolve(360, true, true, 48f, 120f, 1f)
        assertTrue(layout.leadingSpace >= rc5Start + 32)
        assertEquals(available / 2f, layout.leadingSpace + (56 + 52) / 2f, 1f)
        for (remaining in listOf(0f, 40f, 120f, 200f, 600f)) {
            val changed = TimerBlockLayoutPolicy.resolve(360, true, true, 48f, remaining, 1f)
            assertEquals(layout.leadingSpace, changed.leadingSpace)
            assertTrue(changed.leadingSpace + changed.blockWidth <= available)
        }
    }
    @Test fun anchorIsSafeAcrossWidthsFontsAndPowerModes() {
        for (width in listOf(180, 230, 250, 320, 360, 600))
            for (scale in listOf(1f, 1.5f, 2f)) for (power in listOf(false, true)) {
                val available = width - 18 - (if (width < 250) 16 else 20) - (if (power) 48 else 0) - 4
                val first = TimerBlockLayoutPolicy.resolve(width, true, power, 48f * scale, 0f, scale)
                for (remaining in listOf(120f, 500f)) {
                    val layout = TimerBlockLayoutPolicy.resolve(width, true, power, 48f * scale, remaining, scale)
                    assertEquals(first.leadingSpace, layout.leadingSpace)
                    assertTrue(layout.leadingSpace >= 0)
                    assertTrue(layout.leadingSpace + layout.blockWidth <= available)
                    assertTrue(layout.intervalHeight >= 48)
                }
            }
    }
    @Test fun wideAreaLeavesRoomToCenterWithoutMovingPower() {
        val layout = TimerBlockLayoutPolicy.resolve(360, true, true, 48f, 120f, 1f)
        assertEquals(180, layout.blockWidth)
        assertEquals(48, layout.intervalHeight)
        assertTrue(layout.blockWidth < 360 - 18 - 20 - 48 - 4)
    }
    @Test fun narrowAndLargeFontWrapTextWithoutShrinkingTargets() {
        for (width in listOf(180, 230, 320)) for (scale in listOf(1f, 1.5f, 2f)) {
            val layout = TimerBlockLayoutPolicy.resolve(width, true, true, 48f * scale, 120f * scale, scale)
            assertTrue(layout.textWidth > 0)
            assertTrue(layout.blockWidth <= width - 18 - (if (width < 250) 16 else 20) - 48 - 4)
            assertTrue(layout.intervalHeight >= 48)
            assertEquals(56 + layout.textWidth, layout.blockWidth)
        }
    }
}
