package com.danila.hacustomwidgets.dashboard

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.data.LegacyEntityWidgetCleanup
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyWidgetCleanupTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(): Context = object : ContextWrapper(base) {
        val prefix = "cleanup-${UUID.randomUUID()}-"
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        override fun getFilesDir() = File(super.getFilesDir(), prefix).apply { mkdirs() }
    }

    @Test fun installedPickerHasOnlyDashboard() {
        val providers = AppWidgetManager.getInstance(base).installedProviders.filter { it.provider.packageName == base.packageName }
        assertEquals(listOf("com.danila.hacustomwidgets.dashboard.DashboardWidgetReceiver"), providers.map { it.provider.className })
        val intent = android.content.Intent("android.appwidget.action.APPWIDGET_CONFIGURE").setPackage(base.packageName)
        assertEquals(listOf("com.danila.hacustomwidgets.dashboard.DashboardWidgetConfigActivity"),
            base.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name })
    }

    @Test fun legacyRecordsAndCachesAreRemovedWithoutTouchingDashboard() {
        val c = isolated()
        val legacy = c.getSharedPreferences("entity_widgets", 0)
        legacy.edit().putStringSet("configured_widget_ids", setOf("701"))
            .putString("widget_701_metrics", "[{\"entity_id\":\"sensor.old\"}]")
            .putString("widget_702_entity_id", "sensor.orphan").commit()
        val dashboard = c.getSharedPreferences("dashboard_widgets", 0)
        dashboard.edit().putStringSet("configured_dashboard_ids", setOf("703"))
            .putString("dashboard_703", "unchanged config fixture").commit()
        val before = dashboard.all.toMap()
        val freshness = c.getSharedPreferences("dashboard_sync_freshness", 0)
        freshness.edit().putLong("widget_701_last_confirmed_sync", 1)
            .putLong("widget_702_last_confirmed_sync", 2).putLong("widget_703_last_confirmed_sync", 3).commit()
        val keep = File(c.filesDir, "datastore/appWidgetLayout-703").apply { parentFile!!.mkdirs(); writeText("dashboard") }
        val old = File(c.filesDir, "datastore/appWidgetLayout-701").apply { writeText("old") }
        val oldState = File(c.filesDir, "datastore/appWidget-702.preferences_pb").apply { writeText("old") }
        LegacyEntityWidgetCleanup.run(c)
        LegacyEntityWidgetCleanup.run(c)
        assertTrue(legacy.all.isEmpty())
        assertEquals(before, dashboard.all)
        assertEquals(mapOf("widget_703_last_confirmed_sync" to 3L), freshness.all)
        assertFalse(old.exists()); assertFalse(oldState.exists()); assertEquals("dashboard", keep.readText())
    }

    @Test fun noLegacyStateIsANoOp() {
        val c = isolated()
        val prefs = c.getSharedPreferences("dashboard_widgets", 0)
        prefs.edit().putString("preserved", "value").commit()
        LegacyEntityWidgetCleanup.run(c)
        assertEquals(mapOf("preserved" to "value"), prefs.all)
        assertTrue(c.getSharedPreferences("entity_widgets", 0).all.isEmpty())
    }

    @Test fun obsoleteGlanceIndexIsRebuiltEvenWithoutLegacyConfiguration() {
        val c = isolated()
        val index = File(c.filesDir, "datastore/GlanceAppWidgetManager.preferences_pb")
        index.parentFile!!.mkdirs()
        index.writeText("com.danila.hacustomwidgets.widget.EntityStateWidgetReceiver")
        LegacyEntityWidgetCleanup.run(c)
        assertFalse(index.exists())
        index.writeText("com.danila.hacustomwidgets.dashboard.DashboardWidgetReceiver")
        LegacyEntityWidgetCleanup.run(c)
        assertTrue(index.exists())
    }
}
