package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.danila.hacustomwidgets.HaWidgetApplication
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DashboardRenderCoordinator(
    context: Context,
    private val repository: DashboardRepository,
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = ConcurrentHashMap<Int, RenderSlot>()

    fun request(appWidgetId: Int, requiredRevision: Long, reason: String) {
        val slot = slots.getOrPut(appWidgetId) { RenderSlot() }
        val previous = slot.requested.getAndUpdate { maxOf(it, requiredRevision) }
        slot.reason = reason
        Log.d(
            TAG,
            "RENDER_REQUEST widgetId=$appWidgetId requiredRevision=$requiredRevision reason=$reason " +
                "monotonicMs=${SystemClock.elapsedRealtime()}",
        )
        if (slot.running.compareAndSet(false, true)) {
            scope.launch { drain(appWidgetId, slot) }
        } else if (requiredRevision <= previous) {
            Log.d(TAG, "RENDER_COALESCED widgetId=$appWidgetId revision=$requiredRevision")
        }
    }

    private suspend fun drain(appWidgetId: Int, slot: RenderSlot) {
        var retryScheduled = false
        try {
            while (true) {
                val revisionState = repository.revisionState(appWidgetId)
                val target = maxOf(slot.requested.get(), revisionState.requestedRenderRevision)
                if (target <= revisionState.renderedRevision) return
                val reason = slot.reason
                val started = SystemClock.elapsedRealtime()
                Log.d(TAG, "RENDER_START widgetId=$appWidgetId revision=$target reason=$reason monotonicMs=$started")
                try {
                    performDashboardWidgetUpdate(applicationContext, appWidgetId)
                    repository.markRendered(appWidgetId, target)
                    Log.d(
                        TAG,
                        "RENDER_SUCCESS widgetId=$appWidgetId revision=$target durationMs=${SystemClock.elapsedRealtime() - started}",
                    )
                } catch (error: Throwable) {
                    Log.e(
                        TAG,
                        "RENDER_FAILURE widgetId=$appWidgetId revision=$target reason=$reason",
                        error,
                    )
                    scheduleRetry(applicationContext, appWidgetId, target)
                    retryScheduled = true
                    Log.w(TAG, "RENDER_RETRY widgetId=$appWidgetId revision=$target delayMs=$RETRY_DELAY_MS")
                    return
                }
            }
        } finally {
            slot.running.set(false)
            val revisionState = repository.revisionState(appWidgetId)
            if (!retryScheduled &&
                maxOf(slot.requested.get(), revisionState.requestedRenderRevision) > revisionState.renderedRevision &&
                slot.running.compareAndSet(false, true)
            ) {
                scope.launch { drain(appWidgetId, slot) }
            }
        }
    }

    private data class RenderSlot(
        val requested: AtomicLong = AtomicLong(0L),
        val running: AtomicBoolean = AtomicBoolean(false),
        @Volatile var reason: String = "unknown",
    )

    companion object {
        private const val TAG = "HAWidgetRender"
        private const val RETRY_DELAY_MS = 1_000L

        private fun scheduleRetry(context: Context, appWidgetId: Int, revision: Long) {
            val data = Data.Builder().putInt(KEY_WIDGET_ID, appWidgetId)
                .putLong(KEY_REVISION, revision).build()
            val request = OneTimeWorkRequestBuilder<DashboardRenderWorker>()
                .setInputData(data)
                .setInitialDelay(RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "dashboard-render:$appWidgetId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        internal const val KEY_WIDGET_ID = "widget_id"
        internal const val KEY_REVISION = "revision"
    }
}

class DashboardRenderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val appWidgetId = inputData.getInt(DashboardRenderCoordinator.KEY_WIDGET_ID, -1)
        val revision = inputData.getLong(DashboardRenderCoordinator.KEY_REVISION, 0L)
        if (appWidgetId < 0) return Result.failure()
        val container = (applicationContext as HaWidgetApplication).container
        container.dashboardRenders.request(appWidgetId, revision, "RENDER_RETRY")
        return Result.success()
    }
}

private suspend fun performDashboardWidgetUpdate(context: Context, appWidgetId: Int) {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    DashboardWidget().update(context, glanceId)
}

/** Compatibility entry point: every caller is routed through the coordinator. */
suspend fun updateDashboardWidget(context: Context, appWidgetId: Int, reason: String) {
    val container = (context.applicationContext as HaWidgetApplication).container
    val revision = container.dashboards.revisionState(appWidgetId)
    container.dashboardRenders.request(
        appWidgetId,
        maxOf(revision.committedRevision, revision.requestedRenderRevision),
        reason,
    )
}
