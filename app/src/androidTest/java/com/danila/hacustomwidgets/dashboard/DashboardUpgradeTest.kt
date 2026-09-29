package com.danila.hacustomwidgets.dashboard

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.data.model.*
import com.danila.hacustomwidgets.data.security.SecureConnectionStore
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** CI installs this test APK against stable, seeds real bound IDs, then replaces only the app APK. */
@RunWith(AndroidJUnit4::class)
class DashboardUpgradeTest {
    @Test fun upgradeFixture() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("upgradePhase") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AppWidgetManager.getInstance(context)
        val marker = File(context.filesDir, "dashboard-upgrade-fixture")
        val repo = DashboardRepository(context)
        val connection = SecureConnectionStore(context)
        fun assertConnection() {
            val saved = context.getSharedPreferences("ha_connection", 0)
            for (key in listOf("base_url", "access_token", "token_iv")) {
                assertTrue("$phase: persisted connection field missing: $key", saved.contains(key))
            }
            val loaded = connection.load()
            assertNotNull("$phase: connection fields exist but decryption failed", loaded)
            assertEquals("https://upgrade-fixture.invalid", loaded!!.baseUrl)
            assertEquals("upgrade-fixture-token", loaded.token)
        }
        if (phase == "seed") {
            val host = AppWidgetHost(context, 6201)
            fun bind(className: String): Int {
                val id = host.allocateAppWidgetId()
                assertTrue("Binding $className failed", manager.bindAppWidgetIdIfAllowed(id, ComponentName(context.packageName, className)))
                return id
            }
            val dashboardId = bind("com.danila.hacustomwidgets.dashboard.DashboardWidgetReceiver")
            val entity = HaEntity("switch.upgrade", "on", "Upgrade switch", null, "2026-09-28T00:00:00Z")
            val catalog = HaCatalog(listOf(HaDeviceGroup(HaDevice("upgrade", "Upgrade", areaId = "room"), listOf(entity))), listOf(HaArea("room", "Room")))
            val config = DashboardConfig(dashboardId, listOf("area:room"), mapOf("area:room" to DashboardGrouping.NONE),
                listOf("upgrade"), mapOf("upgrade" to listOf("switch.upgrade")), mapOf("area:room" to listOf("upgrade", "hidden")), false, true,
                hiddenDeviceIdsByContext = mapOf("area:room" to listOf("hidden")),
                hiddenEntityIdsByContext = mapOf("area:room" to listOf("sensor.hidden")),
                autoOffTimersByDevice = mapOf("upgrade" to AutoOffTimerConfig(enabled = true, timerEntityId = "timer.upgrade", controlEntityId = "switch.upgrade")))
            repo.saveConfiguration(config, catalog)
            connection.save("https://upgrade-fixture.invalid", "upgrade-fixture-token")
            assertConnection()
            val legacyId = if (args.getString("withLegacy") == "true") {
                val id = bind("com.danila.hacustomwidgets.widget.EntityStateWidgetReceiver")
                context.getSharedPreferences("entity_widgets", 0).edit()
                    .putStringSet("configured_widget_ids", setOf(id.toString()))
                    .putString("widget_${id}_metrics", "[{\"entity_id\":\"sensor.legacy\",\"label\":\"Legacy\",\"state\":\"22\"}]")
                    .putString("widget_${id}_title", "Legacy fixture").commit()
                context.getSharedPreferences("dashboard_sync_freshness", 0).edit().putLong("widget_${id}_last_confirmed_sync", 1).commit()
                id
            } else -1
            val prefs = context.getSharedPreferences("dashboard_widgets", 0)
            prefs.edit().commit()
            ObjectOutputStream(marker.outputStream()).use {
                it.writeInt(dashboardId); it.writeInt(legacyId); it.writeObject(HashMap(prefs.all))
            }
            assertNotNull(manager.getAppWidgetInfo(dashboardId))
            if (legacyId != -1) assertNotNull(manager.getAppWidgetInfo(legacyId))
        } else {
            assertTrue("Unexpected phase: $phase", phase == "verify" || phase == "preUpgrade")
            ObjectInputStream(marker.inputStream()).use {
                val dashboardId = it.readInt(); val legacyId = it.readInt()
                val before = it.readObject() as Map<*, *>
                assertEquals(before, context.getSharedPreferences("dashboard_widgets", 0).all)
                assertNotNull(repo.getConfig(dashboardId))
                assertConnection()
                val dashboardInfo = manager.getAppWidgetInfo(dashboardId)
                assertNotNull("$phase: bound Dashboard ID was lost", dashboardInfo)
                assertEquals("com.danila.hacustomwidgets.dashboard.DashboardWidgetReceiver", dashboardInfo!!.provider.className)
                if (phase == "preUpgrade") {
                    if (legacyId != -1) assertNotNull("Seeded legacy widget must exist before upgrade", manager.getAppWidgetInfo(legacyId))
                    return
                }
                if (legacyId != -1) {
                    assertNull(manager.getAppWidgetInfo(legacyId))
                    assertFalse(context.getSharedPreferences("dashboard_sync_freshness", 0).contains("widget_${legacyId}_last_confirmed_sync"))
                }
                assertTrue(context.getSharedPreferences("entity_widgets", 0).all.isEmpty())
                assertEquals(listOf("com.danila.hacustomwidgets.dashboard.DashboardWidgetReceiver"),
                    manager.installedProviders.filter { p -> p.provider.packageName == context.packageName }.map { p -> p.provider.className })
            }
        }
    }
}
