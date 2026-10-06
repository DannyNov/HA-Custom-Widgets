package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.data.model.*
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.security.SecureConnectionStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MaintenanceHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun isolated() = object : ContextWrapper(context) {
        val prefix = "maintenance-${UUID.randomUUID()}-"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    }
    private fun battery(value: String = "6") = HaEntity("sensor.charge", value, "Battery", "%", "2026-10-06T00:00:00Z", deviceClass = "battery", entityCategory = "diagnostic")
    private fun catalog(value: String = "6") = HaCatalog(listOf(HaDeviceGroup(HaDevice("a", "Device", areaId = "room"),
        listOf(battery(value), HaEntity("update.a", "off", "Firmware", null, "2026-10-06T00:00:00Z")))), listOf(HaArea("room", "Room")))
    private fun config(id: Int) = DashboardConfig(id, listOf("area:room"), emptyMap(), emptyList(), emptyMap(), emptyMap(), false, true)
    @Test fun legacyDefaultsOnWithoutRewritingPreferences() {
        val c = isolated(); val repo = DashboardRepository(c); repo.saveConfiguration(config(901), catalog())
        val prefs = c.getSharedPreferences("dashboard_widgets", 0); val key = "dashboard_901_config"
        val legacy = JSONObject(prefs.getString(key, null)!!).apply { remove("show_maintenance") }.toString()
        prefs.edit().putString(key, legacy).commit()
        assertTrue(DashboardRepository(c).getConfig(901)!!.showMaintenance)
        assertEquals(legacy, prefs.getString(key, null))
    }
    @Test fun independentWidgetsPersistenceAndSelectedHiddenFallback() {
        val c = isolated(); var repo = DashboardRepository(c)
        for (id in 902..903) repo.saveConfiguration(config(id), catalog())
        repo.setSelectedTab(902, MAINTENANCE_TAB_ID); assertEquals(MAINTENANCE_TAB_ID, repo.get(902)!!.selectedTabId)
        repo.saveConfiguration(config(902).copy(showMaintenance = false), catalog())
        assertEquals("area:room", repo.get(902)!!.selectedTabId)
        assertFalse(repo.get(902)!!.tabs.any { it.id == MAINTENANCE_TAB_ID })
        assertTrue(repo.get(903)!!.tabs.any { it.id == MAINTENANCE_TAB_ID })
        repo = DashboardRepository(c)
        assertFalse(repo.getConfig(902)!!.showMaintenance); assertTrue(repo.getConfig(903)!!.showMaintenance)
        repo.setSelectedTab(902, MAINTENANCE_TAB_ID); assertNotEquals(MAINTENANCE_TAB_ID, repo.get(902)!!.selectedTabId)
        repo.saveConfiguration(repo.getConfig(902)!!.copy(showMaintenance = true), catalog("5"))
        repo.setSelectedTab(902, MAINTENANCE_TAB_ID); assertTrue(repo.get(902)!!.maintenance.attention)
    }
    @Test fun upgradeCatalogIsDueEvenWhenHistoricalTimestampIsFresh() {
        val c = isolated(); val repo = DashboardRepository(c); repo.saveConfiguration(config(909), catalog())
        assertFalse(repo.requiresCatalogRefresh(909))
        val prefs = c.getSharedPreferences("dashboard_structure", 0)
        val key = prefs.all.keys.single(); val legacy = JSONObject(prefs.getString(key, null)!!).apply { remove("batteries"); remove("updates") }
        prefs.edit().putString(key, legacy.toString()).commit()
        assertTrue(DashboardRepository(c).requiresCatalogRefresh(909))
        assertTrue(DashboardRepository(c).getConfig(909)!!.showMaintenance)
    }
    @Test fun eventStatesChangeAttentionAndCatalogRemovalWithoutManualConfiguration() {
        val c = isolated(); val repo = DashboardRepository(c); repo.saveConfiguration(config(904), catalog())
        assertFalse(repo.get(904)!!.maintenance.attention)
        repo.updateEntityStates(904, listOf(battery("5").copy(lastUpdated = "2026-10-06T00:00:01Z")), DashboardStateSource.EVENT)
        assertTrue(repo.get(904)!!.maintenance.attention)
        repo.updateEntityStates(904, listOf(battery("6").copy(lastUpdated = "2026-10-06T00:00:02Z")), DashboardStateSource.EVENT)
        assertFalse(repo.get(904)!!.maintenance.attention)
        repo.updateFromCatalog(904, catalog().copy(groups = emptyList()))
        assertTrue(repo.get(904)!!.maintenance.batteries.isEmpty()); assertFalse("sensor.charge" in repo.entityIds(904))
    }
    @Test fun repairsPersistenceIgnoredAndErrorKeepsLastKnownAlert() {
        val c = isolated(); val repo = DashboardRepository(c); repo.saveConfiguration(config(905), catalog())
        repo.updateRepairs(listOf(RepairIssue("ha", "id", "critical", fixable = true, titles = mapOf("en" to "Broken", "ru" to "Ошибка"))))
        assertTrue(DashboardRepository(c).get(905)!!.maintenance.attention)
        repo.updateRepairs(null, failed = true); assertTrue(repo.get(905)!!.maintenance.repairsError); assertTrue(repo.get(905)!!.maintenance.attention)
        repo.updateRepairs(listOf(RepairIssue("ha", "id", "critical", ignored = true)))
        assertFalse(repo.get(905)!!.maintenance.attention); assertFalse(repo.get(905)!!.maintenance.repairsError)
    }
    @Test fun manualRefreshWorksWithoutNotificationAccessAndRefreshesAllSources() = runBlocking {
        val c = isolated(); val store = SecureConnectionStore(c); store.save("https://fixture.invalid", "fixture")
        val repo = DashboardRepository(c); repo.saveConfiguration(config(906), catalog())
        var catalogs = 0; var repairs = 0
        val coordinator = DashboardEventCoordinator(c, store, HomeAssistantClient(), fetchCatalog = { catalogs++; catalog("5") },
            fetchEntities = { _, _ -> error("manual refresh must fetch catalog") }, dashboards = repo,
            fetchRepairIssues = { repairs++; listOf(RepairIssue("ha", "id", "warning")) })
        assertTrue(coordinator.reconcileNow("TEST", true, 906, DashboardStateSource.MANUAL_REFRESH))
        assertEquals(1, catalogs); assertEquals(1, repairs); assertTrue(repo.get(906)!!.maintenance.attention)
        assertTrue(repo.get(906)!!.maintenance.repairsLoaded)
    }
    @Test fun repairsDoNotCrossHaConnections() {
        val c = isolated(); val store = SecureConnectionStore(c); store.save("https://first.invalid", "fixture")
        val repo = DashboardRepository(c); repo.saveConfiguration(config(908), catalog())
        repo.updateRepairs(listOf(RepairIssue("ha", "id", "critical")))
        assertTrue(repo.get(908)!!.maintenance.attention)
        store.save("https://second.invalid", "fixture")
        assertFalse(repo.get(908)!!.maintenance.attention); assertFalse(repo.get(908)!!.maintenance.repairsLoaded)
        repo.updateRepairs(null, failed = true)
        assertFalse(repo.get(908)!!.maintenance.attention); assertTrue(repo.get(908)!!.maintenance.repairsError)
    }
    private fun flatten(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { flatten(v.getChildAt(it)) } else emptyList()
    @Test fun wrenchNormalRedAndSmallExclamationKeepIdenticalGeometry() {
        fun draw(resource: Int, color: Int): android.graphics.Bitmap {
            val drawable = context.getDrawable(resource)!!.mutate(); drawable.setTint(color); drawable.setBounds(0,0,112,112)
            return android.graphics.Bitmap.createBitmap(112,112,android.graphics.Bitmap.Config.ARGB_8888).also { drawable.draw(android.graphics.Canvas(it)) }
        }
        val normal = draw(com.danila.hacustomwidgets.R.drawable.ic_maintenance, android.graphics.Color.GRAY)
        val alert = draw(com.danila.hacustomwidgets.R.drawable.ic_maintenance_attention, android.graphics.Color.RED)
        var extraPixels = 0; var wrenchPixels = 0
        for (y in 0 until 112) for (x in 0 until 112) {
            val a = normal.getPixel(x,y); val b = alert.getPixel(x,y)
            if (android.graphics.Color.alpha(a) > 0) {
                wrenchPixels++; assertEquals(android.graphics.Color.alpha(a), android.graphics.Color.alpha(b))
                assertEquals(255, android.graphics.Color.red(b)); assertEquals(0, android.graphics.Color.green(b))
            } else if (android.graphics.Color.alpha(b) > 0) {
                extraPixels++; assertTrue(x in 88..96 && y in 4..40)
            }
        }
        assertTrue(wrenchPixels > 0); assertTrue(extraPixels in 1 until wrenchPixels / 4)
        normal.recycle(); alert.recycle()
    }
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun batteryRowsWrapAtNarrowWidthsAndLargeFontInBothLocales() = runBlocking {
        val previous = java.util.Locale.getDefault()
        try {
            for (locale in listOf(java.util.Locale.ENGLISH, java.util.Locale("ru"))) {
                java.util.Locale.setDefault(locale)
                for (width in listOf(160, 230, 320)) for (font in listOf(1f, 1.5f, 2f)) for (value in listOf("5", "6", "unknown", "unavailable")) {
                    val ctx = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { fontScale = font })
                    val row = MaintenanceBattery(battery(value).copy(friendlyName = "Очень длинное имя устройства / Very long device name"), "Room", "a")
                    val remote = GlanceRemoteViews().compose(ctx, DpSize(width.dp, 200.dp)) { GlanceTheme {
                        MaintenanceBatteryRow(row, androidx.glance.unit.ColorProvider(com.danila.hacustomwidgets.R.color.widget_primary), androidx.glance.unit.ColorProvider(com.danila.hacustomwidgets.R.color.widget_secondary))
                    } }.remoteViews
                    instrumentation.runOnMainSync {
                        val v = remote.apply(ctx, null); val density = ctx.resources.displayMetrics.density
                        v.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); v.layout(0,0,v.measuredWidth,v.measuredHeight)
                        val texts = flatten(v).filterIsInstance<TextView>().filter { it.text.isNotEmpty() }
                        assertEquals(2, texts.size)
                        assertTrue(texts.any { it.text.toString().trim() == row.metric.state })
                        texts.forEach { text ->
                            assertNotNull(text.layout)
                            for (line in 0 until text.lineCount) assertEquals(0, text.layout.getEllipsisCount(line))
                            assertTrue(text.height >= text.layout.height)
                        }
                    }
                }
            }
        } finally { java.util.Locale.setDefault(previous) }
    }
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun wrenchTargetsAndNarrowFontScaleMatrix() = runBlocking {
        val c = isolated(); val repo = DashboardRepository(c); repo.saveConfiguration(config(907), catalog())
        for (width in listOf(180, 230, 320)) for (font in listOf(1f, 1.5f, 2f)) for (show in listOf(false, true)) for (attention in listOf(false, true)) {
            val ctx = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { fontScale = font })
            val state = repo.get(907)!!.copy(config = config(907).copy(showMaintenance = show), maintenance = repo.get(907)!!.maintenance.copy(repairs = if (attention) listOf(RepairIssue("ha", "id", "warning")) else emptyList()))
            val remote = GlanceRemoteViews().compose(ctx, DpSize(width.dp, 100.dp)) { GlanceTheme {
                DashboardHeader(ctx, 907, state, width, androidx.glance.unit.ColorProvider(com.danila.hacustomwidgets.R.color.widget_primary), androidx.glance.unit.ColorProvider(com.danila.hacustomwidgets.R.color.widget_accent))
            } }.remoteViews
            instrumentation.runOnMainSync {
                val v = remote.apply(ctx, null); val density = ctx.resources.displayMetrics.density
                v.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); v.layout(0,0,v.measuredWidth,v.measuredHeight)
                val icons = flatten(v).filter { it.contentDescription?.toString() == com.danila.hacustomwidgets.tr("Maintenance", "Обслуживание") }
                assertEquals(if (show) 1 else 0, icons.size)
                if (show) {
                    var target: View? = icons.single()
                    while (target != null && !target.hasOnClickListeners()) target = target.parent as? View
                    assertNotNull(target); assertTrue(target!!.height >= 48 * density - 1); assertTrue(target!!.width >= 48 * density - 1)
                }
            }
        }
    }
}
