package com.danila.hacustomwidgets.realtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object RealtimeNotificationAccess {
    fun isGranted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun settingsIntent(context: Context): Intent {
        val component = ComponentName(context, RealtimeNotificationListenerService::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(EXTRA_LISTENER_COMPONENT, component.flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    }

    private const val EXTRA_LISTENER_COMPONENT =
        "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME"
}
