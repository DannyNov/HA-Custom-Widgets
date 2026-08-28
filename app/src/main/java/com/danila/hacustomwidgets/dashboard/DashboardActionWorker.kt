package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.data.AppContainer
import kotlinx.coroutines.delay

class DashboardActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val appWidgetId = inputData.getInt(KEY_WIDGET_ID, -1)
        val entityId = inputData.getString(KEY_ENTITY_ID) ?: return Result.failure()
        val operationId = inputData.getString(KEY_OPERATION_ID) ?: return Result.failure()
        if (appWidgetId < 0) return Result.failure()
        val container = (applicationContext as HaWidgetApplication).container
        val operation = container.dashboards.getOperation(appWidgetId, entityId)
            ?.takeIf { it.operationId == operationId && it.status.isActive } ?: return Result.success()

        if (DashboardStatePolicy.operationExpired(operation, System.currentTimeMillis())) {
            reconcileAndFinish(container, appWidgetId, operation, DashboardOperationStatus.TIMEOUT, "deadline-before-worker")
            return Result.success()
        }

        Log.d(TAG, context("SERVICE_CALL_START", appWidgetId, operation, "attempt=$runAttemptCount"))
        container.dashboards.setOperationStatus(appWidgetId, entityId, operationId, DashboardOperationStatus.RUNNING)
        var serviceAccepted = false
        try {
            val connection = container.connectionStore.load() ?: error("Подключение не настроено")
            container.client.callService(connection, operation.domain, operation.service, entityId)
            serviceAccepted = true
            Log.d(TAG, context("SERVICE_CALL_END", appWidgetId, operation, "httpAccepted=true confirmed=false"))

            if (operation.desiredState == null) {
                val snapshot = container.client.getEntity(connection, entityId)
                container.dashboards.updateEntityStates(appWidgetId, listOf(snapshot), DashboardStateSource.RECONCILIATION)
                container.dashboards.finishOperation(
                    appWidgetId, entityId, operationId, DashboardOperationStatus.CONFIRMED, null,
                )
                return Result.success()
            }

            while (System.currentTimeMillis() < operation.deadlineAt) {
                val remaining = operation.deadlineAt - System.currentTimeMillis()
                delay(minOf(POLL_INTERVAL_MS, remaining.coerceAtLeast(1L)))
                val snapshot = container.client.getEntity(connection, entityId)
                container.dashboards.updateEntityStates(appWidgetId, listOf(snapshot), DashboardStateSource.ACTION)
                val latest = container.dashboards.getOperation(appWidgetId, entityId)
                if (latest?.operationId != operationId || !latest.status.isActive) return Result.success()
            }
            reconcileAndFinish(container, appWidgetId, operation, DashboardOperationStatus.TIMEOUT, "deadline-mismatch")
            return Result.success()
        } catch (error: Throwable) {
            Log.w(TAG, context("SERVICE_CALL_END", appWidgetId, operation, "httpAccepted=$serviceAccepted error=${error.javaClass.simpleName}"), error)
            reconcileOnce(container, appWidgetId, operation)
            val latest = container.dashboards.getOperation(appWidgetId, entityId)
            if (latest?.operationId != operationId || !latest.status.isActive) return Result.success()
            val expired = System.currentTimeMillis() >= operation.deadlineAt
            return if (!expired && runAttemptCount < MAX_RETRIES) {
                container.dashboards.setOperationStatus(
                    appWidgetId, entityId, operationId, DashboardOperationStatus.PENDING,
                    error.message,
                )
                Result.retry()
            } else {
                container.dashboards.finishOperation(
                    appWidgetId, entityId, operationId,
                    if (expired) DashboardOperationStatus.TIMEOUT else DashboardOperationStatus.FAILED,
                    error.message ?: "Команда не подтверждена Home Assistant",
                )
                Result.failure()
            }
        } finally {
            val latest = container.dashboards.getOperation(appWidgetId, entityId)
            Log.d(
                TAG,
                context(
                    "WORKER_END", appWidgetId, operation,
                    "status=${latest?.status} committedRevision=${container.dashboards.currentStateRevision(appWidgetId)}",
                ),
            )
        }
    }

    private suspend fun reconcileAndFinish(
        container: AppContainer,
        appWidgetId: Int,
        operation: DashboardOperation,
        fallbackStatus: DashboardOperationStatus,
        reason: String,
    ) {
        reconcileOnce(container, appWidgetId, operation)
        val latest = container.dashboards.getOperation(appWidgetId, operation.entityId)
        if (latest?.operationId == operation.operationId && latest.status.isActive) {
            container.dashboards.finishOperation(
                appWidgetId, operation.entityId, operation.operationId, fallbackStatus,
                if (fallbackStatus == DashboardOperationStatus.CONFIRMED) null else "Home Assistant не подтвердил состояние до deadline ($reason)",
            )
        }
    }

    private suspend fun reconcileOnce(container: AppContainer, appWidgetId: Int, operation: DashboardOperation) {
        val connection = container.connectionStore.load() ?: return
        Log.d(TAG, context("REST_RECONCILE_START", appWidgetId, operation, "source=ACTION"))
        runCatching { container.client.getEntity(connection, operation.entityId) }
            .onSuccess {
                container.dashboards.updateEntityStates(appWidgetId, listOf(it), DashboardStateSource.RECONCILIATION)
                Log.d(TAG, context("REST_RECONCILE_END", appWidgetId, operation, "state=${it.state} success=true"))
            }
            .onFailure { Log.w(TAG, context("REST_RECONCILE_END", appWidgetId, operation, "success=false"), it) }
    }

    private fun context(event: String, widgetId: Int, operation: DashboardOperation, detail: String): String =
        "$event processStartId=${DashboardDiagnostics.processStartId} operationId=${operation.operationId} " +
            "appWidgetId=$widgetId entityId=${operation.entityId} source=ACTION " +
            "monotonicMs=${SystemClock.elapsedRealtime()} $detail"

    companion object {
        private const val TAG = "HAWidgetActionWorker"
        private const val KEY_WIDGET_ID = "widget_id"
        private const val KEY_ENTITY_ID = "entity_id"
        private const val KEY_OPERATION_ID = "operation_id"
        private const val MAX_RETRIES = 2
        private const val POLL_INTERVAL_MS = 500L

        fun enqueue(context: Context, appWidgetId: Int, entityId: String, operationId: String) {
            val operation = (context.applicationContext as HaWidgetApplication).container.dashboards
                .getOperation(appWidgetId, entityId)?.takeIf { it.operationId == operationId } ?: return
            val data = Data.Builder().putInt(KEY_WIDGET_ID, appWidgetId)
                .putString(KEY_ENTITY_ID, entityId).putString(KEY_OPERATION_ID, operationId).build()
            val request = OneTimeWorkRequestBuilder<DashboardActionWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                DashboardStatePolicy.actionWorkName(appWidgetId, entityId, operationId),
                ExistingWorkPolicy.KEEP,
                request,
            )
            DashboardOperationDeadlineWorker.enqueue(context, appWidgetId, operation)
        }
    }
}
