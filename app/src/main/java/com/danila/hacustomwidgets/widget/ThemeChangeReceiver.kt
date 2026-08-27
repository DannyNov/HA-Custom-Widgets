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
        if (intent.action != Intent.ACTION_CONFIGURATION_CHANGED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                EntityStateWidget().updateAll(context.applicationContext)
                DashboardWidget().updateAll(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
