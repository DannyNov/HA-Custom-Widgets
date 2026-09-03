package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.tr
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DashboardV051TimerPolicyTest {
    private fun preset(vararg minutes: Int) = minutes.mapIndexed { i, value -> TimerDurationPreset("p$i", value) }
    private fun timer(state: String, duration: String? = null, remaining: String? = null, finish: String? = null) =
        DashboardMetric("timer.test", "Timer", state, state, "timer", null, duration, remaining, finish)

    @Test fun eligibilityIsLimitedToSafeBinaryDomains() {
        assertTrue(AutoOffTimerPolicy.eligible("switch")); assertTrue(AutoOffTimerPolicy.eligible("input_boolean"))
        assertTrue(AutoOffTimerPolicy.eligible("light"))
        listOf("button", "script", "scene", "sensor", "automation", "timer").forEach {
            assertFalse(AutoOffTimerPolicy.eligible(it))
        }
    }

    @Test fun defaultCycleStartsAtThirty() {
        assertEquals(0, AutoOffTimerPolicy.nextIndex(AutoOffTimerConfig(), null))
    }

    @Test fun activePresetCyclesInManualOrder() {
        val config = AutoOffTimerConfig(true, "timer.x", preset(30, 60, 90, 120))
        assertEquals(1, AutoOffTimerPolicy.nextIndex(config, 30))
        assertEquals(2, AutoOffTimerPolicy.nextIndex(config, 60))
        assertEquals(3, AutoOffTimerPolicy.nextIndex(config, 90))
        assertEquals(0, AutoOffTimerPolicy.nextIndex(config, 120))
    }

    @Test fun customCycleKeepsSavedOrder() {
        val config = AutoOffTimerConfig(true, "timer.x", preset(15, 180, 45))
        assertEquals(1, AutoOffTimerPolicy.nextIndex(config, 15))
    }

    @Test fun externalDurationChoosesFirstGreaterPreset() {
        val config = AutoOffTimerConfig(true, "timer.x", preset(30, 60, 90, 120))
        assertEquals(1, AutoOffTimerPolicy.nextIndex(config, 45))
        assertEquals(0, AutoOffTimerPolicy.nextIndex(config, 150))
    }

    @Test fun durationValidationRejectsInvalidAndDuplicates() {
        assertFalse(AutoOffTimerPolicy.validate(emptyList()))
        assertFalse(AutoOffTimerPolicy.validate(preset(0)))
        assertFalse(AutoOffTimerPolicy.validate(preset(-1)))
        assertFalse(AutoOffTimerPolicy.validate(preset(30, 30)))
        assertTrue(AutoOffTimerPolicy.validate(preset(45, 15, 180)))
    }

    @Test fun durationPayloadIsHaTimeFormat() {
        assertEquals("00:30:00", AutoOffTimerPolicy.durationPayload(30))
        assertEquals("02:00:00", AutoOffTimerPolicy.durationPayload(120))
    }

    @Test fun offDeviceStartsPrimaryBeforeTimer() {
        val calls = CompositeTimerActionPolicy.start(DashboardControl("switch.dryer", "Dryer", "switch", "off"),
            false, "timer.dryer", 30)
        assertEquals(listOf("switch.turn_on", "timer.start"), calls.map { "${it.domain}.${it.service}" })
        assertEquals("switch.dryer", calls[0].entityId)
        assertEquals("timer.dryer", calls[1].entityId)
        assertEquals("00:30:00", calls[1].data["duration"])
    }

    @Test fun onDeviceOnlyStartsTimer() {
        val calls = CompositeTimerActionPolicy.start(DashboardControl("switch.dryer", "Dryer", "switch", "on"),
            true, "timer.dryer", 60)
        assertEquals(1, calls.size); assertEquals("timer.start", "${calls[0].domain}.${calls[0].service}")
    }

    @Test fun manualOffCancelsOnlyRunningTimer() {
        val control = DashboardControl("switch.dryer", "Dryer", "switch", "on")
        assertEquals(listOf("switch.turn_off", "timer.cancel"),
            CompositeTimerActionPolicy.powerOff(control, "timer.dryer", "active").map { "${it.domain}.${it.service}" })
        assertEquals(1, CompositeTimerActionPolicy.powerOff(control, "timer.dryer", "idle").size)
    }

    @Test fun timerStatesAreParsed() {
        assertEquals(HaTimerStatus.IDLE, HaTimerPresentationPolicy.resolve(timer("idle"), Instant.EPOCH).status)
        assertEquals(HaTimerStatus.ACTIVE, HaTimerPresentationPolicy.resolve(timer("active", remaining="00:10:00"), Instant.EPOCH).status)
        assertEquals(HaTimerStatus.PAUSED, HaTimerPresentationPolicy.resolve(timer("paused", remaining="00:10:00"), Instant.EPOCH).status)
        assertEquals(HaTimerStatus.UNAVAILABLE, HaTimerPresentationPolicy.resolve(timer("unavailable"), Instant.EPOCH).status)
        assertEquals(HaTimerStatus.UNKNOWN, HaTimerPresentationPolicy.resolve(timer("unknown"), Instant.EPOCH).status)
    }

    @Test fun finishAtIsAuthoritativeAndClamped() {
        val result = HaTimerPresentationPolicy.resolve(timer("active", finish="1970-01-01T00:17:32Z"), Instant.EPOCH)
        assertEquals(1_052_000L, result.remainingMillis)
        assertEquals(0L, HaTimerPresentationPolicy.resolve(timer("active", finish="1969-12-31T23:59:00Z"), Instant.EPOCH).remainingMillis)
    }

    @Test fun malformedTimerAttributesNeverThrow() {
        assertNull(HaTimerPresentationPolicy.resolve(timer("active", "bad", "bad", "bad"), Instant.EPOCH).remainingMillis)
    }

    @Test fun countdownFormattingMatchesUx() {
        assertEquals(tr("48 min", "48 мин"), HaTimerPresentationPolicy.formatRemaining((47*60+32)*1000L))
        assertEquals(tr("1 h 28 min", "1 ч 28 мин"), HaTimerPresentationPolicy.formatRemaining((3600+27*60+14)*1000L))
    }

    @Test fun batterySemanticMetricDoesNotNeedDuplicateLabel() {
        assertFalse(MetricPresentationPolicy.showLabel(DashboardMetric("sensor.battery", "Батарея", "92 %", "92", "sensor", "battery")))
        assertTrue(MetricPresentationPolicy.showLabel(DashboardMetric("sensor.custom", "Качество", "42", "42", "sensor", null)))
    }

    @Test fun presetStableIdSurvivesEditAndReorder() {
        val values = preset(30, 60, 90)
        val edited = values.map { if (it.id == "p0") it.copy(minutes = 45) else it }
        assertEquals("p0", edited.first().id)
        assertEquals(listOf("p1", "p2", "p0"), moveStable(edited, 0, 2).map { it.id })
    }

    @Test fun assignedTimerIsHiddenOnlyFromDuplicatePresentation() {
        val configs = mapOf("dryer" to AutoOffTimerConfig(true, "timer.dryer", preset(30)))
        assertEquals(setOf("timer.dryer"), CompositeTimerPresentationPolicy.assignedTimerIds(configs))
        assertTrue("timer remains an authoritative subscribed id", "timer.dryer" in configs.values.mapNotNull { it.timerEntityId })
    }
}
