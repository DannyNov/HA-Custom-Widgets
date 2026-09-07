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
    val baselineFinishAt: Long? = null,
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
        // A provably older snapshot must never win, even if its deadline differs.
        if (updated != null && floor != null && updated < floor) return true
        if (updated != null && floor != null && updated > floor) return false
        val finish = timestamp(entity.timerFinishesAt)
        val previousFinish = if (reset.confirmedHa != null) reset.finishAt else reset.baselineFinishAt
        if (entity.state == "active" && finish != null && previousFinish != null && finish != previousFinish) return false
        return if (reset.confirmedHa != null) updated == null else !confirms(reset, entity)
    }

    /** Retire the overlay for any accepted server truth, including a different client's preset.
     * Keep command identity so an in-flight worker does not repeat timer.start after a REST error. */
    fun reconcile(reset: TimerReset, entity: HaEntity): TimerReset = reset.copy(
        confirmedHa = timestamp(entity.lastUpdated) ?: reset.confirmedHa ?: reset.baselineHa ?: 0L,
        finishAt = timestamp(entity.timerFinishesAt) ?: reset.finishAt,
    )

    fun showOverlay(reset: TimerReset, now: Long): Boolean =
        reset.confirmedHa == null && now - reset.createdAt < 120_000L

    fun overlay(reset: TimerReset, metric: DashboardMetric): DashboardMetric = metric.copy(
        rawState = "active", state = "active",
        timerDuration = AutoOffTimerPolicy.durationPayload(reset.minutes),
        timerRemaining = AutoOffTimerPolicy.durationPayload(reset.minutes),
        timerFinishesAt = Instant.ofEpochMilli(reset.finishAt).toString(),
    )
}
