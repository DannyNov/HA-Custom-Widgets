package com.danila.hacustomwidgets.dashboard

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.danila.hacustomwidgets.HaWidgetApplication

/** Executes one user timer gesture. HA remains authoritative; no local delayed shutdown is scheduled. */
class DashboardTimerActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val widgetId = inputData.getInt(KEY_WIDGET, -1)
        val primaryId = inputData.getString(KEY_PRIMARY) ?: return Result.failure()
        val primaryDomain = inputData.getString(KEY_DOMAIN) ?: return Result.failure()
        val timerId = inputData.getString(KEY_TIMER) ?: return Result.failure()
        val minutes = inputData.getInt(KEY_MINUTES, 0)
        val primaryOn = inputData.getBoolean(KEY_PRIMARY_ON, false)
        if (widgetId < 0 || !AutoOffTimerPolicy.validMinutes(minutes)) return Result.failure()
        val container = (applicationContext as HaWidgetApplication).container
        container.dashboardEvents.workerStarted("DASHBOARD_TIMER_ACTION")
        return runCatching {
            val connection = container.connectionStore.load() ?: error("Подключение не настроено")
            CompositeTimerActionPolicy.start(
                DashboardControl(primaryId, primaryId, primaryDomain, if (primaryOn) "on" else "off"),
                primaryOn, timerId, minutes,
            ).forEach { call ->
                container.client.callService(connection, call.domain, call.service, call.entityId, call.data)
            }
            val states = container.client.getEntities(connection, listOf(primaryId, timerId))
            container.dashboards.updateEntityStates(widgetId, states, DashboardStateSource.RECONCILIATION)
            Result.success()
        }.getOrElse { error ->
            container.dashboards.saveError(widgetId, error.message ?: "Не удалось запустить таймер")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_WIDGET = "widget"
        private const val KEY_PRIMARY = "primary"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_PRIMARY_ON = "primary_on"
        private const val KEY_TIMER = "timer"
        private const val KEY_MINUTES = "minutes"

        fun enqueue(context: Context, widgetId: Int, primaryId: String, primaryDomain: String,
                    primaryOn: Boolean, timerId: String, minutes: Int) {
            val data = Data.Builder().putInt(KEY_WIDGET, widgetId).putString(KEY_PRIMARY, primaryId)
                .putString(KEY_DOMAIN, primaryDomain).putBoolean(KEY_PRIMARY_ON, primaryOn)
                .putString(KEY_TIMER, timerId).putInt(KEY_MINUTES, minutes).build()
            val request = OneTimeWorkRequestBuilder<DashboardTimerActionWorker>().setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "dashboard-timer:$widgetId:$primaryId", ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}
