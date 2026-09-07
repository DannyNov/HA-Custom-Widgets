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
import kotlinx.coroutines.sync.withLock

/** Serialized command generations; accepted starts are never repeated just because REST failed. */
class DashboardTimerActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val timerId = inputData.getString(KEY_TIMER) ?: return Result.failure()
        return TimerExecutionLocks.get(timerId).withLock { execute() }
    }

    private suspend fun execute(): Result {
        val widgetId = inputData.getInt(KEY_WIDGET, -1)
        val primaryId = inputData.getString(KEY_PRIMARY) ?: return Result.failure()
        val primaryDomain = inputData.getString(KEY_DOMAIN) ?: return Result.failure()
        val timerId = inputData.getString(KEY_TIMER) ?: return Result.failure()
        val generation = inputData.getString("generation") ?: return Result.failure()
        val store = TimerResetStore(applicationContext)
        val reset = store.get(timerId)?.takeIf { it.generation == generation } ?: return Result.success()
        val minutes = inputData.getInt(KEY_MINUTES, 0)
        val primaryOn = inputData.getBoolean(KEY_PRIMARY_ON, false)
        if (widgetId < 0 || !AutoOffTimerPolicy.validMinutes(minutes)) return Result.failure()
        val container = (applicationContext as HaWidgetApplication).container
        if (TimerResetPolicy.expiredPending(reset, System.currentTimeMillis())) {
            store.remove(timerId, generation)
            container.dashboards.saveError(widgetId, com.danila.hacustomwidgets.tr("Timer request expired; tap again", "Запрос таймера устарел; нажмите ещё раз"))
            return Result.success()
        }
        container.dashboardEvents.workerStarted("DASHBOARD_TIMER_ACTION")
        return runCatching {
            val connection = container.connectionStore.load() ?: error("Подключение не настроено")
            if (connection.baseUrl != reset.serverUrl) {
                store.remove(timerId, generation)
                return Result.success()
            }
            val currentPrimary = container.client.getEntity(connection, primaryId)
            if (!reset.accepted) CompositeTimerActionPolicy.start(
                DashboardControl(primaryId, primaryId, primaryDomain, if (primaryOn) "on" else "off"),
                currentPrimary.state == "on", timerId, minutes,
            ).forEach { call ->
                container.client.callService(connection, call.domain, call.service, call.entityId, call.data)
            }
            val accepted = if (reset.accepted) reset else reset.copy(accepted = true,
                finishAt = System.currentTimeMillis() + minutes * 60_000L)
            if (!store.update(accepted)) return Result.success()
            DashboardTimerExpiryWorker.schedule(applicationContext, accepted)
            container.dashboards.refreshTransientUi(widgetId)
            val states = container.client.getEntities(connection, listOf(primaryId, timerId))
            container.dashboards.widgetsContainingEntity(timerId).forEach {
                container.dashboards.updateEntityStates(it, states, DashboardStateSource.RECONCILIATION)
            }
            store.get(timerId)?.takeIf { it.generation == generation }?.let {
                DashboardTimerExpiryWorker.schedule(applicationContext, it)
            }
            Result.success()
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (store.get(timerId)?.generation != generation) return Result.success()
            if (runAttemptCount >= 2 && store.get(timerId)?.accepted != true) store.remove(timerId, generation)
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
            val reset = TimerResetStore(context).get(timerId) ?: return
            val data = Data.Builder().putInt(KEY_WIDGET, widgetId).putString(KEY_PRIMARY, primaryId)
                .putString(KEY_DOMAIN, primaryDomain).putBoolean(KEY_PRIMARY_ON, primaryOn)
                .putString(KEY_TIMER, timerId).putInt(KEY_MINUTES, minutes)
                .putString("generation", reset.generation).build()
            val request = OneTimeWorkRequestBuilder<DashboardTimerActionWorker>().setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "dashboard-timer:$timerId", ExistingWorkPolicy.APPEND_OR_REPLACE, request,
            )
        }
    }
}
