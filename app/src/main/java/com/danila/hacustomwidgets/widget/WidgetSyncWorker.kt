package com.danila.hacustomwidgets.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.dashboard.DashboardStateSource
import java.util.concurrent.TimeUnit

class WidgetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as HaWidgetApplication).container
        container.dashboardEvents.workerStarted("PERIODIC_SYNC")
        container.connectionStore.load() ?: return Result.success()
        val hasWidgets = container.dashboards.all().isNotEmpty() || container.widgets.all().isNotEmpty()
        if (!hasWidgets) return Result.success()
        container.dashboardEvents.ensureStarted("PERIODIC_WORK", reconcileIfStale = false)
        val success = container.dashboardEvents.reconcileNow(
            reason = "PERIODIC_WORK",
            force = true,
            source = DashboardStateSource.PERIODIC_REFRESH,
        )
        if (!success) {
            container.widgets.all().forEach {
                container.widgets.saveError(it.appWidgetId, "Ошибка фонового обновления")
                container.widgetRenders.request(it.appWidgetId, "PERIODIC_FAILURE")
            }
        }
        return if (success) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK = "ha_widget_periodic_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<WidgetSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
