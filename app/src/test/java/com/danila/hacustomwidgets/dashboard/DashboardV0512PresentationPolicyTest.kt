package com.danila.hacustomwidgets.dashboard

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
        assertEquals("30 мин", HaTimerPresentationPolicy.formatRemaining(30 * 60_000L))
        assertEquals("30 мин", HaTimerPresentationPolicy.formatRemaining((29 * 60 + 59) * 1_000L))
        assertEquals("30 мин", HaTimerPresentationPolicy.formatRemaining((29 * 60 + 27) * 1_000L))
        assertEquals("2 мин", HaTimerPresentationPolicy.formatRemaining(61_000L))
    }

    @Test fun underOneMinuteNeverShowsZeroMinutes() {
        assertEquals("< 1 мин", HaTimerPresentationPolicy.formatRemaining(59_000L))
        assertEquals("< 1 мин", HaTimerPresentationPolicy.formatRemaining(1_000L))
    }

    @Test fun hourCountdownRoundsUpConsistentlyWithTimerTapPolicy() {
        assertEquals("1 ч", HaTimerPresentationPolicy.formatRemaining(3_600_000L))
        assertEquals("1 ч 28 мин", HaTimerPresentationPolicy.formatRemaining((3600 + 27 * 60 + 14) * 1_000L))
        assertEquals("2 ч 15 мин", HaTimerPresentationPolicy.formatRemaining((2 * 3600 + 14 * 60 + 59) * 1_000L))
    }

    @Test fun idleUnavailableAndUnknownHaveNoRemainingPresentation() {
        listOf("idle", "unavailable", "unknown").forEach { state ->
            assertNull(HaTimerPresentationPolicy.resolve(timer(state, "00:29:27"), Instant.EPOCH).formattedRemaining)
        }
    }

    @Test fun pausedUsesSameMinutePresentationWithoutBecomingLocalAuthority() {
        val paused = HaTimerPresentationPolicy.resolve(timer("paused", "00:29:27"), Instant.EPOCH)
        assertEquals(HaTimerStatus.PAUSED, paused.status)
        assertEquals("30 мин", paused.formattedRemaining)
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

    @Test fun semanticMappingsAndLabelSuppressionRemainUnchanged() {
        val classes = listOf("temperature", "humidity", "battery", "voltage", "power", "current", "energy", "pressure", "illuminance")
        classes.forEach { deviceClass -> assertFalse(deviceClass, MetricPresentationPolicy.resolve(metric("42", deviceClass)).showLabel) }
        assertTrue(MetricPresentationPolicy.resolve(metric("42", "unknown", "Friendly label")).showLabel)
    }
}
