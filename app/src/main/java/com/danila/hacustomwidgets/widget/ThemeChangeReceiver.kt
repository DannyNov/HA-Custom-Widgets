package com.danila.hacustomwidgets.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.danila.hacustomwidgets.dashboard.DashboardWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ThemeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_CONFIGURATION_CHANGED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                EntityStateWidget().updateAll(context.applicationContext)
                if (com.danila.hacustomwidgets.dashboard.LegacyCollectionPolicy.useLegacy(android.os.Build.VERSION.SDK_INT)) {
                    val container = (context.applicationContext as com.danila.hacustomwidgets.HaWidgetApplication).container
                    container.dashboards.all().forEach {
                        com.danila.hacustomwidgets.dashboard.DashboardLegacyCollection.update(context, it.appWidgetId, bind = true)
                    }
                } else DashboardWidget().updateAll(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
