package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.LightBrightness
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.security.SecureConnectionStore
import com.danila.hacustomwidgets.tr
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ephemeral commands: never restore/retry a queued target after process death. */
class BrightnessCoordinator internal constructor(
    private val connection: () -> com.danila.hacustomwidgets.data.security.HomeAssistantConnection?,
    private val truth: (String) -> VersionedEntityState?,
    private val changed: (String) -> Unit,
    private val failure: (String) -> Unit,
    private val send: suspend (com.danila.hacustomwidgets.data.security.HomeAssistantConnection, String, Int) -> Unit,
    private val refresh: suspend (com.danila.hacustomwidgets.data.security.HomeAssistantConnection, String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val coalesceMs: Long = 300,
    private val confirmationTicks: Int = 20,
    private val tickMs: Long = 200,
) {
    constructor(connections: SecureConnectionStore, client: HomeAssistantClient, dashboards: DashboardRepository) : this(
        connection = connections::load, truth = dashboards::brightnessTruth, changed = dashboards::brightnessChanged,
        failure = { id -> dashboards.widgetsContainingEntity(id).forEach {
            dashboards.saveError(it, tr("Brightness change failed. Showing confirmed state.", "Не удалось изменить яркость. Показано подтверждённое состояние."))
        } },
        send = { c, id, target -> client.callService(c, "light", "turn_on", id, mapOf("brightness_pct" to target)) },
        refresh = { c, id ->
            val entity = client.getEntity(c, id)
            if (connections.load() == c) dashboards.widgetsContainingEntity(id).forEach {
                dashboards.updateEntityStates(it, listOf(entity), DashboardStateSource.RECONCILIATION)
            }
        },
    )
    private val mutex = Mutex()
    private data class Key(val connection: com.danila.hacustomwidgets.data.security.HomeAssistantConnection, val entity: String)
    private data class Pending(val target: Int, val generation: Long)
    private val latest = ConcurrentHashMap<Key, Pending>()
    private val networkGates = ConcurrentHashMap<Key, Mutex>()
    private val running = mutableSetOf<Key>()
    private var generation = 0L

    fun overlay(entityId: String): Int? = connection()?.let { latest[Key(it, entityId)]?.target }

    fun invalidate() {
        val ids = latest.keys.map { it.entity }
        latest.clear()
        ids.forEach(changed)
    }

    suspend fun power(entityId: String, action: suspend () -> Unit) {
        val key = connection()?.let { Key(it, entityId) } ?: return
        latest.remove(key)
        changed(entityId)
        networkGates.getOrPut(key) { Mutex() }.withLock { action() }
    }

    fun submit(entityId: String, step: Int? = null, absolute: Int? = null): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val activeConnection = connection() ?: return@launch
            val key = Key(activeConnection, entityId)
            mutex.withLock {
                val observed = truth(entityId) ?: return@withLock
                if (observed.optimisticOverlay != null) return@withLock
                if (!observed.brightness.capable || observed.confirmedRawState !in setOf("on", "off")) return@withLock
                val base = latest[key]?.target ?: observed.brightness.displayPercent(observed.confirmedRawState, observed.lastConfirmedBrightness)
                    ?: return@withLock
                val target = absolute?.coerceIn(1, 100) ?: LightBrightness.step(base, step ?: return@withLock)
                latest[key] = Pending(target, ++generation)
                changed(entityId)
                if (running.add(key)) scope.launch { drain(key) }
            }
        }

    private suspend fun drain(key: Key) {
        var discard = false
        try {
            while (true) {
                delay(coalesceMs)
                if (connection() != key.connection) { discard = true; break }
                val sent = latest[key] ?: break
                val observed = truth(key.entity)
                if (observed == null || !observed.brightness.capable || observed.confirmedRawState !in setOf("on", "off")) { discard = true; break }
                val baseline = observed.confirmedHaLastUpdatedMillis
                networkGates.getOrPut(key) { Mutex() }.withLock {
                    if (latest[key] == sent && connection() == key.connection) send(key.connection, key.entity, sent.target)
                }
                // New targets are sent after the preceding HTTP call, without waiting for its echo.
                if (latest[key] != sent) continue
                var confirmed = false
                repeat(confirmationTicks) {
                    if (latest[key] != sent || confirmed) return@repeat
                    delay(tickMs)
                    val observed = truth(key.entity)
                    confirmed = observed?.confirmedRawState == "on" &&
                        (baseline == null || (observed.confirmedHaLastUpdatedMillis ?: Long.MIN_VALUE) > baseline) &&
                        LightBrightness.percent(observed.brightness.value)?.let { kotlin.math.abs(it - sent.target) <= 1 } == true
                }
                if (latest[key] != sent) continue
                if (!confirmed) {
                    // One bounded fallback, never a REST polling loop.
                    refresh(key.connection, key.entity)
                    if (connection() != key.connection) { discard = true; break }
                    val entity = truth(key.entity)
                    confirmed = entity?.confirmedRawState == "on" && LightBrightness.percent(entity.brightness.value)
                        ?.let { kotlin.math.abs(it - sent.target) <= 1 } == true
                }
                if (latest[key] != sent) continue
                if (!confirmed) throw IllegalStateException("brightness confirmation timeout")
                mutex.withLock { if (latest[key] == sent) latest.remove(key) }
                changed(key.entity)
                if (!latest.containsKey(key)) break
            }
        } catch (cancelled: CancellationException) {
            discard = true
            throw cancelled
        } catch (_: Exception) {
            discard = true
            failure(key.entity)
        } finally {
            withContext(NonCancellable) { mutex.withLock {
                if (discard) latest.remove(key)
                if (latest.containsKey(key)) scope.launch { drain(key) } else running.remove(key)
                changed(key.entity)
            } }
        }
    }
}
