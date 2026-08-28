package com.danila.hacustomwidgets.dashboard

import android.content.Context
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
import kotlinx.coroutines.delay

class DashboardActionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val appWidgetId = inputData.getInt(KEY_WIDGET_ID, -1)
        val entityId = inputData.getString(KEY_ENTITY_ID) ?: return Result.failure()
        val operationId = inputData.getString(KEY_OPERATION_ID) ?: return Result.failure()
        if (appWidgetId < 0) return Result.failure()
        val container = (applicationContext as HaWidgetApplication).container
        val operation = container.dashboards.getOperation(appWidgetId, entityId)
            ?.takeIf { it.operationId == operationId }
            ?: return Result.success()
        if (!operation.status.isActive) return Result.success()

        var retry = false
        Log.d(TAG, "worker start operationId=$operationId widgetId=$appWidgetId entityId=$entityId attempt=$runAttemptCount")
        try {
            container.dashboards.setOperationStatus(
                appWidgetId,
                entityId,
                operationId,
                DashboardOperationStatus.RUNNING,
            )
            val connection = container.connectionStore.load() ?: error("Подключение не настроено")
            Log.d(TAG, "service call start operationId=$operationId entityId=$entityId service=${operation.domain}.${operation.service}")
            container.client.callService(connection, operation.domain, operation.service, entityId)
            Log.d(TAG, "service call end operationId=$operationId entityId=$entityId success=true")

            if (operation.desiredState == null) {
                container.dashboards.setOperationStatus(
                    appWidgetId,
                    entityId,
                    operationId,
                    DashboardOperationStatus.CONFIRMED,
                )
                runCatching { container.client.getEntity(connection, entityId) }
                    .onSuccess {
                        container.dashboards.updateEntityStates(
                            appWidgetId,
                            listOf(it),
                            DashboardStateSource.RECONCILIATION,
                        )
                    }
            } else {
                val confirmed = awaitConfirmation(
                    desiredState = operation.desiredState,
                    load = { container.client.getEntity(connection, entityId) },
                    accept = { entity ->
                        container.dashboards.updateEntityStates(
                            appWidgetId,
                            listOf(entity),
                            DashboardStateSource.ACTION,
                        )
                        entity.state == operation.desiredState
                    },
                )
                if (confirmed) {
                    container.dashboards.setOperationStatus(
                        appWidgetId,
                        entityId,
                        operationId,
                        DashboardOperationStatus.CONFIRMED,
                    )
                    Log.d(TAG, "confirmation operationId=$operationId entityId=$entityId state=${operation.desiredState}")
                } else {
                    container.dashboards.setOperationStatus(
                        appWidgetId,
                        entityId,
                        operationId,
                        DashboardOperationStatus.TIMEOUT,
                        "Home Assistant не подтвердил состояние вовремя",
                    )
                    reconcile(appWidgetId, entityId, container, "timeout")
                }
            }
            return Result.success()
        } catch (error: Throwable) {
            Log.w(TAG, "service/confirmation failure operationId=$operationId entityId=$entityId", error)
            if (runAttemptCount < MAX_RETRIES) {
                retry = true
                container.dashboards.setOperationStatus(
                    appWidgetId,
                    entityId,
                    operationId,
                    DashboardOperationStatus.PENDING,
                )
                return Result.retry()
            }
            container.dashboards.setOperationStatus(
                appWidgetId,
                entityId,
                operationId,
                DashboardOperationStatus.FAILED,
                error.message ?: "Команда не выполнена",
            )
            reconcile(appWidgetId, entityId, container, "failure")
            return Result.failure()
        } finally {
            if (!retry) {
                val latest = container.dashboards.getOperation(appWidgetId, entityId)
                if (latest?.operationId == operationId && latest.status.isActive) {
                    container.dashboards.setOperationStatus(
                        appWidgetId,
                        entityId,
                        operationId,
                        DashboardOperationStatus.FAILED,
                        "Операция завершилась без подтверждения",
                    )
                }
            }
            updateDashboardWidget(applicationContext, appWidgetId, "action-worker")
            val latest = container.dashboards.getOperation(appWidgetId, entityId)
            Log.d(
                TAG,
                "worker end operationId=$operationId widgetId=$appWidgetId entityId=$entityId " +
                    "status=${latest?.status} revision=${container.dashboards.currentStateRevision(appWidgetId)} retry=$retry",
            )
        }
    }

    private suspend fun awaitConfirmation(
        desiredState: String,
        load: suspend () -> com.danila.hacustomwidgets.data.model.HaEntity,
        accept: (com.danila.hacustomwidgets.data.model.HaEntity) -> Boolean,
    ): Boolean {
        val delays = longArrayOf(250, 400, 700, 1_000, 1_500, 2_000)
        for (wait in delays) {
            delay(wait)
            val entity = load()
            if (entity.state == desiredState && accept(entity)) return true
            accept(entity)
        }
        return false
    }

    private suspend fun reconcile(
        appWidgetId: Int,
        entityId: String,
        container: com.danila.hacustomwidgets.data.AppContainer,
        reason: String,
    ) {
        val connection = container.connectionStore.load() ?: return
        Log.d(TAG, "reconciliation start widgetId=$appWidgetId entityId=$entityId reason=$reason")
        runCatching { container.client.getEntity(connection, entityId) }
            .onSuccess {
                container.dashboards.updateEntityStates(
                    appWidgetId,
                    listOf(it),
                    DashboardStateSource.RECONCILIATION,
                )
                Log.d(TAG, "reconciliation end widgetId=$appWidgetId entityId=$entityId success=true")
            }
            .onFailure {
                Log.w(TAG, "reconciliation end widgetId=$appWidgetId entityId=$entityId success=false", it)
                DashboardRefreshWorker.enqueue(
                    applicationContext,
                    appWidgetId,
                    DashboardStateSource.RECONCILIATION,
                )
            }
    }

    companion object {
        private const val TAG = "HAWidgetActionWorker"
        private const val KEY_WIDGET_ID = "widget_id"
        private const val KEY_ENTITY_ID = "entity_id"
        private const val KEY_OPERATION_ID = "operation_id"
        private const val MAX_RETRIES = 2

        fun enqueue(context: Context, appWidgetId: Int, entityId: String, operationId: String) {
            val data = Data.Builder()
                .putInt(KEY_WIDGET_ID, appWidgetId)
                .putString(KEY_ENTITY_ID, entityId)
                .putString(KEY_OPERATION_ID, operationId)
                .build()
            val request = OneTimeWorkRequestBuilder<DashboardActionWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                DashboardStatePolicy.actionWorkName(appWidgetId, entityId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
