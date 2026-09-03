package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.tr
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DashboardV0512PresentationPolicyTest {
    private fun metric(state: String, deviceClass: String = "power", label: String = "Power") =
        DashboardMetric("sensor.test", label, state, state.substringBefore(' '), "sensor", deviceClass)

    private fun timer(state: String, remaining: String?) = DashboardMetric(
        "timer.test", "Timer", state, state, "timer", null, timerRemaining = remaining,
    )

    @Test fun subHourCountdownRoundsUpToWholeMinutes() {
        assertEquals(tr("30 min", "30 мин"), HaTimerPresentationPolicy.formatRemaining(30 * 60_000L))
        assertEquals(tr("30 min", "30 мин"), HaTimerPresentationPolicy.formatRemaining((29 * 60 + 59) * 1_000L))
        assertEquals(tr("30 min", "30 мин"), HaTimerPresentationPolicy.formatRemaining((29 * 60 + 27) * 1_000L))
        assertEquals(tr("2 min", "2 мин"), HaTimerPresentationPolicy.formatRemaining(61_000L))
    }

    @Test fun underOneMinuteNeverShowsZeroMinutes() {
        assertEquals(tr("< 1 min", "< 1 мин"), HaTimerPresentationPolicy.formatRemaining(59_000L))
        assertEquals(tr("< 1 min", "< 1 мин"), HaTimerPresentationPolicy.formatRemaining(1_000L))
    }

    @Test fun hourCountdownRoundsUpConsistentlyWithTimerTapPolicy() {
        assertEquals(tr("1 h", "1 ч"), HaTimerPresentationPolicy.formatRemaining(3_600_000L))
        assertEquals(tr("1 h 28 min", "1 ч 28 мин"), HaTimerPresentationPolicy.formatRemaining((3600 + 27 * 60 + 14) * 1_000L))
        assertEquals(tr("2 h 15 min", "2 ч 15 мин"), HaTimerPresentationPolicy.formatRemaining((2 * 3600 + 14 * 60 + 59) * 1_000L))
    }

    @Test fun idleUnavailableAndUnknownHaveNoRemainingPresentation() {
        listOf("idle", "unavailable", "unknown").forEach { state ->
            assertNull(HaTimerPresentationPolicy.resolve(timer(state, "00:29:27"), Instant.EPOCH).formattedRemaining)
        }
    }

    @Test fun pausedUsesSameMinutePresentationWithoutBecomingLocalAuthority() {
        val paused = HaTimerPresentationPolicy.resolve(timer("paused", "00:29:27"), Instant.EPOCH)
        assertEquals(HaTimerStatus.PAUSED, paused.status)
        assertEquals(tr("30 min", "30 мин"), paused.formattedRemaining)
    }

    @Test fun finishesAtCannotRenderAboveNominalTimerDuration() {
        val metric = DashboardMetric(
            "timer.test", "Timer", "active", "active", "timer", null,
            timerDuration = "00:30:00",
            timerFinishesAt = "1970-01-01T00:30:00.500Z",
        )
        val presentation = HaTimerPresentationPolicy.resolve(metric, Instant.EPOCH)
        assertEquals(30 * 60_000L, presentation.remainingMillis)
        assertEquals(tr("30 min", "30 мин"), presentation.formattedRemaining)
    }

    @Test fun singleMetricUsesOneColumn() {
        assertEquals(1, MetricLayoutPolicy.columns(listOf(metric("9.8 W")), 400))
    }

    @Test fun threeShortMetricsUseOneRowWhenTheyFit() {
        val metrics = listOf(metric("0 W"), metric("234 V", "voltage"), metric("21 °C", "temperature"))
        assertEquals(3, MetricLayoutPolicy.columns(metrics, 300))
    }

    @Test fun narrowWidgetFallsBackToOneColumn() {
        assertEquals(1, MetricLayoutPolicy.columns(listOf(metric("0 W"), metric("0 V", "voltage")), 180))
    }

    @Test fun longValuesFallBackToOneColumnInsteadOfClippingTwoCells() {
        val metrics = listOf(metric("1234.5 W"), metric("123.45 kWh", "energy"))
        assertEquals(1, MetricLayoutPolicy.columns(metrics, 200))
    }

    @Test fun fourDryerMetricsFitConservativeTwoColumnRowsAtNormalWidth() {
        val metrics = listOf(
            metric("801 mA", "current"), metric("189.9 W"),
            metric("234.5 V", "voltage"), metric("0.0 kWh", "energy"),
        )
        assertEquals(2, MetricLayoutPolicy.columns(metrics, 300))
    }

    @Test fun genericFriendlyLabelIsIncludedInWidthEstimate() {
        val generic = metric("some long state", "unknown", "Очень длинное техническое показание")
        assertTrue(MetricPresentationPolicy.resolve(generic).showLabel)
        assertEquals(1, MetricLayoutPolicy.columns(listOf(generic, metric("0 W")), 320))
    }

    @Test fun primaryPowerButtonUsesRequestedTypeColorsAndFixedSize() {
        val lightOff = DashboardControl("light.kitchen", "Kitchen", "light", "off")
        val lightOn = lightOff.copy(state = "on")
        val switchOn = DashboardControl("switch.socket", "Socket", "switch", "on")
        assertTrue(PrimaryPowerButtonPolicy.supports(lightOff))
        assertTrue(PrimaryPowerButtonPolicy.supports(switchOn))
        assertFalse(PrimaryPowerButtonPolicy.supports(DashboardControl("button.x", "X", "button", "off")))
        assertEquals(PrimaryPowerButtonTone.OFF, PrimaryPowerButtonPolicy.tone(lightOff))
        assertEquals(PrimaryPowerButtonTone.LIGHT_ON_GREEN, PrimaryPowerButtonPolicy.tone(lightOn))
        assertEquals(PrimaryPowerButtonTone.SWITCH_ON_YELLOW, PrimaryPowerButtonPolicy.tone(switchOn))
        assertEquals(40, PrimaryPowerButtonPolicy.VISIBLE_SIZE_DP)
        assertEquals(48, PrimaryPowerButtonPolicy.TOUCH_SIZE_DP)
    }

    @Test fun batteryRangesUseGreenYellowRedAndUnknownBands() {
        fun battery(raw: String) = DashboardMetric(
            "sensor.battery", "Battery", "$raw %", raw, "sensor", "battery",
        )
        assertEquals(BatteryHealth.NORMAL, batteryHealth(battery("100")))
        assertEquals(BatteryHealth.NORMAL, batteryHealth(battery("31")))
        assertEquals(BatteryHealth.LOW, batteryHealth(battery("30")))
        assertEquals(BatteryHealth.LOW, batteryHealth(battery("11")))
        assertEquals(BatteryHealth.CRITICAL, batteryHealth(battery("10")))
        assertEquals(BatteryHealth.CRITICAL, batteryHealth(battery("0")))
        assertEquals(BatteryHealth.UNKNOWN, batteryHealth(battery("unknown")))
        assertEquals(BatteryHealth.UNKNOWN, batteryHealth(battery("101")))
    }

    @Test fun semanticMappingsAndLabelSuppressionRemainUnchanged() {
        val classes = listOf("temperature", "humidity", "battery", "voltage", "power", "current", "energy", "pressure", "illuminance")
        classes.forEach { deviceClass -> assertFalse(deviceClass, MetricPresentationPolicy.resolve(metric("42", deviceClass)).showLabel) }
        assertTrue(MetricPresentationPolicy.resolve(metric("42", "unknown", "Friendly label")).showLabel)
    }
}
