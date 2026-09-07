package com.danila.hacustomwidgets.dashboard

import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests Android RemoteViews apply/reapply, not just the string used as an item ID. */
@RunWith(AndroidJUnit4::class)
class DashboardLegacyHostTest {
    @Test fun headerUpdatesKeepTheListViewAndItsAdapter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val root = RemoteViews(context.packageName, R.layout.dashboard_legacy).apply(context, null)
            val list = root.findViewById<ListView>(R.id.legacy_list)
            val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, (1..100).map { "Entity $it" })
            list.adapter = adapter
            repeat(4) {
                DashboardLegacyCollection.buildViews(context, 42, null, initial = false).reapply(context, root)
                assertSame(list, root.findViewById(R.id.legacy_list))
                assertSame(adapter, list.adapter)
            }
        }
    }

    @Test fun sensorAndLaunchDrawablesInflateThroughRemoteViews() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            listOf(R.drawable.ic_metric_temperature, R.drawable.ic_metric_humidity,
                R.drawable.ic_metric_battery, R.drawable.ic_launch_play,
                R.drawable.ic_launch_success, R.drawable.ic_launch_pending, R.drawable.ic_launch_error).forEach { resource ->
                val views = RemoteViews(context.packageName, R.layout.dashboard_legacy_button)
                views.setImageViewResource(R.id.legacy_button_icon, resource)
                val root = views.apply(context, null)
                assertNotNull(root.findViewById<ImageView>(R.id.legacy_button_icon).drawable)
            }
        }
    }
}
