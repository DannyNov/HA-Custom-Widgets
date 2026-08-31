package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.danila.hacustomwidgets.data.WidgetRepository
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.remote.EntitySubscriptionMode
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.remote.StateChangedWebSocketListener
import com.danila.hacustomwidgets.data.security.SecureConnectionStore
import com.danila.hacustomwidgets.widget.EntityWidgetRenderCoordinator
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.WebSocket

/** One process-scoped HA transport shared by every Dashboard and Device widget. */
class DashboardEventCoordinator(
    context: Context,
    private val connectionStore: SecureConnectionStore,
    private val client: HomeAssistantClient,
    private val dashboards: DashboardRepository,
    private val widgets: WidgetRepository,
    private val widgetRenders: EntityWidgetRenderCoordinator,
) {
    private val appContext = context.applicationContext
    private val syncPrefs = appContext.getSharedPreferences("dashboard_sync_freshness", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Unit>(Channel.CONFLATED)
    private val reconcileMutex = Mutex()
    private val stateLock = Any()
    private val generationCounter = AtomicLong()
    private val commandCounter = AtomicLong(10_000L)
    private val counters = RealtimeResourceCounters()

    @Volatile private var socketState = DashboardSocketState.IDLE
    @Volatile private var socketGeneration = 0L
    @Volatile private var connectionId = "none"
    @Volatile private var serverId = "none"
    @Volatile private var currentSocket: WebSocket? = null
    @Volatile private var currentSubscriptionId = 0
    @Volatile private var subscriptionMode = EntitySubscriptionMode.STATE_CHANGED
    @Volatile private var subscribedEntities = emptySet<String>()
    @Volatile private var lastMessageAt = 0L
    @Volatile private var lastEventAt = 0L
    @Volatile private var reconnectAttempt = 0
    private val systemBinding = RealtimeBindingState()
    @Volatile private var screenInteractive = true
    private var stageDeadlineJob: Job? = null

    init { scope.launch { connectionLoop() } }

    fun start() = ensureStarted("START")

    fun wakeAsync(reason: String, reconcileIfStale: Boolean = true) {
        scope.launch { ensureStarted(reason, reconcileIfStale) }
    }

    fun ensureStarted(reason: String, reconcileIfStale: Boolean = true) {
        val ids = desiredEntityIds()
        when {
            ids.isEmpty() -> log("WS_START_SKIPPED", "reason=no_widgets source=$reason")
            connectionStore.load() == null -> log("WS_START_SKIPPED", "reason=no_connection source=$reason")
            !screenInteractive -> log("WS_START_SKIPPED", "reason=screen_off source=$reason")
            socketState !in ACTIVE_STATES -> {
                log("WS_START", "source=$reason entityCount=${ids.size}")
                commands.trySend(Unit)
            }
            socketState == DashboardSocketState.SUBSCRIBED && ids != subscribedEntities ->
                replaceSubscription("WAKE_$reason", ids)
            else -> log("WS_START_SKIPPED", "reason=already_${socketState.name.lowercase()} source=$reason")
        }
        if (reconcileIfStale) requestReconciliation("WAKE_$reason", force = false)
    }

    fun systemBindingConnected(bindingId: String) {
        val changed = systemBinding.connected(bindingId)
        log("NLS_CONNECTED", "duplicate=${!changed}")
        scope.launch { ensureStarted("NLS_CONNECTED", reconcileIfStale = false) }
    }

    fun systemBindingLost(bindingId: String) {
        if (!systemBinding.disconnected(bindingId)) {
            log("NLS_DISCONNECTED", "ignored=stale bindingId=$bindingId")
            return
        }
        log("NLS_DISCONNECTED", "bindingId=$bindingId")
        invalidateGeneration("nls_disconnected", reconnect = false)
    }

    fun screenInteractiveChanged(interactive: Boolean) {
        screenInteractive = interactive
        if (interactive) ensureStarted("SCREEN_ON", reconcileIfStale = false)
        else invalidateGeneration("screen_off", reconnect = false)
    }

    fun connectivityChanged() {
        if (!screenInteractive || desiredEntityIds().isEmpty()) return
        if (socketState in ACTIVE_STATES || currentSocket != null) {
            invalidateGeneration("connectivity_change", reconnect = true)
        } else ensureStarted("CONNECTIVITY_AVAILABLE", reconcileIfStale = true)
    }

    fun subscriptionSetChanged(reason: String) {
        scope.launch {
            val ids = desiredEntityIds()
            counters.subscriptionChanges.incrementAndGet()
            log("SUBSCRIPTION_SET_CHANGED", "reason=$reason entityCount=${ids.size}")
            when {
                ids.isEmpty() -> stopIfUnused()
                socketState == DashboardSocketState.SUBSCRIBED && ids != subscribedEntities ->
                    replaceSubscription(reason, ids)
                else -> ensureStarted(reason, reconcileIfStale = false)
            }
        }
    }

    fun evaluateAfterManualRefresh() {
        if (RealtimeSubscriptionPolicy.reconnectAfterManualRefresh(systemBinding.isConnected, socketState)) {
            invalidateGeneration("manual_refresh", reconnect = true)
        } else ensureStarted("MANUAL_REFRESH", reconcileIfStale = false)
    }

    fun workerStarted(source: String) {
        counters.workerStarts.incrementAndGet()
        log("RESOURCE_SNAPSHOT", "source=$source ${counters.snapshot()}")
    }

    fun requestReconciliation(reason: String, force: Boolean = false, appWidgetId: Int? = null) {
        scope.launch { reconcileNow(reason, force, appWidgetId) }
    }

    suspend fun reconcileNow(
        reason: String,
        force: Boolean = false,
        appWidgetId: Int? = null,
        source: DashboardStateSource = DashboardStateSource.RECONCILIATION,
    ): Boolean {
        val requestedAt = System.currentTimeMillis()
        return reconcileMutex.withLock {
        val dashboardConfigs = dashboards.all().filter { appWidgetId == null || it.appWidgetId == appWidgetId }
        val deviceConfigs = widgets.all().filter { appWidgetId == null || it.appWidgetId == appWidgetId }
        if (dashboardConfigs.isEmpty() && deviceConfigs.isEmpty()) return@withLock true
        val now = System.currentTimeMillis()
        val allIds = dashboardConfigs.map { it.appWidgetId } + deviceConfigs.map { it.appWidgetId }
        if (allIds.all { lastSyncAt(it) >= requestedAt } ||
            (!force && allIds.all { DashboardEventPolicy.isSnapshotFresh(lastSyncAt(it), now) })
        ) return@withLock true
        val started = SystemClock.elapsedRealtime()
        counters.restReconciliations.incrementAndGet()
        log("RECONCILE_START", "source=$reason dashboards=${dashboardConfigs.size} deviceWidgets=${deviceConfigs.size}")
        try {
            val connection = connectionStore.load() ?: return@withLock false
            val normal = dashboardConfigs.filterNot { dashboards.requiresCatalogRefresh(it.appWidgetId) }
            val ids = (normal.flatMap { dashboards.entityIds(it.appWidgetId) } +
                deviceConfigs.flatMap { config -> config.metrics.map { it.entityId } }).distinct()
            val byId = if (ids.isEmpty()) emptyMap() else client.getEntities(connection, ids).associateBy(HaEntity::entityId)
            normal.forEach { config ->
                val entities = dashboards.entityIds(config.appWidgetId).mapNotNull(byId::get)
                if (entities.isNotEmpty()) dashboards.updateEntityStates(config.appWidgetId, entities, source)
            }
            dashboardConfigs.filter { it !in normal }.forEach { config ->
                dashboards.updateFromCatalog(config.appWidgetId, client.getCatalog(connection))
            }
            deviceConfigs.forEach { config ->
                widgets.updateStates(config.appWidgetId, config.metrics.mapNotNull { byId[it.entityId] })
                widgetRenders.request(config.appWidgetId, "RECONCILIATION")
            }
            val completedAt = System.currentTimeMillis()
            syncPrefs.edit().also { editor ->
                allIds.forEach { editor.putLong(syncKey(it), completedAt) }
            }.apply()
            log("RECONCILE_END", "source=$reason success=true entities=${ids.size} durationMs=${SystemClock.elapsedRealtime() - started}")
            true
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.w(TAG, context("RECONCILE_END", "source=$reason success=false durationMs=${SystemClock.elapsedRealtime() - started}"), error)
            false
        }
        }
    }

    fun stopIfUnused() {
        if (desiredEntityIds().isNotEmpty()) return
        invalidateGeneration("no_widgets", reconnect = false)
        socketState = DashboardSocketState.STOPPED
    }

    internal fun snapshot() = DashboardSocketSnapshot(socketState, socketGeneration, connectionId, lastMessageAt, lastEventAt)

    private fun desiredEntityIds(): Set<String> =
        (dashboards.all().flatMap { dashboards.entityIds(it.appWidgetId) } + widgets.entityIds()).toSet()

    private suspend fun connectionLoop() {
        while (scope.isActive) {
            commands.receive()
            while (scope.isActive && desiredEntityIds().isNotEmpty() && screenInteractive) {
                if (socketState in ACTIVE_STATES) break
                val delayMs = if (socketState == DashboardSocketState.BACKOFF) {
                    DashboardEventPolicy.reconnectDelayMs((reconnectAttempt - 1).coerceAtLeast(0))
                } else 0L
                if (delayMs > 0) {
                    log("WS_RECONNECT", "attempt=$reconnectAttempt delayMs=$delayMs")
                    delay(delayMs)
                }
                if (!screenInteractive || desiredEntityIds().isEmpty()) break
                if (socketState in ACTIVE_STATES) break
                try { openGeneration(); break }
                catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    reconnectAttempt++
                    socketState = DashboardSocketState.BACKOFF
                    Log.w(TAG, context("WS_DISCONNECTED", "source=open attempt=$reconnectAttempt"), error)
                    if (reconnectAttempt == 1) requestReconciliation("WS_OPEN_FAILURE", force = false)
                }
            }
        }
    }

    private fun openGeneration() {
        val connection = connectionStore.load() ?: return
        serverId = stableServerId(connection.baseUrl)
        val generation = generationCounter.incrementAndGet()
        val id = UUID.randomUUID().toString()
        synchronized(stateLock) {
            socketGeneration = generation
            connectionId = id
            socketState = DashboardSocketState.CONNECTING
            currentSocket = null
            currentSubscriptionId = 0
            subscribedEntities = emptySet()
            lastMessageAt = System.currentTimeMillis()
        }
        log("WS_CONNECT_START")
        scheduleDeadline(generation, DashboardSocketState.CONNECTING, DashboardEventPolicy.CONNECT_TIMEOUT_MS, "connect_timeout")
        val opened = client.openStateChangedWebSocket(connection, listener(generation, id))
        synchronized(stateLock) { if (socketGeneration == generation && currentSocket == null) currentSocket = opened }
    }

    private fun listener(generation: Long, id: String) = object : StateChangedWebSocketListener {
        override fun onOpen(socket: WebSocket) {
            if (!claimSocket(socket, generation, id)) return stale("onOpen", generation, id)
            counters.wsConnectionsOpened.incrementAndGet()
            transition(generation, DashboardSocketState.AUTHENTICATING)
            log("WS_CONNECTED")
            scheduleDeadline(generation, DashboardSocketState.AUTHENTICATING, DashboardEventPolicy.AUTH_REQUIRED_TIMEOUT_MS, "auth_required_timeout")
        }
        override fun onMessage(socket: WebSocket, type: String) {
            if (!isCurrent(socket, generation, id)) return stale("onMessage:$type", generation, id)
            lastMessageAt = System.currentTimeMillis()
        }
        override fun onAuthRequired(socket: WebSocket) {
            if (!isCurrent(socket, generation, id)) return stale("auth_required", generation, id)
            scheduleDeadline(generation, DashboardSocketState.AUTHENTICATING, DashboardEventPolicy.AUTH_OK_TIMEOUT_MS, "auth_ok_timeout")
        }
        override fun onAuthOk(socket: WebSocket, haVersion: String) {
            if (!isCurrent(socket, generation, id)) return stale("auth_ok", generation, id)
            log("WS_AUTH_OK", "haVersion=$haVersion")
            subscriptionMode = if (DashboardEventPolicy.supportsSubscribeEntities(haVersion)) {
                EntitySubscriptionMode.SUBSCRIBE_ENTITIES
            } else EntitySubscriptionMode.STATE_CHANGED
            sendSubscription(socket, generation, id, desiredEntityIds(), subscriptionMode)
        }
        override fun onAuthInvalid(socket: WebSocket) {
            if (!isCurrent(socket, generation, id)) return stale("auth_invalid", generation, id)
            stopGeneration(socket, generation, id, "auth_invalid")
        }
        override fun onSubscriptionResult(socket: WebSocket, subscriptionId: Int, success: Boolean, error: String?) {
            if (!isCurrent(socket, generation, id) || subscriptionId != currentSubscriptionId) {
                return stale("subscription_result", generation, id)
            }
            if (!success && subscriptionMode == EntitySubscriptionMode.SUBSCRIBE_ENTITIES) {
                subscriptionMode = EntitySubscriptionMode.STATE_CHANGED
                log("WS_SUBSCRIBE_START", "fallback=state_changed error=${error.orEmpty()}")
                sendSubscription(socket, generation, id, desiredEntityIds(), subscriptionMode)
                return
            }
            if (!success) return failGeneration(socket, generation, id, "subscribe_rejected", null)
            stageDeadlineJob?.cancel()
            reconnectAttempt = 0
            transition(generation, DashboardSocketState.SUBSCRIBED)
            log("WS_SUBSCRIBED", "subscriptionMode=$subscriptionMode entityCount=${subscribedEntities.size}")
            val latestEntities = desiredEntityIds()
            if (latestEntities.isEmpty()) {
                stopIfUnused()
                return
            }
            if (latestEntities != subscribedEntities) {
                replaceSubscription("CONFIG_CHANGED_DURING_SUBSCRIBE", latestEntities)
                return
            }
            if (subscriptionMode == EntitySubscriptionMode.STATE_CHANGED) requestReconciliation("WS_SUBSCRIBED_FALLBACK", true)
            log("RESOURCE_SNAPSHOT", counters.snapshot())
        }
        override fun onEntities(socket: WebSocket, subscriptionId: Int, entities: List<HaEntity>, initial: Boolean) {
            if (!isCurrent(socket, generation, id) || subscriptionId != currentSubscriptionId) {
                return stale("entities", generation, id)
            }
            if (entities.isEmpty()) return
            lastEventAt = System.currentTimeMillis()
            if (initial) counters.wsInitialStatesReceived.incrementAndGet()
            else counters.wsEventsReceived.addAndGet(entities.size.toLong())
            log(if (initial) "WS_INITIAL_STATE" else "WS_EVENT_RECEIVED", "entityCount=${entities.size}")
            scope.launch {
                applyAuthoritative(entities, if (initial) "WS_INITIAL_STATE" else "WS_EVENT", initial)
            }
        }
        override fun onClosed(socket: WebSocket, code: Int, reason: String) =
            failGeneration(socket, generation, id, "closed code=$code", null)
        override fun onFailure(socket: WebSocket, error: Throwable) =
            failGeneration(socket, generation, id, "failure", error)
    }

    private fun sendSubscription(socket: WebSocket, generation: Long, id: String, ids: Set<String>, mode: EntitySubscriptionMode) {
        if (!isCurrent(socket, generation, id)) return
        currentSubscriptionId = commandCounter.incrementAndGet().toInt()
        subscribedEntities = ids
        transition(generation, DashboardSocketState.SUBSCRIBING)
        log("WS_SUBSCRIBE_START", "subscriptionMode=$mode entityCount=${ids.size}")
        if (!client.subscribeEntities(socket, currentSubscriptionId, ids, mode)) {
            failGeneration(socket, generation, id, "subscribe_send_failed", null)
            return
        }
        scheduleDeadline(generation, DashboardSocketState.SUBSCRIBING, DashboardEventPolicy.SUBSCRIBE_TIMEOUT_MS, "subscribe_timeout")
    }

    private fun replaceSubscription(reason: String, ids: Set<String>) {
        val socket = currentSocket ?: return
        val generation = socketGeneration
        val id = connectionId
        if (currentSubscriptionId != 0) {
            client.unsubscribe(socket, commandCounter.incrementAndGet().toInt(), currentSubscriptionId)
        }
        counters.subscriptionChanges.incrementAndGet()
        log("SUBSCRIPTION_SET_CHANGED", "reason=$reason oldCount=${subscribedEntities.size} entityCount=${ids.size}")
        sendSubscription(socket, generation, id, ids, subscriptionMode)
    }

    private suspend fun applyAuthoritative(entities: List<HaEntity>, reason: String, initial: Boolean) {
        val byId = entities.associateBy { it.entityId }
        val dashboardIds = entities.flatMap { dashboards.widgetsContainingEntity(it.entityId) }.distinct()
        dashboardIds.forEach { widgetId ->
            val relevant = dashboards.entityIds(widgetId).mapNotNull(byId::get)
            if (relevant.isNotEmpty()) dashboards.updateEntityStates(widgetId, relevant, DashboardStateSource.EVENT)
        }
        counters.renders.addAndGet(dashboardIds.size.toLong())
        val deviceIds = entities.flatMap { widgets.widgetsContainingEntity(it.entityId) }.distinct()
        deviceIds.forEach { widgetId ->
            val relevant = widgets.get(widgetId)?.metrics.orEmpty().mapNotNull { byId[it.entityId] }
            if (relevant.isNotEmpty()) {
                widgets.updateStates(widgetId, relevant)
                widgetRenders.request(widgetId, reason)
                counters.renders.incrementAndGet()
            }
        }
        if (initial) {
            val confirmedAt = System.currentTimeMillis()
            syncPrefs.edit().also { editor ->
                (dashboards.all().map { it.appWidgetId } + widgets.all().map { it.appWidgetId })
                    .distinct()
                    .forEach { editor.putLong(syncKey(it), confirmedAt) }
            }.apply()
        }
    }

    private fun stopGeneration(socket: WebSocket, generation: Long, id: String, reason: String) {
        val accepted = synchronized(stateLock) {
            if (socketGeneration != generation || connectionId != id || currentSocket !== socket) false
            else {
                stageDeadlineJob?.cancel()
                currentSocket = null
                socketState = DashboardSocketState.STOPPED
                true
            }
        }
        if (!accepted) return stale("stop:$reason", generation, id)
        socket.cancel()
        log("WS_DISCONNECTED", "reason=$reason reconnect=false")
    }

    private fun invalidateGeneration(reason: String, reconnect: Boolean) {
        val socket = synchronized(stateLock) {
            stageDeadlineJob?.cancel()
            currentSocket.also {
                currentSocket = null
                socketState = if (reconnect) DashboardSocketState.BACKOFF else DashboardSocketState.IDLE
                if (reconnect) reconnectAttempt++
            }
        }
        socket?.cancel()
        log("WS_DISCONNECTED", "reason=$reason reconnect=$reconnect")
        if (reconnect) { counters.wsReconnects.incrementAndGet(); commands.trySend(Unit) }
    }

    private fun failGeneration(socket: WebSocket, generation: Long, id: String, reason: String, error: Throwable?) {
        val accepted = synchronized(stateLock) {
            if (socketGeneration != generation || connectionId != id || currentSocket !== socket) false
            else {
                stageDeadlineJob?.cancel()
                currentSocket = null
                socketState = DashboardSocketState.BACKOFF
                reconnectAttempt++
                true
            }
        }
        if (!accepted) return stale("failure:$reason", generation, id)
        socket.cancel()
        counters.wsReconnects.incrementAndGet()
        if (error == null) log("WS_DISCONNECTED", "reason=$reason")
        else Log.w(TAG, context("WS_DISCONNECTED", "reason=$reason"), error)
        if (reconnectAttempt == 1) requestReconciliation("WS_FAILURE", force = false)
        commands.trySend(Unit)
    }

    private fun claimSocket(socket: WebSocket, generation: Long, id: String): Boolean = synchronized(stateLock) {
        if (socketGeneration != generation || connectionId != id) false
        else { if (currentSocket == null) currentSocket = socket; currentSocket === socket }
    }
    private fun isCurrent(socket: WebSocket, generation: Long, id: String): Boolean = synchronized(stateLock) {
        socketGeneration == generation && connectionId == id && currentSocket === socket
    }
    private fun transition(generation: Long, state: DashboardSocketState) = synchronized(stateLock) {
        if (socketGeneration == generation) socketState = state
    }
    private fun scheduleDeadline(generation: Long, expected: DashboardSocketState, timeoutMs: Long, reason: String) {
        stageDeadlineJob?.cancel()
        stageDeadlineJob = scope.launch {
            delay(timeoutMs)
            val socket = synchronized(stateLock) {
                currentSocket?.takeIf { socketGeneration == generation && socketState == expected }
            } ?: return@launch
            failGeneration(socket, generation, connectionId, reason, null)
        }
    }
    private fun stale(callback: String, generation: Long, id: String) =
        Log.d(TAG, context("WS_STALE_CALLBACK", "callback=$callback staleGeneration=$generation staleConnectionId=$id"))
    private fun lastSyncAt(id: Int) = syncPrefs.getLong(syncKey(id), 0L)
    private fun syncKey(id: Int) = "widget_${id}_last_confirmed_sync"
    private fun log(event: String, fields: String = "") = Log.d(TAG, context(event, fields))
    private fun context(event: String, fields: String = "") =
        "$event processStartId=${DashboardDiagnostics.processStartId} socketGeneration=$socketGeneration " +
            "connectionId=$connectionId serverId=$serverId state=$socketState nlsBound=${systemBinding.isConnected} " +
            "monotonicMs=${SystemClock.elapsedRealtime()} $fields"

    private fun stableServerId(baseUrl: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(baseUrl.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val TAG = "HAWidgetRealtime"
        private val ACTIVE_STATES = setOf(
            DashboardSocketState.CONNECTING, DashboardSocketState.AUTHENTICATING,
            DashboardSocketState.SUBSCRIBING, DashboardSocketState.SUBSCRIBED,
        )
    }
}

internal class RealtimeResourceCounters {
    val wsConnectionsOpened = AtomicLong()
    val wsReconnects = AtomicLong()
    val wsEventsReceived = AtomicLong()
    val wsInitialStatesReceived = AtomicLong()
    val subscriptionChanges = AtomicLong()
    val restReconciliations = AtomicLong()
    val renders = AtomicLong()
    val workerStarts = AtomicLong()
    fun snapshot() = "connectionsOpened=${wsConnectionsOpened.get()} reconnects=${wsReconnects.get()} " +
        "events=${wsEventsReceived.get()} initialStates=${wsInitialStatesReceived.get()} " +
        "subscriptionChanges=${subscriptionChanges.get()} reconciliations=${restReconciliations.get()} " +
        "renders=${renders.get()} workers=${workerStarts.get()}"
}
