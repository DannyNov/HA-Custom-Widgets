package com.danila.hacustomwidgets.dashboard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.danila.hacustomwidgets.HaWidgetApplication
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Best-effort minute display ticks. No network, wake lock, exact alarm or local device action. */
class DashboardCountdownWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getInt(KEY_WIDGET, -1)
        if (id < 0) return Result.failure()
        val repository = (applicationContext as HaWidgetApplication).container.dashboards
        val state = repository.get(id) ?: return Result.success()
        repository.refreshTransientUi(id)
        // Append before finishing so KEEP requests from concurrent renders cannot lose the next tick.
        schedule(applicationContext, id, state, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val KEY_WIDGET = "widget_id"
        fun schedule(context: Context, id: Int, state: DashboardState?, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val remaining = state?.cards.orEmpty().flatMap { it.metrics + listOfNotNull(it.timerState) }
                .filter { it.domain == "timer" }
                .map { HaTimerPresentationPolicy.resolve(it, Instant.now()) }
                .filter { it.status == HaTimerStatus.ACTIVE }
                .mapNotNull { it.remainingMillis }
            val delay = DashboardCountdownPolicy.nextDelayMillis(remaining)
            val manager = WorkManager.getInstance(context)
            val name = "dashboard-countdown:$id"
            if (delay == null) {
                manager.cancelUniqueWork(name)
                return
            }
            manager.enqueueUniqueWork(name, policy,
                OneTimeWorkRequestBuilder<DashboardCountdownWorker>()
                    .setInputData(Data.Builder().putInt(KEY_WIDGET, id).build())
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
        }
    }
}

object DashboardCountdownPolicy {
    fun nextDelayMillis(remaining: List<Long>): Long? = remaining.filter { it > 0 }.minOrNull()
        ?.coerceIn(1_000L, 60_000L)
}
