package com.danila.hacustomwidgets.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.danila.hacustomwidgets.HaWidgetApplication
import java.util.concurrent.TimeUnit

class WidgetSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as HaWidgetApplication).container
        val connection = container.connectionStore.load() ?: return Result.success()
        var transientFailure = false
        container.widgets.all().forEach { config ->
            runCatching {
                container.client.getEntities(connection, config.metrics.map { it.entityId })
            }
                .onSuccess { container.widgets.updateStates(config.appWidgetId, it) }
                .onFailure {
                    transientFailure = true
                    container.widgets.saveError(config.appWidgetId, it.message ?: "Ошибка сети")
                }
        }
        EntityStateWidget().updateAll(applicationContext)
        return if (transientFailure) Result.retry() else Result.success()
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
