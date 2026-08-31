package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.remote.StateChangedWebSocketListener
import com.danila.hacustomwidgets.data.security.SecureConnectionStore
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
import org.json.JSONObject

class DashboardEventCoordinator(
    context: Context,
    private val connectionStore: SecureConnectionStore,
    private val client: HomeAssistantClient,
    private val dashboards: DashboardRepository,
) {
    private val applicationContext = context.applicationContext
    private val syncPrefs = applicationContext.getSharedPreferences("dashboard_sync_freshness", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionCommands = Channel<Unit>(Channel.CONFLATED)
    private val reconcileMutex = Mutex()
    private val stateLock = Any()
    private val generationCounter = AtomicLong(0L)
    private val heartbeatId = AtomicLong(10_000L)

    @Volatile private var socketState = DashboardSocketState.IDLE
    @Volatile private var socketGeneration = 0L
    @Volatile private var connectionId = "none"
    @Volatile private var currentSocket: WebSocket? = null
    @Volatile private var lastMessageAt = 0L
    @Volatile private var lastEventAt = 0L
    @Volatile private var reconnectAttempt = 0
    private var stageDeadlineJob: Job? = null
    private var heartbeatJob: Job? = null

    init {
        scope.launch { connectionLoop() }
    }

    fun start() = ensureStarted("START")

    fun ensureStarted(reason: String, reconcileIfStale: Boolean = true) {
        if (dashboards.all().isEmpty()) {
            log("WS_START_SKIPPED", "reason=no_dashboards source=$reason")
            return
        }
        if (connectionStore.load() == null) {
            log("WS_START_SKIPPED", "reason=no_connection source=$reason")
            return
        }
        val state = socketState
        if (state in ACTIVE_STATES) {
            log("WS_START_SKIPPED", "reason=already_${state.name.lowercase()} source=$reason")
        } else {
            log("WS_START", "source=$reason")
            connectionCommands.trySend(Unit)
        }
        if (reconcileIfStale) requestReconciliation("WAKE_$reason", force = false)
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
            val configs = dashboards.all().filter { appWidgetId == null || it.appWidgetId == appWidgetId }
            if (configs.isEmpty()) return@withLock true
            val now = System.currentTimeMillis()
            val alreadySatisfied = configs.all { lastSyncAt(it.appWidgetId) >= requestedAt }
            val allFresh = configs.all {
                DashboardEventPolicy.isSnapshotFresh(lastSyncAt(it.appWidgetId), now)
            }
            if (alreadySatisfied || (!force && allFresh)) {
                log("RECONCILE_SUCCESS", "source=$reason skipped=fresh widgets=${configs.size} durationMs=0")
                return@withLock true
            }
            val started = SystemClock.elapsedRealtime()
            log("RECONCILE_START", "source=$reason widgets=${configs.size}")
            try {
                val connection = connectionStore.load() ?: return@withLock false
                val normalConfigs = configs.filterNot { dashboards.requiresCatalogRefresh(it.appWidgetId) }
                val entityIds = normalConfigs.flatMap { dashboards.entityIds(it.appWidgetId) }.distinct()
                val byId = if (entityIds.isEmpty()) emptyMap() else {
                    client.getEntities(connection, entityIds).associateBy(HaEntity::entityId)
                }
                normalConfigs.forEach { config ->
                    val entities = dashboards.entityIds(config.appWidgetId).mapNotNull(byId::get)
                    if (entities.isNotEmpty()) dashboards.updateEntityStates(config.appWidgetId, entities, source)
                }
                configs.filter { it !in normalConfigs }.forEach { config ->
                    dashboards.updateFromCatalog(config.appWidgetId, client.getCatalog(connection))
                }
                val completedAt = System.currentTimeMillis()
                syncPrefs.edit().also { editor ->
                    configs.forEach { editor.putLong(syncKey(it.appWidgetId), completedAt) }
                }.apply()
                log(
                    "RECONCILE_SUCCESS",
                    "source=$reason widgets=${configs.size} entities=${entityIds.size} " +
                        "durationMs=${SystemClock.elapsedRealtime() - started}",
                )
                true
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(
                    TAG,
                    context("RECONCILE_FAILURE", "source=$reason durationMs=${SystemClock.elapsedRealtime() - started}"),
                    error,
                )
                false
            }
        }
    }

    fun stopIfUnused() {
        if (dashboards.all().isNotEmpty()) return
        val socket = synchronized(stateLock) {
            stageDeadlineJob?.cancel()
            heartbeatJob?.cancel()
            currentSocket.also {
                currentSocket = null
                socketState = DashboardSocketState.STOPPED
            }
        }
        socket?.close(1000, "no dashboards")
        log("WS_CLOSE", "reason=no_dashboards")
    }

    internal fun snapshot(): DashboardSocketSnapshot = DashboardSocketSnapshot(
        socketState, socketGeneration, connectionId, lastMessageAt, lastEventAt,
    )

    private suspend fun connectionLoop() {
        while (scope.isActive) {
            connectionCommands.receive()
            while (scope.isActive && dashboards.all().isNotEmpty()) {
                val state = socketState
                if (state in ACTIVE_STATES) break
                val delayMs = if (state == DashboardSocketState.BACKOFF) {
                    DashboardEventPolicy.reconnectDelayMs((reconnectAttempt - 1).coerceAtLeast(0))
                } else 0L
                if (delayMs > 0L) {
                    log("WS_RECONNECT_SCHEDULED", "attempt=$reconnectAttempt delayMs=$delayMs")
                    delay(delayMs)
                    log("WS_RECONNECT_START", "attempt=$reconnectAttempt")
                }
                try {
                    openGeneration()
                    break
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    reconnectAttempt += 1
                    socketState = DashboardSocketState.BACKOFF
                    Log.w(TAG, context("WS_FAILURE", "source=open attempt=$reconnectAttempt"), error)
                }
            }
        }
    }

    private fun openGeneration() {
        val connection = connectionStore.load() ?: return
        val generation = generationCounter.incrementAndGet()
        val id = UUID.randomUUID().toString()
        synchronized(stateLock) {
            socketGeneration = generation
            connectionId = id
            socketState = DashboardSocketState.CONNECTING
            currentSocket = null
            lastMessageAt = System.currentTimeMillis()
            lastEventAt = 0L
        }
        log("WS_CONNECTING", "source=connection_loop")
        scheduleStageDeadline(generation, DashboardSocketState.CONNECTING, DashboardEventPolicy.CONNECT_TIMEOUT_MS, "connect_timeout")
        val opened = client.openStateChangedWebSocket(connection, listener(generation, id))
        synchronized(stateLock) {
            if (socketGeneration == generation && currentSocket == null) currentSocket = opened
        }
    }

    private fun listener(generation: Long, id: String) = object : StateChangedWebSocketListener {
        override fun onOpen(socket: WebSocket) {
            if (!claimSocket(socket, generation, id)) return stale("onOpen", generation, id)
            transition(generation, DashboardSocketState.AUTHENTICATING)
            scheduleStageDeadline(
                generation, DashboardSocketState.AUTHENTICATING,
                DashboardEventPolicy.AUTH_REQUIRED_TIMEOUT_MS, "auth_required_timeout",
            )
        }

        override fun onMessage(socket: WebSocket, type: String) {
            if (!isCurrent(socket, generation, id)) return stale("onMessage:$type", generation, id)
            lastMessageAt = System.currentTimeMillis()
            log("WS_MESSAGE", "type=$type")
        }

        override fun onAuthRequired(socket: WebSocket) {
            if (!isCurrent(socket, generation, id)) return stale("auth_required", generation, id)
            log("WS_AUTH_REQUIRED")
            scheduleStageDeadline(
                generation, DashboardSocketState.AUTHENTICATING,
                DashboardEventPolicy.AUTH_OK_TIMEOUT_MS, "auth_ok_timeout",
            )
        }

        override fun onAuthOk(socket: WebSocket) {
            if (!isCurrent(socket, generation, id)) return stale("auth_ok", generation, id)
            log("WS_AUTH_OK")
            transition(generation, DashboardSocketState.SUBSCRIBING)
            scheduleStageDeadline(
                generation, DashboardSocketState.SUBSCRIBING,
                DashboardEventPolicy.SUBSCRIBE_TIMEOUT_MS, "subscribe_timeout",
            )
        }

        override fun onAuthInvalid(socket: WebSocket) {
            if (!isCurrent(socket, generation, id)) return stale("auth_invalid", generation, id)
            log("WS_AUTH_INVALID")
            failGeneration(socket, generation, id, "auth_invalid", null)
        }

        override fun onSubscribeSent(socket: WebSocket) {
            if (isCurrent(socket, generation, id)) log("WS_SUBSCRIBE_SENT")
        }

        override fun onSubscribed(socket: WebSocket) {
            if (!isCurrent(socket, generation, id)) return stale("subscribed", generation, id)
            stageDeadlineJob?.cancel()
            reconnectAttempt = 0
            transition(generation, DashboardSocketState.SUBSCRIBED)
            log("WS_SUBSCRIBE_OK")
            startHeartbeat(socket, generation, id)
            requestReconciliation("WS_SUBSCRIBED", force = true)
        }

        override fun onSubscribeRejected(socket: WebSocket) {
            if (!isCurrent(socket, generation, id)) return stale("subscribe_rejected", generation, id)
            log("WS_SUBSCRIBE_FAILED", "reason=rejected")
            failGeneration(socket, generation, id, "subscribe_rejected", null)
        }

        override fun onStateChanged(socket: WebSocket, entity: HaEntity) {
            if (!isCurrent(socket, generation, id)) return stale("state_changed", generation, id)
            lastEventAt = System.currentTimeMillis()
            log("EVENT_RECEIVED", "entityId=${entity.entityId} haState=${entity.state}")
            scope.launch {
                val widgetIds = dashboards.widgetsContainingEntity(entity.entityId)
                log("EVENT_MAPPED", "entityId=${entity.entityId} widgets=${widgetIds.size}")
                widgetIds.forEach { widgetId ->
                    runCatching {
                        dashboards.updateEntityStates(widgetId, listOf(entity), DashboardStateSource.EVENT)
                    }.onFailure {
                        Log.e(TAG, context("CONFIRMED_STATE_COMMIT", "appWidgetId=$widgetId entityId=${entity.entityId} failed=true"), it)
                    }
                }
            }
        }

        override fun onClosed(socket: WebSocket, code: Int, reason: String) {
            failGeneration(socket, generation, id, "closed code=$code", null)
        }

        override fun onFailure(socket: WebSocket, error: Throwable) {
            failGeneration(socket, generation, id, "failure", error)
        }
    }

    private fun claimSocket(socket: WebSocket, generation: Long, id: String): Boolean = synchronized(stateLock) {
        if (socketGeneration != generation || connectionId != id) return@synchronized false
        if (currentSocket == null) currentSocket = socket
        currentSocket === socket
    }

    private fun isCurrent(socket: WebSocket, generation: Long, id: String): Boolean = synchronized(stateLock) {
        socketGeneration == generation && connectionId == id && currentSocket === socket
    }

    private fun transition(generation: Long, target: DashboardSocketState) {
        synchronized(stateLock) {
            if (socketGeneration == generation) socketState = target
        }
    }

    private fun scheduleStageDeadline(
        generation: Long,
        expected: DashboardSocketState,
        timeoutMs: Long,
        reason: String,
    ) {
        stageDeadlineJob?.cancel()
        stageDeadlineJob = scope.launch {
            delay(timeoutMs)
            val currentId = connectionId
            val socket = synchronized(stateLock) {
                currentSocket?.takeIf { socketGeneration == generation && socketState == expected }
            } ?: return@launch
            if (expected == DashboardSocketState.SUBSCRIBING) log("WS_SUBSCRIBE_TIMEOUT")
            failGeneration(socket, generation, currentId, reason, null)
        }
    }

    private fun startHeartbeat(socket: WebSocket, generation: Long, id: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isCurrent(socket, generation, id)) {
                delay(DashboardEventPolicy.HEARTBEAT_INTERVAL_MS)
                val now = System.currentTimeMillis()
                if (DashboardEventPolicy.isWatchdogStale(socketState, lastMessageAt, now)) {
                    log("WS_WATCHDOG_STALE", "ageMs=${now - lastMessageAt}")
                    failGeneration(socket, generation, id, "watchdog_stale", null)
                    break
                }
                socket.send(
                    JSONObject().put("id", heartbeatId.incrementAndGet()).put("type", "ping").toString(),
                )
            }
        }
    }

    private fun failGeneration(
        socket: WebSocket,
        generation: Long,
        id: String,
        reason: String,
        error: Throwable?,
    ) {
        val accepted = synchronized(stateLock) {
            if (socketGeneration != generation || connectionId != id || currentSocket !== socket) {
                false
            } else {
                stageDeadlineJob?.cancel()
                heartbeatJob?.cancel()
                currentSocket = null
                socketState = DashboardSocketState.BACKOFF
                reconnectAttempt += 1
                true
            }
        }
        if (!accepted) return stale("failure:$reason", generation, id)
        socket.cancel()
        if (error == null) log("WS_CLOSE", "reason=$reason")
        else Log.w(TAG, context("WS_FAILURE", "reason=$reason"), error)
        connectionCommands.trySend(Unit)
    }

    private fun stale(callback: String, generation: Long, id: String) {
        Log.d(
            TAG,
            context(
                "WS_START_SKIPPED",
                "reason=stale_callback callback=$callback staleGeneration=$generation staleConnectionId=$id",
            ),
        )
    }

    private fun lastSyncAt(appWidgetId: Int): Long = syncPrefs.getLong(syncKey(appWidgetId), 0L)
    private fun syncKey(appWidgetId: Int) = "widget_${appWidgetId}_last_confirmed_sync"

    private fun log(event: String, fields: String = "") {
        Log.d(TAG, context(event, fields))
    }

    private fun context(event: String, fields: String = ""): String =
        "$event processStartId=${DashboardDiagnostics.processStartId} socketGeneration=$socketGeneration " +
            "connectionId=$connectionId state=$socketState monotonicMs=${SystemClock.elapsedRealtime()} $fields"

    companion object {
        private const val TAG = "HAWidgetEvents"
        private val ACTIVE_STATES = setOf(
            DashboardSocketState.CONNECTING,
            DashboardSocketState.AUTHENTICATING,
            DashboardSocketState.SUBSCRIBING,
            DashboardSocketState.SUBSCRIBED,
        )
    }
}
