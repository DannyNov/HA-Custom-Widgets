package com.danila.hacustomwidgets

import android.app.Application
import com.danila.hacustomwidgets.data.AppContainer
import com.danila.hacustomwidgets.widget.WidgetSyncWorker

class HaWidgetApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        WidgetSyncWorker.schedule(this)
        container.dashboardStartup.start()
        container.dashboardEvents.start()
    }
}
