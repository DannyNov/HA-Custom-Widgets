package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.AboutLinks
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.tr
import java.net.URI
import java.time.Instant
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DashboardV061PolicyTest {
    private fun battery(raw: String) = DashboardMetric("sensor.tuya_battery", "Состояние батареи", raw, raw, "sensor", null)
    private fun timer(state: String = "active") = DashboardMetric("timer.test", "Timer", state, state, "timer", null,
        "02:00:00", "02:00:00", "2026-09-06T12:00:00Z")

    @Test fun textualBatteriesUseAllTuyaAliasesAndNeutralUnknown() {
        listOf("high", "normal", " HIGH ").forEach { assertEquals(BatteryHealth.NORMAL, batteryHealth(battery(it))) }
        listOf("middle", "medium").forEach { assertEquals(BatteryHealth.LOW, batteryHealth(battery(it))) }
        assertEquals(BatteryHealth.CRITICAL, batteryHealth(battery("low")))
        listOf("unknown", "unavailable", "nonsense", "NaN", "101", "-1").forEach {
            assertEquals(BatteryHealth.UNKNOWN, batteryHealth(battery(it)))
        }
        assertFalse(MetricPresentationPolicy.showLabel(battery("low")))
    }

    @Test fun percentageThresholdsAreUnchanged() {
        listOf("0", "10").forEach { assertEquals(BatteryHealth.CRITICAL, batteryHealth(battery(it))) }
        listOf("11", "30").forEach { assertEquals(BatteryHealth.LOW, batteryHealth(battery(it))) }
        listOf("31", "100").forEach { assertEquals(BatteryHealth.NORMAL, batteryHealth(battery(it))) }
        assertEquals("92 %", batteryDisplayState(battery("92").copy(state = "92 %")))
    }

    @Test fun batteryLabelsAreLocalizedWithoutRawPrefix() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("Low", batteryDisplayState(battery("low")))
            assertEquals("Medium", batteryDisplayState(battery("middle")))
            Locale.setDefault(Locale.forLanguageTag("ru"))
            assertEquals("Низкий", batteryDisplayState(battery("low")))
            assertEquals("Высокий", batteryDisplayState(battery("normal")))
            assertEquals("—", batteryDisplayState(battery("unavailable")))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun countdownDecreasesWithoutAnyNewHaEventIncluding120Minutes() {
        val start = Instant.parse("2026-09-06T10:00:00Z")
        assertEquals(7_200_000L, HaTimerPresentationPolicy.resolve(timer(), start).remainingMillis)
        assertEquals(7_140_000L, HaTimerPresentationPolicy.resolve(timer(), start.plusSeconds(60)).remainingMillis)
        assertEquals("02:00:00", AutoOffTimerPolicy.durationPayload(120))
        val finished = HaTimerPresentationPolicy.resolve(timer(), start.plusSeconds(7200))
        assertEquals(HaTimerStatus.IDLE, finished.status)
        assertEquals(0L, finished.remainingMillis)
        assertEquals(30, AutoOffTimerPolicy.displayedPresetMinutes(AutoOffTimerConfig(), finished.status, 120))
        assertEquals(0, AutoOffTimerPolicy.tapIndex(AutoOffTimerConfig(), finished.status, 0, 120))
    }

    @Test fun pausedTimerDoesNotCountDownAndMalformedFinishFallsBack() {
        val now = Instant.parse("2026-09-06T10:00:00Z")
        assertEquals(HaTimerPresentationPolicy.resolve(timer("paused"), now), HaTimerPresentationPolicy.resolve(timer("paused"), now.plusSeconds(60)))
        val entity = HaEntity("timer.test", "active", "Timer", null, now.toString(), timerRemaining = "00:10:00", timerFinishesAt = "bad")
        assertEquals(now.plusSeconds(600).toString(), HaTimerPresentationPolicy.finishesAt(entity, null, now.plusSeconds(60)))
    }

    @Test fun remainingFallbackWithoutHaTimestampDoesNotRestartOnRepeatedRestRead() {
        val now = Instant.parse("2026-09-06T10:00:00Z")
        val entity = HaEntity("timer.test", "active", "Timer", null, null, timerRemaining = "00:10:00")
        val finish = HaTimerPresentationPolicy.finishesAt(entity, null, now)
        val stored = VersionedEntityState("timer.test", "active", "active", null, 1,
            timerRemaining = entity.timerRemaining, timerFinishesAt = finish)
        assertEquals(finish, HaTimerPresentationPolicy.finishesAt(entity, stored, now.plusSeconds(60)))
    }

    @Test fun countdownSchedulingIsBoundedAndStops() {
        assertEquals(60_000L, DashboardCountdownPolicy.nextDelayMillis(listOf(7_200_000)))
        assertEquals(15_000L, DashboardCountdownPolicy.nextDelayMillis(listOf(7_200_000, 15_000)))
        assertEquals(1_000L, DashboardCountdownPolicy.nextDelayMillis(listOf(1)))
        assertNull(DashboardCountdownPolicy.nextDelayMillis(listOf(0, -1)))
        assertNull(DashboardCountdownPolicy.nextDelayMillis(emptyList()))
    }

    @Test fun restAndEventWithIdenticalPayloadDoNotRequireNewVisualRevision() {
        val stored = VersionedEntityState("switch.test", "on", "on", 100, 1)
        val entity = HaEntity("switch.test", "on", "Test", null, Instant.ofEpochMilli(200).toString())
        assertTrue(DashboardRefreshPolicy.samePayload(stored, entity))
        assertFalse(DashboardRefreshPolicy.samePayload(stored, entity.copy(state = "off")))
        assertFalse(DashboardRefreshPolicy.samePayload(stored, entity.copy(unit = "%")))
        assertFalse(DashboardRefreshPolicy.samePayload(stored, entity.copy(timerFinishesAt = "new finish")))
    }

    @Test fun duplicatePayloadStillConfirmsAnActiveOperation() {
        val stored = VersionedEntityState("switch.test", "on", "on", 100, 1)
        val operation = DashboardOperation("op", "switch.test", "switch", "turn_on", "on", "on", "off", 100, 12000, DashboardOperationStatus.RUNNING)
        assertTrue(DashboardStatePolicy.decide(stored, "on", 100, operation).confirmsOperation)
        assertFalse(DashboardStatePolicy.decide(stored, "off", 99, operation).accept)
    }

    @Test fun cachedCardsAndListIdentitySurviveRefreshAndWorkerStatusChanges() {
        val card = DashboardCard("device", "Device", null, null, DeviceCategory.OTHER, emptyList(), emptyList())
        val config = DashboardConfig(1, emptyList(), emptyMap(), emptyList(), emptyMap(), emptyMap(), false, false)
        val state = DashboardState(config, emptyList(), listOf(card), emptyList(), MAIN_TAB_ID,
            emptySet(), emptySet(), mapOf("switch.test" to DashboardOperationStatus.PENDING), 1, false, 0, null)
        val next = state.copy(refreshInProgress = true, stateRevision = 3,
            operationStatusByEntity = mapOf("switch.test" to DashboardOperationStatus.RUNNING))
        assertEquals(DashboardRefreshPolicy.presentation(state), DashboardRefreshPolicy.presentation(next))
        assertSame(card, DashboardRefreshPolicy.presentation(next).cards.single())
        assertEquals(state.selectedTabId, next.selectedTabId)
        assertEquals(DashboardStatePolicy.stableCollectionId("card:device"), DashboardStatePolicy.stableCollectionId("card:${next.cards.single().key}"))
    }

    @Test fun scenesArePlayOnlyAndUseSceneTurnOn() {
        assertTrue("scene" in SCENARIO_DOMAINS)
        assertEquals("turn_on", ScenarioPolicy.runService("scene"))
        assertEquals("trigger", ScenarioPolicy.runService("automation"))
        assertEquals("turn_on", ScenarioPolicy.runService("script"))
        assertFalse(ScenarioDisplayPolicy.showStateToggle("scene"))
        assertFalse(ScenarioDisplayPolicy.showStateToggle("script"))
        assertTrue(ScenarioDisplayPolicy.showStateToggle("automation"))
        assertTrue(ScenarioDisplayPolicy.isRunOperation(DashboardOperation("op", "scene.test", "scene", "turn_on", null, null, null, 0, 100, DashboardOperationStatus.PENDING)))
    }

    @Test fun aboutHasExactOfficialDestinations() {
        assertEquals("https://github.com/DannyNov/HA-Custom-Widgets", AboutLinks.GITHUB)
        assertEquals("https://t.me/HACustomWidgets", AboutLinks.TELEGRAM)
        assertEquals("https://dannynov.github.io/HA-Custom-Widgets/privacy-policy/", AboutLinks.PRIVACY)
        assertEquals("https://github.com/DannyNov/HA-Custom-Widgets/issues", AboutLinks.ISSUES)
        assertEquals("mailto:hacustomwidgets@gmail.com", AboutLinks.CONTACT)
        assertEquals(6, AboutLinks.all.distinct().size)
        AboutLinks.all.forEach { assertTrue(URI(it).scheme in setOf("https", "mailto")) }
    }
}
