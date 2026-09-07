package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaEntity
import java.time.Instant

/** A command generation is independent of a widget redraw and of HA's state string. */
data class TimerReset(
    val generation: String,
    val timerId: String,
    val widgetId: Int,
    val primaryId: String,
    val domain: String,
    val minutes: Int,
    val createdAt: Long,
    val baselineHa: Long?,
    val finishAt: Long,
    val accepted: Boolean = false,
    val confirmedHa: Long? = null,
    val serverUrl: String = "",
)

object TimerResetPolicy {
    fun expiredPending(reset: TimerReset, now: Long): Boolean =
        !reset.accepted && now - reset.createdAt >= 120_000L

    fun timestamp(value: String?): Long? = value?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
    }

    fun confirms(reset: TimerReset, entity: HaEntity): Boolean {
        val updated = timestamp(entity.lastUpdated) ?: return false
        val finish = timestamp(entity.timerFinishesAt) ?: return false
        return reset.accepted && entity.state == "active" &&
            (reset.baselineHa == null || updated > reset.baselineHa) &&
            HaTimerPresentationPolicy.parseDuration(entity.timerDuration) == reset.minutes * 60_000L &&
            finish > reset.createdAt
    }

    fun stale(reset: TimerReset, entity: HaEntity): Boolean {
        val updated = timestamp(entity.lastUpdated)
        val floor = reset.confirmedHa ?: reset.baselineHa
        return if (reset.confirmedHa != null) updated == null || updated < reset.confirmedHa
        else !confirms(reset, entity) && (updated == null || floor == null || updated <= floor || !reset.accepted)
    }

    fun overlay(reset: TimerReset, metric: DashboardMetric): DashboardMetric = metric.copy(
        rawState = "active", state = "active",
        timerDuration = AutoOffTimerPolicy.durationPayload(reset.minutes),
        timerRemaining = AutoOffTimerPolicy.durationPayload(reset.minutes),
        timerFinishesAt = Instant.ofEpochMilli(reset.finishAt).toString(),
    )
}

enum class TimerExpiryDecision { WAIT, RESCHEDULE, CANCEL, TURN_OFF }

object TimerExpiryPolicy {
    fun decide(reset: TimerReset, timer: HaEntity, primary: HaEntity, now: Long): TimerExpiryDecision {
        if (!reset.accepted || now < reset.finishAt) return TimerExpiryDecision.WAIT
        if (timer.state == "paused") return TimerExpiryDecision.WAIT
        if (timer.state == "active") return TimerExpiryDecision.RESCHEDULE
        if (timer.state != "idle") return TimerExpiryDecision.WAIT
        // Idle also means cancel. Only a transition at the expected deadline can expire this run.
        val idleAt = TimerResetPolicy.timestamp(timer.lastChanged ?: timer.lastUpdated)
            ?: return TimerExpiryDecision.CANCEL
        if (idleAt < reset.finishAt - 2_000L) return TimerExpiryDecision.CANCEL
        if (primary.state != "on") return TimerExpiryDecision.CANCEL
        val switchedAt = TimerResetPolicy.timestamp(primary.lastChanged)
            ?: return TimerExpiryDecision.CANCEL
        if (switchedAt > reset.finishAt) return TimerExpiryDecision.CANCEL
        return TimerExpiryDecision.TURN_OFF
    }

    fun turnOff(reset: TimerReset) = TimerServiceCall(reset.domain, "turn_off", reset.primaryId)
    fun workName(timerId: String) = "dashboard-auto-off:$timerId"
}
