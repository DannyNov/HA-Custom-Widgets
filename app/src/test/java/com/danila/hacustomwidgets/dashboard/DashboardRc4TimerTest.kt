package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.remote.CompressedEntitySubscriptionParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.io.File

class DashboardRc4TimerTest {
    private val t = Instant.parse("2026-09-07T10:00:00Z")
    private fun server(minutes: Int, at: Instant = t) = HaEntity("timer.t", "active", "Timer", null,
        at.toString(), lastChanged = t.toString(), timerDuration = AutoOffTimerPolicy.durationPayload(minutes),
        timerRemaining = AutoOffTimerPolicy.durationPayload(minutes), timerFinishesAt = at.plusSeconds(minutes * 60L).toString())
    private fun reset(minutes: Int = 30, confirmed: Boolean = false) = TimerReset("local", "timer.t", 1,
        "switch.s", "switch", minutes, t.toEpochMilli(), t.minusSeconds(1).toEpochMilli(),
        t.plusSeconds(minutes * 60L).toEpochMilli(), accepted = true,
        confirmedHa = t.toEpochMilli().takeIf { confirmed }, baselineFinishAt = t.plusSeconds(1000).toEpochMilli())
    private fun metric(e: HaEntity) = DashboardMetric(e.entityId, "Timer", e.state, e.state, "timer", null,
        e.timerDuration, e.timerRemaining, e.timerFinishesAt)
    private fun wire(e: HaEntity): HaEntity {
        val attrs = JSONObject().put("friendly_name", "Timer").put("duration", e.timerDuration)
            .put("remaining", e.timerRemaining).put("finishes_at", e.timerFinishesAt)
        val parser = CompressedEntitySubscriptionParser()
        parser.apply(JSONObject().put("a", JSONObject().put(e.entityId,
            JSONObject().put("s", "active").put("lc", t.epochSecond).put("a", JSONObject()))))
        return parser.apply(JSONObject().put("c", JSONObject().put(e.entityId,
            JSONObject().put("+", JSONObject().put("lu", Instant.parse(e.lastUpdated).epochSecond).put("a", attrs)))))
            .entities.single()
    }
    private fun accept(r: TimerReset, e: HaEntity, now: Instant): Pair<TimerReset, HaTimerPresentation> {
        assertFalse("fresh server state rejected", TimerResetPolicy.stale(r, e))
        val next = TimerResetPolicy.reconcile(r, e)
        val visible = if (TimerResetPolicy.showOverlay(next, now.toEpochMilli())) TimerResetPolicy.overlay(next, metric(e)) else metric(e)
        return next to HaTimerPresentationPolicy.resolve(visible, now)
    }
    private fun multiClient(ws: Boolean, reverse: Boolean) {
        var a = reset(confirmed = reverse)
        var b = reset(confirmed = !reverse)
        val first = server(30)
        assertEquals(HaTimerPresentationPolicy.resolve(metric(first), t.plusSeconds(180)),
            HaTimerPresentationPolicy.resolve(metric(first), t.plusSeconds(180)))
        val afterMinutes = t.plusSeconds(180)
        val sixty = server(60, afterMinutes).let { if (ws) wire(it) else it }
        val result = accept(if (reverse) a else b, sixty, afterMinutes)
        assertEquals(60, result.second.actualDurationMinutes)
        assertEquals(3_600_000L, result.second.remainingMillis)
        if (reverse) a = result.first else b = result.first
        val thirty = server(30, afterMinutes.plusSeconds(180)).let { if (ws) wire(it) else it }
        val back = accept(if (reverse) b else a, thirty, afterMinutes.plusSeconds(180))
        assertEquals(30, back.second.actualDurationMinutes)
        assertEquals(1_800_000L, back.second.remainingMillis)
    }
    @Test fun aToBThenBackAfterMinutesRest() = multiClient(false, false)
    @Test fun bToAThenBackAfterMinutesRest() = multiClient(false, true)
    @Test fun aToBThenBackAfterMinutesWebSocket() = multiClient(true, false)
    @Test fun bToAThenBackAfterMinutesWebSocket() = multiClient(true, true)
    @Test fun websocketAndRestReconciliationEquivalent() {
        val e = server(60, t.plusSeconds(180))
        assertEquals(accept(reset(), e, t.plusSeconds(181)), accept(reset(), wire(e), t.plusSeconds(181)))
    }
    @Test fun differentPresetRetiresPreviouslyUnconfirmedOverlay() {
        val (r, p) = accept(reset(), server(60, t.plusSeconds(180)), t.plusSeconds(180))
        assertNotNull(r.confirmedHa)
        assertEquals(60, p.actualDurationMinutes)
    }
    @Test fun websocketBeforeHttpResponseCanRetireOverlay() {
        val (r, _) = accept(reset().copy(accepted = false), server(60, t.plusSeconds(2)), t.plusSeconds(2))
        assertFalse(TimerResetPolicy.showOverlay(r, t.plusSeconds(2).toEpochMilli()))
    }
    @Test fun sameTimestampChangedDeadlineWins() {
        val old = reset(confirmed = true)
        assertFalse(TimerResetPolicy.stale(old, server(60)))
        assertEquals(t.plusSeconds(3600).toEpochMilli(), accept(old, server(60), t).first.finishAt)
    }
    @Test fun missingTimestampChangedValidDeadlineWins() {
        val e = server(60).copy(lastUpdated = null)
        assertFalse(TimerResetPolicy.showOverlay(accept(reset(), e, t).first, t.toEpochMilli()))
    }
    @Test fun trulyOlderSnapshotWithDifferentDeadlineIsRejected() {
        assertTrue(TimerResetPolicy.stale(reset(confirmed = true), server(120, t.minusSeconds(1))))
    }
    @Test fun acceptedUnconfirmedOverlayHasBoundedLifetime() {
        assertTrue(TimerResetPolicy.showOverlay(reset(), t.plusSeconds(119).toEpochMilli()))
        assertFalse(TimerResetPolicy.showOverlay(reset(), t.plusSeconds(120).toEpochMilli()))
    }
    @Test fun external120MinuteResetHasServerRemainingOnBothClients() {
        val e = server(120, t.plusSeconds(180))
        val a = accept(reset(), e, t.plusSeconds(241)).second
        val b = accept(reset(confirmed = true), wire(e), t.plusSeconds(241)).second
        assertEquals(a, b)
        assertEquals(120, a.actualDurationMinutes)
        assertEquals(7_139_000L, a.remainingMillis)
    }
    @Test fun elapsedPressResetsThenQuickPressAdvancesExternalPreset() {
        val p = accept(reset(), server(60), t.plusSeconds(180)).second
        val config = AutoOffTimerConfig(enabled = true, timerEntityId = "timer.t")
        val index = AutoOffTimerPolicy.tapIndex(config, p.status, p.remainingMillis, p.actualDurationMinutes)
        assertEquals(60, config.durations[index].minutes)
        val fresh = HaTimerPresentationPolicy.resolve(TimerResetPolicy.overlay(reset(60).copy(createdAt = t.plusSeconds(180).toEpochMilli(),
            finishAt = t.plusSeconds(3780).toEpochMilli()), metric(server(60))), t.plusSeconds(182))
        val next = AutoOffTimerPolicy.tapIndex(config, fresh.status, fresh.remainingMillis, fresh.actualDurationMinutes)
        assertEquals(90, config.durations[next].minutes)
    }
    @Test fun sameDeadlineWithinMinuteRounding() {
        val e = metric(server(60))
        val a = HaTimerPresentationPolicy.resolve(e, t.plusSeconds(181)).remainingMillis!!
        val b = HaTimerPresentationPolicy.resolve(e, t.plusSeconds(182)).remainingMillis!!
        assertEquals(HaTimerPresentationPolicy.displayedRemainingMinutes(a), HaTimerPresentationPolicy.displayedRemainingMinutes(b))
    }
    // Replace six tests of retired client-side expiry with six upgrade/architecture regressions.
    private fun source(path: String) = File("src/main/java/com/danila/hacustomwidgets/$path").readText()
    @Test fun legacyWorkerCompletesWithoutNetworkOrTurnOff() {
        val s = source("dashboard/DashboardTimerExpiryWorker.kt").substringBefore("class DashboardTimerAlarmReceiver")
        assertTrue(s.contains("doWork(): Result = Result.success()"))
        assertFalse(s.contains("callService"))
        assertFalse(s.contains("turn_off"))
    }
    @Test fun legacyAlarmDoesNotEnqueueWork() {
        val s = source("dashboard/DashboardTimerExpiryWorker.kt").substringAfter("class DashboardTimerAlarmReceiver").substringBefore("object TimerExpiryMigration")
        assertTrue(s.contains("= Unit"))
        assertFalse(s.contains("enqueue"))
    }
    @Test fun migrationCatchesOrphanWorkByAutomaticClassTag() {
        assertEquals(DashboardTimerExpiryWorker::class.java.name, TimerExpiryMigration.WORKER_TAG)
        assertTrue(source("dashboard/DashboardTimerExpiryWorker.kt").contains("cancelAllWorkByTag(WORKER_TAG)"))
    }
    @Test fun migrationCancelsBothLegacyUniqueWorkNames() {
        assertEquals(listOf("dashboard-auto-off:timer.t", "dashboard-auto-off:timer.t:alarm"), TimerExpiryMigration.workNames("timer.t"))
    }
    @Test fun startupCleansInsteadOfReschedulingExpiry() {
        val s = source("HaWidgetApplication.kt")
        assertTrue(s.contains("TimerExpiryMigration.cleanup(this)"))
        assertFalse(s.contains("DashboardTimerExpiryWorker.schedule"))
    }
    @Test fun actionWorkerDoesNotScheduleExpiry() {
        val s = source("dashboard/DashboardTimerActionWorker.kt")
        assertFalse(s.contains("DashboardTimerExpiryWorker"))
        assertFalse(s.contains("turn_off"))
    }
}
