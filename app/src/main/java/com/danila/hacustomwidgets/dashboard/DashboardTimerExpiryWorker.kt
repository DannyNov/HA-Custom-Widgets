package com.danila.hacustomwidgets.dashboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** Compatibility tombstone: already persisted RC2/RC3 work must be harmless after upgrade. */
class DashboardTimerExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

/** Kept for pending intents created before RC4. Never enqueue work or call Home Assistant. */
class DashboardTimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

object TimerExpiryMigration {
    const val WORKER_TAG = "com.danila.hacustomwidgets.dashboard.DashboardTimerExpiryWorker"
    fun workNames(timerId: String) = listOf("dashboard-auto-off:$timerId", "dashboard-auto-off:$timerId:alarm")
    fun alarmUri(timerId: String): Uri = Uri.parse("hacw://auto-off/${Uri.encode(timerId)}")

    fun cleanup(context: Context) {
        val migration = context.getSharedPreferences("dashboard_timer_migrations", Context.MODE_PRIVATE)
        if (migration.getBoolean("server_auto_off_v37", false)) return
        val manager = WorkManager.getInstance(context)
        // WorkManager automatically tags every request with the worker class name. This also
        // catches orphan work whose TimerReset record was removed before the upgrade.
        manager.cancelAllWorkByTag(WORKER_TAG)
        val store = TimerResetStore(context)
        store.all().forEach { reset ->
            workNames(reset.timerId).forEach { manager.cancelUniqueWork(it) }
            val intent = Intent(context, DashboardTimerAlarmReceiver::class.java).setData(alarmUri(reset.timerId))
            PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                context.getSystemService(AlarmManager::class.java).cancel(it)
                it.cancel()
            }
        }
        // Old runs represented expiry ownership as well as an optimistic UI. HA will refill truth.
        store.clear()
        check(migration.edit().putBoolean("server_auto_off_v37", true).commit())
    }
}
