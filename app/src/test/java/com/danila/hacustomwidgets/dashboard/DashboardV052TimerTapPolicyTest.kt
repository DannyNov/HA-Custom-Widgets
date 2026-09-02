package com.danila.hacustomwidgets.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardV052TimerTapPolicyTest {
    private val config = AutoOffTimerConfig(
        enabled = true,
        timerEntityId = "timer.dryer",
        durations = listOf(30, 60, 90, 120).mapIndexed { index, value -> TimerDurationPreset("p$index", value) },
        selectedDurationIndex = 0,
    )

    private fun tap(minutes: Int, seconds: Int = 0, actual: Int = 30, status: HaTimerStatus = HaTimerStatus.ACTIVE) =
        AutoOffTimerPolicy.tapIndex(config, status, (minutes * 60L + seconds) * 1_000L, actual)

    @Test fun fullCurrentPresetAdvances() = assertEquals(1, tap(30))
    @Test fun displayedMinuteNotYetDecreasedAdvances() = assertEquals(1, tap(29, 59))
    @Test fun decreasedDisplayedMinuteRearmsCurrentPreset() = assertEquals(0, tap(29))
    @Test fun pausedTimerUsesSameDeterministicRule() = assertEquals(0, tap(28, status = HaTimerStatus.PAUSED))
    @Test fun idleTimerPreservesStartAtFirstPreset() = assertEquals(0, tap(0, status = HaTimerStatus.IDLE))

    @Test fun exactExternalDurationOverridesStaleSelectedIndex() {
        assertEquals(2, tap(89, actual = 90))
        assertEquals(3, tap(90, actual = 90))
    }

    @Test fun ninetyCanAdvanceToOneHundredTwentyDuringItsFirstMinute() {
        assertEquals(3, tap(89, 59, actual = 90))
        assertEquals(2, tap(89, 0, actual = 90))
    }

    @Test fun oneHundredTwentyWrapsDuringItsFirstMinute() {
        assertEquals(0, tap(119, 59, actual = 120))
    }

    @Test fun idlePresentationResetsToFirstPreset() {
        val stale = config.copy(selectedDurationIndex = 2)
        assertEquals(30, AutoOffTimerPolicy.displayedPresetMinutes(stale, HaTimerStatus.IDLE, 90))
        assertEquals(90, AutoOffTimerPolicy.displayedPresetMinutes(stale, HaTimerStatus.ACTIVE, 90))
    }

    @Test fun hourFormattingBoundaryMatchesDisplayedMinutePolicy() {
        assertEquals(60, HaTimerPresentationPolicy.displayedRemainingMinutes(3_599_000L))
        assertEquals(60, HaTimerPresentationPolicy.displayedRemainingMinutes(3_600_000L))
        assertEquals(90, HaTimerPresentationPolicy.displayedRemainingMinutes(5_399_000L))
        assertEquals(89, HaTimerPresentationPolicy.displayedRemainingMinutes(5_340_000L))
    }
}
