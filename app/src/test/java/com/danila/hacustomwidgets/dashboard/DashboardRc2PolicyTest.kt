package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.remote.CompressedEntitySubscriptionParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DashboardRc2PolicyTest {
    private val now = Instant.parse("2026-09-06T10:00:00Z")
    private val config = AutoOffTimerConfig(enabled = true, timerEntityId = "timer.t")
    private fun reset(minutes: Int = 30) = TimerReset("one", "timer.t", 1, "switch.s", "switch",
        minutes, now.toEpochMilli(), now.minusSeconds(100).toEpochMilli(), now.plusSeconds(minutes * 60L).toEpochMilli())
    private fun metric(finish: String? = now.plusSeconds(1800).toString()) = DashboardMetric(
        "timer.t", "Timer", "active", "active", "timer", null, "00:30:00", "00:30:00", finish)
    private fun entity(state: String = "active", finish: String? = now.plusSeconds(1800).toString()) =
        HaEntity("timer.t", state, "Timer", null, now.toString(), lastChanged = now.toString(),
            timerDuration = "00:30:00", timerRemaining = "00:30:00", timerFinishesAt = finish)

    @Test fun resetSamePresetAtExactly60Seconds() {
        assertEquals(0, AutoOffTimerPolicy.tapIndex(config, HaTimerStatus.ACTIVE, 1_740_000L, 30))
    }
    @Test fun advanceBefore60SecondsIncludingFractionalBoundary() {
        assertEquals(1, AutoOffTimerPolicy.tapIndex(config, HaTimerStatus.ACTIVE, 1_740_001L, 30))
        assertEquals(1, AutoOffTimerPolicy.tapIndex(config, HaTimerStatus.ACTIVE, 1_799_000L, 30))
    }
    @Test fun secondQuickPressUsesResetOverlayWithoutServerRoundtrip() {
        val old = metric(now.plusSeconds(1200).toString())
        assertEquals(0, AutoOffTimerPolicy.tapIndex(config, HaTimerStatus.ACTIVE,
            HaTimerPresentationPolicy.resolve(old, now).remainingMillis, 30))
        val optimistic = TimerResetPolicy.overlay(reset(), old)
        val shown = HaTimerPresentationPolicy.resolve(optimistic, now.plusSeconds(2))
        assertEquals(1, AutoOffTimerPolicy.tapIndex(config, shown.status, shown.remainingMillis, shown.actualDurationMinutes))
    }
    @Test fun supports120MinutesAndWrapsAfterFreshStart() {
        val shown = HaTimerPresentationPolicy.resolve(TimerResetPolicy.overlay(reset(120), metric()), now)
        assertEquals(7_200_000L, shown.remainingMillis)
        assertEquals(0, AutoOffTimerPolicy.tapIndex(config, shown.status, shown.remainingMillis, shown.actualDurationMinutes))
        assertEquals("02:00:00", AutoOffTimerPolicy.durationPayload(120))
    }
    @Test fun expiryReturnsMinimumPresentationAndTap() {
        val shown = HaTimerPresentationPolicy.resolve(TimerResetPolicy.overlay(reset(120), metric()), now.plusSeconds(7200))
        assertEquals(30, AutoOffTimerPolicy.displayedPresetMinutes(config, shown.status, shown.actualDurationMinutes))
        assertEquals(0, AutoOffTimerPolicy.tapIndex(config, shown.status, shown.remainingMillis, 120))
    }
    @Test fun expiryUsesMinimumEvenAfterPresetReordering() {
        val reordered = config.copy(durations = config.durations.reversed())
        assertEquals(30, AutoOffTimerPolicy.displayedPresetMinutes(reordered, HaTimerStatus.IDLE, 120))
        assertEquals(3, AutoOffTimerPolicy.tapIndex(reordered, HaTimerStatus.IDLE, 0, 120))
    }
    @Test fun serverFinishReplacesEarlierLocalFallbackImmediately() {
        val stored = VersionedEntityState("timer.t", "active", "active", null, 1,
            timerRemaining = "00:30:00", timerFinishesAt = now.plusSeconds(2100).toString())
        assertEquals(entity().timerFinishesAt, HaTimerPresentationPolicy.finishesAt(entity(), stored, now.plusSeconds(180)))
    }
    @Test fun twoClientsIgnoreDifferentFirstSeenWhenServerFinishExists() {
        val a = HaTimerPresentationPolicy.finishesAt(entity(), null, now)
        val b = HaTimerPresentationPolicy.finishesAt(entity(), null, now.plusSeconds(180))
        assertEquals(a, b)
        assertEquals(HaTimerPresentationPolicy.resolve(metric(a), now.plusSeconds(330)),
            HaTimerPresentationPolicy.resolve(metric(b), now.plusSeconds(330)))
    }
    @Test fun timezoneOffsetsReferToSameAbsoluteDeadline() {
        assertEquals(HaTimerPresentationPolicy.resolve(metric("2026-09-06T13:30:00+03:00"), now),
            HaTimerPresentationPolicy.resolve(metric("2026-09-06T10:30:00Z"), now))
    }
    @Test fun staleSnapshotCannotUndoPendingOrAcceptedReset() {
        val old = entity().copy(lastUpdated = now.minusSeconds(100).toString())
        assertTrue(TimerResetPolicy.stale(reset(), old))
        assertTrue(TimerResetPolicy.stale(reset().copy(accepted = true), old))
        assertFalse(TimerResetPolicy.stale(reset().copy(accepted = true), entity()))
    }
    @Test fun confirmedResetRetainsOrderingAgainstMissingAndOlderTimestamps() {
        val confirmed = reset().copy(accepted = true, confirmedHa = now.toEpochMilli())
        assertTrue(TimerResetPolicy.stale(confirmed, entity().copy(lastUpdated = null)))
        assertTrue(TimerResetPolicy.stale(confirmed, entity().copy(lastUpdated = now.minusMillis(1).toString())))
        assertFalse(TimerResetPolicy.stale(confirmed, entity()))
    }
    @Test fun matchingDurationAloneDoesNotConfirmAnOldTimer() {
        assertFalse(TimerResetPolicy.confirms(reset().copy(accepted = true),
            entity().copy(lastUpdated = now.minusSeconds(100).toString())))
        assertFalse(TimerResetPolicy.confirms(reset(), entity()))
        assertTrue(TimerResetPolicy.confirms(reset().copy(accepted = true), entity()))
    }
    @Test fun abandonedPendingGenerationCannotBlockSnapshotsForever() {
        assertFalse(TimerResetPolicy.expiredPending(reset(), now.toEpochMilli() + 119_999))
        assertTrue(TimerResetPolicy.expiredPending(reset(), now.toEpochMilli() + 120_000))
    }
    @Test fun acceptedTimerIsNotDiscardedByPendingCommandTimeout() {
        assertFalse(TimerResetPolicy.expiredPending(reset().copy(accepted = true), now.toEpochMilli() + 180_000))
    }
    @Test fun compressedStateLcSuppliesLastUpdatedForFullAndDelta() {
        val parser = CompressedEntitySubscriptionParser()
        val full = parser.apply(JSONObject("""{"a":{"timer.t":{"s":"idle","lc":1000,"a":{}}}}""")).entities.single()
        assertEquals(Instant.ofEpochSecond(1000).toString(), full.lastUpdated)
        val delta = parser.apply(JSONObject("""{"c":{"timer.t":{"+":{"s":"active","lc":1200,"a":{"duration":"00:30:00","finishes_at":"1970-01-01T00:50:00Z"}}}}}""")).entities.single()
        assertEquals(Instant.ofEpochSecond(1200).toString(), delta.lastUpdated)
        val restBefore = VersionedEntityState("timer.t", "idle", "idle", 1_100_000L, 1)
        assertTrue(DashboardStatePolicy.decide(restBefore, delta.state, TimerResetPolicy.timestamp(delta.lastUpdated), null).accept)
    }
    @Test fun adapterIdentityIsIndependentOfEntityAndSessionRevisions() {
        assertEquals("hacw://dashboard/42/collection/v1", LegacyCollectionPolicy.adapterIdentity(42))
        assertNotEquals(LegacyCollectionPolicy.adapterIdentity(42), LegacyCollectionPolicy.adapterIdentity(43))
        assertFalse(LegacyCollectionPolicy.useLegacy(26))
        assertFalse(LegacyCollectionPolicy.useLegacy(29))
        assertFalse(LegacyCollectionPolicy.useLegacy(31))
    }
    @Test fun sceneIsPlayOnlyEvenWhenItsStateIsUnknown() {
        assertFalse(ScenarioDisplayPolicy.showStateToggle("scene"))
        assertEquals("turn_on", ScenarioPolicy.runService("scene"))
        assertEquals(com.danila.hacustomwidgets.R.drawable.ic_launch_play, scenarioLaunchIcon(null))
        assertEquals(com.danila.hacustomwidgets.R.drawable.ic_launch_success, scenarioLaunchIcon(DashboardOperationStatus.CONFIRMED))
    }
    @Test fun compactSensorsResolveDrawableResourcesIncludingBattery() {
        assertEquals(com.danila.hacustomwidgets.R.drawable.ic_metric_temperature, metricIconResource(HaSemanticIcon.TEMPERATURE))
        assertEquals(com.danila.hacustomwidgets.R.drawable.ic_metric_humidity, metricIconResource(HaSemanticIcon.HUMIDITY))
        assertEquals(com.danila.hacustomwidgets.R.drawable.ic_metric_battery, metricIconResource(HaSemanticIcon.BATTERY))
    }
}
