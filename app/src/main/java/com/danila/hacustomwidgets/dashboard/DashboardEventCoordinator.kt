package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.util.Log
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.security.SecureConnectionStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.WebSocket

class DashboardEventCoordinator(
    context: Context,
    private val connectionStore: SecureConnectionStore,
    private val client: HomeAssistantClient,
    private val dashboards: DashboardRepository,
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconnectScheduled = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var reconnectAttempt = 0

    @Synchronized
    fun start() {
        if (socket != null || dashboards.all().isEmpty()) return
        val connection = connectionStore.load() ?: return
        Log.d(TAG, "WS_OPEN processStartId=${DashboardDiagnostics.processStartId} source=EVENT")
        socket = client.openStateChangedWebSocket(
            connection = connection,
            onSubscribed = {
                reconnectAttempt = 0
                Log.d(TAG, "WS_OPEN processStartId=${DashboardDiagnostics.processStartId} source=EVENT subscribed=true")
                scope.launch { reconcileAll("event-connected") }
            },
            onStateChanged = { entity ->
                scope.launch {
                    val widgetIds = dashboards.widgetsContainingEntity(entity.entityId)
                    widgetIds.forEach { widgetId ->
                        runCatching {
                            dashboards.updateEntityStates(widgetId, listOf(entity), DashboardStateSource.EVENT)
                        }.onFailure {
                            Log.e(TAG, "WS_STATE_CHANGED widgetId=$widgetId entityId=${entity.entityId} commitFailure=true", it)
                        }
                    }
                    Log.d(
                        TAG,
                        "WS_STATE_CHANGED processStartId=${DashboardDiagnostics.processStartId} " +
                            "entityId=${entity.entityId} haState=${entity.state} widgets=${widgetIds.size}",
                    )
                }
            },
            onClosed = { connectionLost("closed", null) },
            onFailure = { connectionLost("failure", it) },
        )
    }

    @Synchronized
    fun stopIfUnused() {
        if (dashboards.all().isNotEmpty()) return
        socket?.close(1000, "no dashboards")
        socket = null
    }

    private fun connectionLost(reason: String, error: Throwable?) {
        synchronized(this) { socket = null }
        if (error == null) Log.d(TAG, "WS_CLOSED processStartId=${DashboardDiagnostics.processStartId} reason=$reason")
        else Log.w(TAG, "WS_FAILURE processStartId=${DashboardDiagnostics.processStartId} reason=$reason", error)
        if (dashboards.all().isEmpty() || !reconnectScheduled.compareAndSet(false, true)) return
        scope.launch {
            val waitMs = (1_000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
            reconnectAttempt += 1
            delay(waitMs)
            reconnectScheduled.set(false)
            Log.d(TAG, "WS_RECONNECT processStartId=${DashboardDiagnostics.processStartId} attempt=$reconnectAttempt")
            start()
        }
    }

    suspend fun reconcileAll(reason: String) {
        val connection = connectionStore.load() ?: return
        dashboards.all().forEach { config ->
            val entityIds = dashboards.entityIds(config.appWidgetId)
            if (entityIds.isEmpty()) return@forEach
            runCatching { client.getEntities(connection, entityIds) }
                .onSuccess {
                    dashboards.updateEntityStates(
                        config.appWidgetId,
                        it,
                        DashboardStateSource.RECONCILIATION,
                    )
                }
                .onFailure {
                    Log.w(TAG, "event reconciliation failed widgetId=${config.appWidgetId}", it)
                    DashboardRefreshWorker.enqueue(
                        applicationContext,
                        config.appWidgetId,
                        DashboardStateSource.RECONCILIATION,
                    )
                }
        }
    }

    companion object {
        private const val TAG = "HAWidgetEvents"
    }
}
