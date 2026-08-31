package com.danila.hacustomwidgets.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.danila.hacustomwidgets.dashboard.DashboardDiagnostics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Serializes and coalesces renders per Device widget without using updateAll(). */
class EntityWidgetRenderCoordinator(context: Context) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = ConcurrentHashMap<Int, RenderSlot>()

    fun request(appWidgetId: Int, reason: String) {
        val slot = slots.getOrPut(appWidgetId) { RenderSlot() }
        val requested = slot.requested.incrementAndGet()
        Log.d(TAG, "RENDER_REQUEST processStartId=${DashboardDiagnostics.processStartId} " +
            "widgetId=$appWidgetId widgetType=device reason=$reason monotonicMs=${SystemClock.elapsedRealtime()}")
        startDrain(appWidgetId, slot, requested)
    }

    private fun startDrain(appWidgetId: Int, slot: RenderSlot, requestNumber: Long) {
        if (!slot.running.compareAndSet(false, true)) return
        scope.launch {
            var failed = false
            try {
                while (slot.rendered.get() < slot.requested.get()) {
                    val target = slot.requested.get()
                    val started = SystemClock.elapsedRealtime()
                    EntityStateWidget().update(
                        applicationContext,
                        GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId),
                    )
                    Log.d(TAG, "RENDER_SUCCESS processStartId=${DashboardDiagnostics.processStartId} " +
                        "widgetId=$appWidgetId widgetType=device request=$requestNumber " +
                        "durationMs=${SystemClock.elapsedRealtime() - started}")
                    slot.rendered.set(target)
                }
            } catch (error: Throwable) {
                failed = true
                Log.e(TAG, "RENDER_FAILURE widgetId=$appWidgetId widgetType=device", error)
            } finally {
                slot.running.set(false)
                if (!failed && slot.rendered.get() < slot.requested.get()) {
                    startDrain(appWidgetId, slot, slot.requested.get())
                }
            }
        }
    }

    private data class RenderSlot(
        val requested: AtomicLong = AtomicLong(),
        val rendered: AtomicLong = AtomicLong(),
        val running: AtomicBoolean = AtomicBoolean(false),
    )

    companion object { private const val TAG = "HAWidgetRender" }
}
