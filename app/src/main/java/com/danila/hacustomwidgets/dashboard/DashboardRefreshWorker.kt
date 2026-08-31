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

class DashboardRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val appWidgetId = inputData.getInt(KEY_WIDGET_ID, -1)
        if (appWidgetId < 0) return Result.failure()
        val source = inputData.getString(KEY_SOURCE)
            ?.let { runCatching { DashboardStateSource.valueOf(it) }.getOrNull() }
            ?: DashboardStateSource.MANUAL_REFRESH
        val container = (applicationContext as HaWidgetApplication).container
        container.dashboardEvents.workerStarted("DASHBOARD_REFRESH")
        Log.d(TAG, "refresh worker start widgetId=$appWidgetId source=$source attempt=$runAttemptCount")
        try {
            container.connectionStore.load() ?: error("Подключение не настроено")
            container.dashboardEvents.ensureStarted("REFRESH_WORKER", reconcileIfStale = false)
            check(
                container.dashboardEvents.reconcileNow(
                    reason = "REFRESH_WORKER_$source",
                    force = true,
                    appWidgetId = appWidgetId,
                    source = source,
                ),
            ) { "Не удалось обновить состояния Home Assistant" }
            container.dashboards.clearError(appWidgetId)
            return Result.success()
        } catch (error: Throwable) {
            container.dashboards.saveError(appWidgetId, error.message ?: "Ошибка обновления")
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        } finally {
            if (source == DashboardStateSource.MANUAL_REFRESH) {
                container.dashboardEvents.evaluateAfterManualRefresh()
            }
            container.dashboards.markRefreshInProgress(appWidgetId, false)
            Log.d(
                TAG,
                "refresh worker end widgetId=$appWidgetId source=$source revision=${container.dashboards.currentStateRevision(appWidgetId)}",
            )
        }
    }

    companion object {
        private const val TAG = "HAWidgetRefreshWorker"
        private const val KEY_WIDGET_ID = "widget_id"
        private const val KEY_SOURCE = "source"
        private const val MAX_RETRIES = 2

        fun enqueue(context: Context, appWidgetId: Int, source: DashboardStateSource) {
            val data = Data.Builder()
                .putInt(KEY_WIDGET_ID, appWidgetId)
                .putString(KEY_SOURCE, source.name)
                .build()
            val request = OneTimeWorkRequestBuilder<DashboardRefreshWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                DashboardStatePolicy.refreshWorkName(appWidgetId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
