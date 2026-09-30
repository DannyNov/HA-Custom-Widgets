package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.*
import org.junit.Test

class TimerBlockLayoutPolicyTest {
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
