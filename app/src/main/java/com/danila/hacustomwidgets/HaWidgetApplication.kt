package com.danila.hacustomwidgets

import android.app.Application
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.danila.hacustomwidgets.dashboard.DashboardDiagnostics
import com.danila.hacustomwidgets.data.AppContainer
import com.danila.hacustomwidgets.widget.WidgetSyncWorker
import com.danila.hacustomwidgets.realtime.RealtimeNotificationAccess
import androidx.core.content.ContextCompat

class HaWidgetApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        Log.i(
            "HAWidgetLifecycle",
            "PROCESS_START processStartId=${DashboardDiagnostics.processStartId} " +
                "monotonicMs=${SystemClock.elapsedRealtime()}",
        )
        WidgetSyncWorker.schedule(this)
        container.dashboardStartup.start()
        val interactive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        container.dashboardEvents.screenInteractiveChanged(interactive)
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_ON -> container.dashboardEvents.screenInteractiveChanged(true)
                        Intent.ACTION_SCREEN_OFF -> container.dashboardEvents.screenInteractiveChanged(false)
                    }
                }
            },
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        Log.i(
            "HAWidgetRealtime",
            "NLS_PERMISSION_STATE processStartId=${DashboardDiagnostics.processStartId} " +
                "granted=${RealtimeNotificationAccess.isGranted(this)}",
        )
        container.dashboardEvents.ensureStarted("APPLICATION_START", reconcileIfStale = false)
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    container.dashboardEvents.connectivityChanged()
                }
            })
    }
}
