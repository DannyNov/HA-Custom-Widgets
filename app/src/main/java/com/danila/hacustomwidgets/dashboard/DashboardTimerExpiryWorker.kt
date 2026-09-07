package com.danila.hacustomwidgets.dashboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.*
import com.danila.hacustomwidgets.HaWidgetApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock

/** Durable expiry execution. Never sends toggle and never derives expiry from a UI tick. */
class DashboardTimerExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val timerId = inputData.getString("timer") ?: return Result.failure()
        return TimerExecutionLocks.get(timerId).withLock { execute() }
    }

    private suspend fun execute(): Result {
        val timerId = inputData.getString("timer") ?: return Result.failure()
        val generation = inputData.getString("generation") ?: return Result.failure()
        val store = TimerResetStore(applicationContext)
        var reset = store.get(timerId)?.takeIf { it.generation == generation && it.accepted }
            ?: return Result.success()
        val container = (applicationContext as HaWidgetApplication).container
        return try {
            val connection = container.connectionStore.load() ?: return Result.retry()
            if (connection.baseUrl != reset.serverUrl) {
                store.remove(timerId, generation)
                return Result.success()
            }
            val timer = container.client.getEntity(connection, timerId)
            val primary = container.client.getEntity(connection, reset.primaryId)
            if (store.get(timerId)?.generation != generation) return Result.success()
            container.dashboards.widgetsContainingEntity(timerId).forEach {
                container.dashboards.updateEntityStates(it, listOf(timer, primary), DashboardStateSource.RECONCILIATION)
            }
            reset = store.get(timerId)?.takeIf { it.generation == generation } ?: return Result.success()
            when (TimerExpiryPolicy.decide(reset, timer, primary, System.currentTimeMillis())) {
                TimerExpiryDecision.WAIT -> {
                    schedule(applicationContext, reset, maxOf(reset.finishAt, System.currentTimeMillis() + 60_000L), true)
                }
                TimerExpiryDecision.RESCHEDULE -> {
                    val finish = TimerResetPolicy.timestamp(timer.timerFinishesAt)
                    val updated = reset.copy(finishAt = finish ?: reset.finishAt,
                        confirmedHa = TimerResetPolicy.timestamp(timer.lastUpdated))
                    if (store.update(updated)) schedule(applicationContext, updated,
                        maxOf(updated.finishAt + 1_000L, System.currentTimeMillis() + 5_000L), true)
                }
                TimerExpiryDecision.CANCEL -> store.remove(timerId, generation)
                TimerExpiryDecision.TURN_OFF -> {
                    // Recheck generation immediately before the idempotent service call.
                    if (store.get(timerId)?.generation != generation) return Result.success()
                    val call = TimerExpiryPolicy.turnOff(reset)
                    container.client.callService(connection, call.domain, call.service, call.entityId)
                    store.remove(timerId, generation)
                    val off = container.client.getEntity(connection, reset.primaryId)
                    container.dashboards.widgetsContainingEntity(reset.primaryId).forEach {
                        container.dashboards.updateEntityStates(it, listOf(off, timer), DashboardStateSource.RECONCILIATION)
                    }
                }
            }
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { Result.retry() }
    }

    companion object {
        fun schedule(context: Context, reset: TimerReset, at: Long = reset.finishAt + 1_000L,
                     append: Boolean = false, keep: Boolean = false) {
            val data = workDataOf("timer" to reset.timerId, "generation" to reset.generation)
            val request = OneTimeWorkRequestBuilder<DashboardTimerExpiryWorker>()
                .setInputData(data).setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(TimerExpiryPolicy.workName(reset.timerId),
                if (keep) ExistingWorkPolicy.KEEP else if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
            // Inexact idle-capable wakeup is an additional prompt, not an exact-time guarantee.
            // No exact-alarm permission or battery-optimization exemption is requested.
            val intent = Intent(context, DashboardTimerAlarmReceiver::class.java)
                .setData(Uri.parse("hacw://auto-off/${Uri.encode(reset.timerId)}"))
                .putExtra("timer", reset.timerId).putExtra("generation", reset.generation)
            val alarm = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
        }
    }
}

class DashboardTimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timerId = intent.getStringExtra("timer") ?: return
        val reset = TimerResetStore(context).get(timerId)
            ?.takeIf { it.generation == intent.getStringExtra("generation") && it.accepted } ?: return
        val builder = OneTimeWorkRequestBuilder<DashboardTimerExpiryWorker>()
            .setInputData(workDataOf("timer" to reset.timerId, "generation" to reset.generation))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        if (android.os.Build.VERSION.SDK_INT >= 31) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        val request = builder.build()
        WorkManager.getInstance(context).enqueueUniqueWork("${TimerExpiryPolicy.workName(timerId)}:alarm",
            ExistingWorkPolicy.KEEP, request)
    }
}
