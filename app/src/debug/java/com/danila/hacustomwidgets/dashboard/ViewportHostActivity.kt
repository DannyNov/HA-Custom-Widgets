package com.danila.hacustomwidgets.dashboard

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.widget.FrameLayout

/** Debug-only surface for unmodified framework AppWidgetHostViews. */
class ViewportHostActivity : ComponentActivity() {
    lateinit var surface: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = FrameLayout(this)
        setContentView(surface)
    }
}

object ViewportProbe {
    val counts = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
}

class ViewportProbeAction : androidx.glance.appwidget.action.ActionCallback {
    override suspend fun onAction(context: android.content.Context, glanceId: androidx.glance.GlanceId,
        parameters: androidx.glance.action.ActionParameters) {
        val id = parameters[DashboardWidgetIdKey] ?: -1
        ViewportProbe.counts.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
    }
}
