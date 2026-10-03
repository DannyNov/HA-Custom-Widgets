package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.LightBrightness
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.security.SecureConnectionStore
import com.danila.hacustomwidgets.tr
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val sendColor: suspend (com.danila.hacustomwidgets.data.security.HomeAssistantConnection, String, Map<String, *>) -> Unit = { _, _, _ -> error("Color transport unavailable") },
    private val colorFailure: (String) -> Unit = failure,
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
        sendColor = { c, id, data -> client.callService(c, "light", "turn_on", id, data) },
        colorFailure = { id -> dashboards.widgetsContainingEntity(id).forEach {
            dashboards.saveError(it, tr("Color change failed. Showing confirmed state.", "Не удалось изменить цвет. Показано подтверждённое состояние."))
        } },
    )
    private val mutex = Mutex()
    private data class Key(val connection: com.danila.hacustomwidgets.data.security.HomeAssistantConnection, val entity: String)
    private data class Pending(val target: Int, val generation: Long)
    private val latest = ConcurrentHashMap<Key, Pending>()
    private val networkGates = ConcurrentHashMap<Key, Mutex>()
    private val running = mutableSetOf<Key>()
    private var generation = 0L
    private data class ColorPending(val data: Map<String, *>, val generation: Long,
        val matches: (LightBrightness) -> Boolean)
    private val colorLatest = ConcurrentHashMap<Key, ColorPending>()
    private val colorRunning = mutableSetOf<Key>()
    private val revision = MutableStateFlow(0L)
    val revisions = revision.asStateFlow()
    fun state(entityId: String): VersionedEntityState? = truth(entityId)
    fun temperatureTarget(entityId: String): Int? = connection()?.let { colorLatest[Key(it, entityId)]?.data?.get("color_temp_kelvin") as? Int }
    fun colorTarget(entityId: String): com.danila.hacustomwidgets.data.model.LightColor? = connection()?.let {
        colorLatest[Key(it, entityId)]?.data?.get("hs_color")?.let { value ->
            com.danila.hacustomwidgets.data.model.LightColor.parse(org.json.JSONObject().put("hs_color", value))
        }
    }

    private fun publish(entityId: String) {
        revision.update { it + 1 }
        changed(entityId)
    }

    /** Read the operation and HA truth directly, never a delayed Dashboard projection. */
    fun displayPercent(entityId: String): Int? = overlay(entityId) ?: truth(entityId)?.let {
        it.brightness.displayPercent(it.confirmedRawState, it.lastConfirmedBrightness)
    }

    fun overlay(entityId: String): Int? {
        if (latest.keys.none { it.entity == entityId }) return null
        return connection()?.let { latest[Key(it, entityId)]?.target }
    }

    fun invalidate() {
        val ids = latest.keys.map { it.entity }
        latest.clear()
        val colorIds = colorLatest.keys.map { it.entity }
        colorLatest.clear()
        colorIds.forEach(::publish)
        ids.forEach(::publish)
    }

    suspend fun power(entityId: String, action: suspend () -> Unit) {
        val key = connection()?.let { Key(it, entityId) } ?: return
        latest.remove(key)
        colorLatest.remove(key)
        publish(entityId)
        networkGates.getOrPut(key) { Mutex() }.withLock { action() }
    }

    /** Temperature and color share one mode queue: a newer mode target supersedes the older. */
    fun temperature(entityId: String, kelvin: Int): Job = submitColor(entityId) { observed ->
        val range = observed.kelvinRange?.takeIf { observed.temperatureCapable } ?: return@submitColor null
        val target = range.clamp(kelvin)
        ColorPending(mapOf("color_temp_kelvin" to target), ++generation) {
            it.colorMode == "color_temp" && it.temperatureKelvin?.let { value -> kotlin.math.abs(value - target) <= 25 } == true
        }
    }

    fun color(entityId: String, color: com.danila.hacustomwidgets.data.model.LightColor): Job = submitColor(entityId) { observed ->
        if (!observed.colorCapable) return@submitColor null
        ColorPending(color.serviceData(), ++generation) {
            it.colorMode in com.danila.hacustomwidgets.data.model.LightColor.MODES && it.color?.let { actual ->
                val difference = kotlin.math.abs(actual.hue - color.hue)
                (color.saturation < 1 || minOf(difference, 360 - difference) <= 3) &&
                    kotlin.math.abs(actual.saturation - color.saturation) <= 3
            } == true
        }
    }

    private fun submitColor(entityId: String, target: (LightBrightness) -> ColorPending?): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val c = connection() ?: return@launch
            val key = Key(c, entityId)
            mutex.withLock {
                val state = truth(entityId) ?: return@withLock
                if (state.optimisticOverlay != null || state.confirmedRawState !in setOf("on", "off")) return@withLock
                val pending = target(state.brightness) ?: return@withLock
                colorLatest[key] = pending
                publish(entityId)
                if (colorRunning.add(key)) scope.launch { drainColor(key) }
            }
        }

    private suspend fun drainColor(key: Key) {
        var attempted: ColorPending? = null
        var discard = false
        try {
            while (true) {
                delay(coalesceMs)
                if (connection() != key.connection) { discard = true; break }
                val sent = colorLatest[key] ?: break
                attempted = sent
                val baseline = truth(key.entity)?.confirmedHaLastUpdatedMillis
                networkGates.getOrPut(key) { Mutex() }.withLock {
                    val state = truth(key.entity)
                    val targetKelvin = sent.data["color_temp_kelvin"] as? Int
                    val capable = state?.brightness?.let {
                        if (targetKelvin != null) it.temperatureCapable && it.kelvinRange?.clamp(targetKelvin) == targetKelvin
                        else it.colorCapable
                    } == true
                    if (state == null || !capable || state.optimisticOverlay != null || state.confirmedRawState !in setOf("on", "off")) {
                        discard = true
                    } else if (colorLatest[key] == sent && connection() == key.connection) {
                        sendColor(key.connection, key.entity, sent.data)
                    }
                }
                if (discard) break
                if (colorLatest[key] != sent) continue
                var confirmed = false
                repeat(confirmationTicks) {
                    if (confirmed || colorLatest[key] != sent) return@repeat
                    delay(tickMs)
                    val state = truth(key.entity)
                    confirmed = state?.confirmedRawState == "on" &&
                        (baseline == null || (state.confirmedHaLastUpdatedMillis ?: Long.MIN_VALUE) > baseline) && sent.matches(state.brightness)
                }
                if (colorLatest[key] != sent) continue
                if (!confirmed) {
                    refresh(key.connection, key.entity)
                    if (connection() != key.connection) { discard = true; break }
                    val state = truth(key.entity)
                    confirmed = state?.confirmedRawState == "on" && sent.matches(state.brightness)
                }
                if (colorLatest[key] != sent) continue
                if (!confirmed) throw IllegalStateException("color confirmation timeout")
                colorLatest.remove(key, sent)
                publish(key.entity)
                if (!colorLatest.containsKey(key)) break
            }
        } catch (cancelled: CancellationException) {
            discard = true
            throw cancelled
        } catch (_: Exception) {
            if (attempted?.let { colorLatest.remove(key, it) } == true) colorFailure(key.entity)
        } finally {
            withContext(NonCancellable) { mutex.withLock {
                if (discard) colorLatest.remove(key)
                if (colorLatest.containsKey(key)) scope.launch { drainColor(key) } else colorRunning.remove(key)
                publish(key.entity)
            } }
        }
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
                publish(entityId)
                if (running.add(key)) scope.launch { drain(key) }
            }
        }

    private suspend fun drain(key: Key) {
        var discard = false
        var attempted: Pending? = null
        try {
            while (true) {
                delay(coalesceMs)
                if (connection() != key.connection) { discard = true; break }
                val sent = latest[key] ?: break
                attempted = sent
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
                publish(key.entity)
                if (!latest.containsKey(key)) break
            }
        } catch (cancelled: CancellationException) {
            discard = true
            throw cancelled
        } catch (_: Exception) {
            // A failure of an older HTTP call must not revoke a newer submitted target.
            if (attempted?.let { latest.remove(key, it) } == true) failure(key.entity)
        } finally {
            withContext(NonCancellable) { mutex.withLock {
                if (discard) latest.remove(key)
                if (latest.containsKey(key)) scope.launch { drain(key) } else running.remove(key)
                publish(key.entity)
            } }
        }
    }
}
