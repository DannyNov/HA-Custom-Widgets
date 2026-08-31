package com.danila.hacustomwidgets.realtime

import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.danila.hacustomwidgets.HaWidgetApplication
import com.danila.hacustomwidgets.dashboard.DashboardDiagnostics

/**
 * System-bound lifetime adapter for real-time widget updates.
 *
 * Notification contents are intentionally not observed or processed here. The process-scoped
 * coordinator owns the Home Assistant transport, subscriptions, repositories and rendering.
 */
class RealtimeNotificationListenerService : NotificationListenerService() {
    private val bindingId = java.util.UUID.randomUUID().toString()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(
            TAG,
            "NLS_CONNECTED processStartId=${DashboardDiagnostics.processStartId} " +
                "bindingId=$bindingId timestamp=${SystemClock.elapsedRealtime()}",
        )
        (application as? HaWidgetApplication)?.container?.dashboardEvents?.systemBindingConnected(bindingId)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(
            TAG,
            "NLS_DISCONNECTED processStartId=${DashboardDiagnostics.processStartId} " +
                "bindingId=$bindingId timestamp=${SystemClock.elapsedRealtime()}",
        )
        (application as? HaWidgetApplication)?.container?.dashboardEvents?.systemBindingLost(bindingId)
    }

    companion object { private const val TAG = "HAWidgetRealtime" }
}
