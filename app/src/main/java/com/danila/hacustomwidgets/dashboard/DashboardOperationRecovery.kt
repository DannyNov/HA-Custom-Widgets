package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.security.SecureConnectionStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DashboardStartupCoordinator(
    context: Context,
    private val connectionStore: SecureConnectionStore,
    private val client: HomeAssistantClient,
    private val dashboards: DashboardRepository,
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            val active = dashboards.activeOperations()
            Log.i(
                TAG,
                "STARTUP_SWEEP processStartId=${DashboardDiagnostics.processStartId} active=${active.size} " +
                    "monotonicMs=${SystemClock.elapsedRealtime()}",
            )
            val connection = connectionStore.load()
            dashboards.all().forEach { config ->
                val ids = dashboards.entityIds(config.appWidgetId)
                if (connection != null && ids.isNotEmpty()) {
                    Log.d(TAG, "REST_RECONCILE_START widgetId=${config.appWidgetId} source=PROCESS_START entities=${ids.size}")
                    runCatching { client.getEntities(connection, ids) }
                        .onSuccess {
                            dashboards.updateEntityStates(config.appWidgetId, it, DashboardStateSource.RECONCILIATION)
                            Log.d(TAG, "REST_RECONCILE_END widgetId=${config.appWidgetId} source=PROCESS_START success=true")
                        }
                        .onFailure {
                            Log.w(TAG, "REST_RECONCILE_END widgetId=${config.appWidgetId} source=PROCESS_START success=false", it)
                            DashboardRefreshWorker.enqueue(applicationContext, config.appWidgetId, DashboardStateSource.RECONCILIATION)
                        }
                }
            }
            val now = System.currentTimeMillis()
            active.forEach { (widgetId, original) ->
                val latest = dashboards.getOperation(widgetId, original.entityId) ?: return@forEach
                if (!latest.status.isActive) return@forEach
                if (DashboardStatePolicy.operationExpired(latest, now)) {
                    dashboards.finishOperation(
                        widgetId, latest.entityId, latest.operationId, DashboardOperationStatus.TIMEOUT,
                        "Операция истекла до восстановления процесса",
                    )
                } else {
                    DashboardActionWorker.enqueue(applicationContext, widgetId, latest.entityId, latest.operationId)
                    DashboardOperationDeadlineWorker.enqueue(applicationContext, widgetId, latest)
                }
            }
            dashboards.requestPendingRenders("PROCESS_START")
        }
    }

    companion object { private const val TAG = "HAWidgetStartup" }
}

class DashboardOperationDeadlineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val widgetId = inputData.getInt(KEY_WIDGET_ID, -1)
        val entityId = inputData.getString(KEY_ENTITY_ID) ?: return Result.failure()
        val operationId = inputData.getString(KEY_OPERATION_ID) ?: return Result.failure()
        if (widgetId < 0) return Result.failure()
        val container = (applicationContext as HaWidgetApplication).container
        val operation = container.dashboards.getOperation(widgetId, entityId)
            ?.takeIf { it.operationId == operationId && it.status.isActive } ?: return Result.success()
        val remaining = operation.deadlineAt - System.currentTimeMillis()
        if (remaining > 100L) {
            enqueue(applicationContext, widgetId, operation)
            return Result.success()
        }
        val connection = container.connectionStore.load()
        if (connection != null) {
            Log.d(TAG, "REST_RECONCILE_START operationId=$operationId widgetId=$widgetId entityId=$entityId source=DEADLINE")
            runCatching { container.client.getEntity(connection, entityId) }
                .onSuccess {
                    container.dashboards.updateEntityStates(widgetId, listOf(it), DashboardStateSource.RECONCILIATION)
                    Log.d(TAG, "REST_RECONCILE_END operationId=$operationId entityId=$entityId state=${it.state} success=true")
                }
                .onFailure { Log.w(TAG, "REST_RECONCILE_END operationId=$operationId entityId=$entityId success=false", it) }
        }
        val after = container.dashboards.getOperation(widgetId, entityId)
        if (after?.operationId == operationId && after.status.isActive) {
            val status = if (operation.desiredState == null && connection != null) {
                DashboardOperationStatus.CONFIRMED
            } else {
                DashboardOperationStatus.TIMEOUT
            }
            container.dashboards.finishOperation(
                widgetId, entityId, operationId, status,
                if (status == DashboardOperationStatus.TIMEOUT) "Home Assistant не подтвердил состояние до deadline" else null,
            )
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "HAWidgetDeadline"
        private const val KEY_WIDGET_ID = "widget_id"
        private const val KEY_ENTITY_ID = "entity_id"
        private const val KEY_OPERATION_ID = "operation_id"

        fun enqueue(context: Context, appWidgetId: Int, operation: DashboardOperation) {
            val remaining = (operation.deadlineAt - System.currentTimeMillis()).coerceAtLeast(0L)
            val data = Data.Builder().putInt(KEY_WIDGET_ID, appWidgetId)
                .putString(KEY_ENTITY_ID, operation.entityId)
                .putString(KEY_OPERATION_ID, operation.operationId).build()
            val request = OneTimeWorkRequestBuilder<DashboardOperationDeadlineWorker>()
                .setInputData(data).setInitialDelay(remaining, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                DashboardStatePolicy.deadlineWorkName(operation.operationId),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

object DashboardDiagnostics {
    val processStartId: String = java.util.UUID.randomUUID().toString()
}
