package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.*
import org.junit.Test

class TimerGlyphPolicyTest {
    @Test fun onlyActiveTimersUseYellow() {
        for (status in HaTimerStatus.entries) {
            assertEquals(status == HaTimerStatus.ACTIVE, TimerGlyphPolicy.activeYellow(status, null, "on"))
        }
        assertFalse(TimerGlyphPolicy.activeYellow(null, null, "on"))
    }

    @Test fun pendingRecoveryPreservesPendingGlyphThenRestoresActiveTint() {
        for (operation in DashboardOperationStatus.entries) {
            assertEquals(!operation.isActive, TimerGlyphPolicy.activeYellow(HaTimerStatus.ACTIVE, operation, "on"))
        }
    }

    @Test fun unavailableAndUnknownControlsCannotLookActive() {
        for (state in listOf("unknown", "unavailable")) {
            assertFalse(TimerGlyphPolicy.activeYellow(HaTimerStatus.ACTIVE, null, state))
        }
        assertTrue(TimerGlyphPolicy.activeYellow(HaTimerStatus.ACTIVE, null, "off"))
    }
}
