package com.danila.hacustomwidgets

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.danila.hacustomwidgets.dashboard.DashboardDiagnostics
import com.danila.hacustomwidgets.data.AppContainer
import com.danila.hacustomwidgets.widget.WidgetSyncWorker

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
        container.dashboardEvents.ensureStarted("APPLICATION_START", reconcileIfStale = false)
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    container.dashboardEvents.ensureStarted("CONNECTIVITY_AVAILABLE")
                }
            })
    }
}
