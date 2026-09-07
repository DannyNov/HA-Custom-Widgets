package com.danila.hacustomwidgets.dashboard

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

internal object TimerExecutionLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun get(timerId: String) = locks.getOrPut(timerId) { Mutex() }
}

/** One local owner per linked HA timer, shared by every Dashboard on this installation. */
class TimerResetStore(context: Context) {
    private val prefs = context.getSharedPreferences("dashboard_timer_runs", Context.MODE_PRIVATE)

    fun get(timerId: String): TimerReset? = synchronized(lock) {
        prefs.getString(timerId, null)?.let { runCatching {
            val j = JSONObject(it)
            TimerReset(j.getString("generation"), timerId, j.getInt("widget"), j.getString("primary"),
                j.getString("domain"), j.getInt("minutes"), j.getLong("created"),
                j.optLong("baseline").takeIf { j.has("baseline") }, j.getLong("finish"),
                j.optBoolean("accepted"), j.optLong("confirmed").takeIf { j.has("confirmed") }, j.optString("server"), j.optLong("baseline_finish").takeIf { j.has("baseline_finish") })
        }.getOrNull() }
    }

    fun clear() = synchronized(lock) { check(prefs.edit().clear().commit()) }

    fun all(): List<TimerReset> = synchronized(lock) { prefs.all.keys.mapNotNull(::get) }

    fun put(value: TimerReset) = synchronized(lock) {
        check(prefs.edit().putString(value.timerId, JSONObject()
            .put("generation", value.generation).put("widget", value.widgetId)
            .put("primary", value.primaryId).put("domain", value.domain).put("minutes", value.minutes)
            .put("created", value.createdAt).put("baseline", value.baselineHa)
            .put("finish", value.finishAt).put("accepted", value.accepted)
            .put("baseline_finish", value.baselineFinishAt).put("confirmed", value.confirmedHa).put("server", value.serverUrl).toString()).commit())
    }

    fun update(value: TimerReset): Boolean = synchronized(lock) {
        if (get(value.timerId)?.generation != value.generation) false else { put(value); true }
    }

    fun markAccepted(timerId: String, generation: String, now: Long): Boolean = synchronized(lock) {
        val latest = get(timerId)?.takeIf { it.generation == generation } ?: return false
        put(latest.copy(accepted = true, finishAt = if (latest.confirmedHa != null || latest.accepted)
            latest.finishAt else now + latest.minutes * 60_000L))
        true
    }

    fun reconcile(reset: TimerReset, entity: com.danila.hacustomwidgets.data.model.HaEntity) = synchronized(lock) {
        val latest = get(reset.timerId)?.takeIf { it.generation == reset.generation } ?: return
        if (!TimerResetPolicy.stale(latest, entity)) put(TimerResetPolicy.reconcile(latest, entity))
    }

    fun remove(timerId: String, generation: String) = synchronized(lock) {
        if (get(timerId)?.generation == generation) prefs.edit().remove(timerId).commit()
    }

    companion object { private val lock = Any() }
}
