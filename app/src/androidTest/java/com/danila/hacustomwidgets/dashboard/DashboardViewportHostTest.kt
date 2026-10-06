package com.danila.hacustomwidgets.dashboard

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ListView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.data.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual framework ListViews, adapters, layout and host reapply; no stored-index substitute. */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(AndroidJUnit4::class)
class DashboardViewportHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun isolated() = object : ContextWrapper(context) {
        private val prefix = "viewport-${UUID.randomUUID()}-"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    }
    private fun catalog(spaces: Int = 2) = HaCatalog(
        (0 until spaces).flatMap { space -> (0 until 36).map { index ->
            val key = "d-$space-$index"
            HaDeviceGroup(HaDevice(key, "Device $key", areaId = "r$space"), listOf(
                HaEntity("sensor.$key", "50", "Battery $key", "%", "2026-10-06T00:00:00Z", deviceClass = "battery"),
                HaEntity("script.$key", "off", "Scenario $key", null, "2026-10-06T00:00:00Z"),
            ))
        } }, (0 until spaces).map { HaArea("r$it", "Space $it") })
    private fun state(id: Int = 9301, spaces: Int = 2): DashboardState {
        val repo = DashboardRepository(isolated())
        val cat = catalog(spaces)
        val ids = (0 until spaces).map { "area:r$it" }
        repo.saveConfiguration(DashboardConfig(id, ids, ids.associateWith { DashboardGrouping.NONE },
            cat.groups.map { it.device!!.id }, emptyMap(), emptyMap(), false, true), cat)
        repo.setSelectedTab(id, ids.first())
        return repo.get(id)!!
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private data class Anchor(val id: Long, val top: Int)
    private inner class Fixture(val activity: ViewportHostActivity, val widgetId: Int, suppliedHost: AppWidgetHostView? = null) {
        val composer = GlanceRemoteViews()
        lateinit var host: AppWidgetHostView
        init { instrumentation.runOnMainSync {
            val provider = android.appwidget.AppWidgetManager.getInstance(context).installedProviders.single {
                it.provider == ComponentName(context, DashboardWidgetReceiver::class.java)
            }
            host = suppliedHost ?: AppWidgetHostView(activity).apply { setAppWidget(widgetId, provider) }
            activity.surface.addView(host, FrameLayout.LayoutParams(320.px(), 440.px()))
        } }
        fun publish(state: DashboardState) {
            val remote = runBlocking { DashboardCollectionsRenderer.dashboard(context, state, widgetId, DpSize(320.dp, 440.dp)) }
            publishRemote(remote, state.selectedTabId)
        }
        fun publishRemote(remote: android.widget.RemoteViews, tab: String) {
            val parcel = android.os.Parcel.obtain()
            val transported = try {
                remote.writeToParcel(parcel, 0)
                android.util.Log.i("ViewportContract", "widget=$widgetId tab=$tab parcelBytes=${parcel.dataSize()}")
                assertTrue("RemoteViews must fit the Binder transaction budget: ${parcel.dataSize()} bytes", parcel.dataSize() < 900_000)
                parcel.setDataPosition(0)
                android.widget.RemoteViews.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
            instrumentation.runOnMainSync { host.updateAppWidget(transported) }
            settle()
            assertTrue("Host must contain real collections, not an error view", lists().isNotEmpty())
            assertEquals("Every space must have a distinct hierarchy-state key", lists().size, lists().map { it.id }.distinct().size)
        }
        fun lists() = descendants(host).filterIsInstance<ListView>()
        fun visible() = lists().single { it.visibility == View.VISIBLE }
        fun settle() {
            // IdleSync does not wait for the next Choreographer layout frame. Observe the
            // real attached-host frame instead of manually using stale measurements.
            val frame = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync {
                host.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        val list = lists().singleOrNull { it.visibility == View.VISIBLE }
                        if (list != null && list.childCount > 0 && !list.isLayoutRequested) {
                            host.viewTreeObserver.removeOnPreDrawListener(this)
                            frame.countDown()
                        }
                        return true
                    }
                })
                host.requestLayout()
            }
            assertTrue("Attached host must complete layout with real list children", frame.await(10, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        }
        fun scroll(position: Int, top: Int = -11): Anchor {
            instrumentation.setInTouchMode(true)
            instrumentation.runOnMainSync { visible().setSelectionFromTop(position, top) }
            settle()
            return anchor().also { assertTrue("Fixture must really scroll", visible().firstVisiblePosition > 0) }
        }
        fun anchor(): Anchor = visible().let { Anchor(it.getItemIdAtPosition(it.firstVisiblePosition), it.getChildAt(0).top) }
        fun assertAnchor(expected: Anchor) {
            assertEquals("first visible stable item", expected.id, anchor().id)
            assertEquals("first visible top offset", expected.top, anchor().top)
        }
        fun dispose() = instrumentation.runOnMainSync { activity.surface.removeView(host) }
    }
    private fun Int.px() = (this * context.resources.displayMetrics.density).toInt()
    private fun withHost(block: (ViewportHostActivity) -> Unit) {
        instrumentation.setInTouchMode(true)
        ActivityScenario.launch<ViewportHostActivity>(Intent(context, ViewportHostActivity::class.java)).use { scenario ->
            lateinit var activity: ViewportHostActivity
            scenario.onActivity { activity = it }
            block(activity)
        }
    }
    @Test fun spacesRememberDifferentActualAnchorsAndOffsets() = withHost { activity ->
        val state = state(); val fixture = Fixture(activity, state.config.appWidgetId)
        fixture.publish(state); val a = fixture.scroll(17)
        val aView = fixture.visible()
        fixture.publish(state.copy(selectedTabId = "area:r1")); val b = fixture.scroll(9, -7)
        fixture.publish(state); assertSame(aView, fixture.visible()); fixture.assertAnchor(a)
        fixture.publish(state.copy(selectedTabId = "area:r1")); fixture.assertAnchor(b)
    }
    @Test fun systemCollectionsRememberTheirOwnAnchors() = withHost { activity ->
        val state = state(); val fixture = Fixture(activity, state.config.appWidgetId)
        val anchors = linkedMapOf<String, Anchor>()
        for (tab in listOf(MAIN_TAB_ID, SCENARIOS_TAB_ID, MAINTENANCE_TAB_ID)) {
            fixture.publish(state.copy(selectedTabId = tab)); anchors[tab] = fixture.scroll(16)
        }
        for ((tab, anchor) in anchors) { fixture.publish(state.copy(selectedTabId = tab)); fixture.assertAnchor(anchor) }
    }
    @Test fun separateWidgetHostsNeverShareViewport() = withHost { activity ->
        val state = state(); val first = Fixture(activity, 9301); val second = Fixture(activity, 9302)
        first.publish(state); val a = first.scroll(19)
        second.publish(state.copy(config = state.config.copy(appWidgetId = 9302))); val b = second.scroll(8)
        first.publish(state.copy(selectedTabId = "area:r1")); first.publish(state); first.assertAnchor(a)
        second.publish(state.copy(config = state.config.copy(appWidgetId = 9302))); second.assertAnchor(b)
    }
    @Test fun refreshRecompositionPreservesActualViewAndViewport() = withHost { activity ->
        val state = state(); val fixture = Fixture(activity, 9301)
        fixture.publish(state); val anchor = fixture.scroll(18); val list = fixture.visible()
        repeat(4) { revision ->
            fixture.publish(state.copy(stateRevision = state.stateRevision + revision + 1,
                refreshInProgress = revision % 2 == 0, error = if (revision == 2) "offline" else null,
                cards = state.cards.map { card -> card.copy(metrics = card.metrics.map { it.copy(state = "49", rawState = "49") }) }))
            assertSame(list, fixture.visible()); fixture.assertAnchor(anchor)
        }
    }
    @Test fun removedAnchorHasNonemptySafeFallback() = withHost { activity ->
        val state = state(); val fixture = Fixture(activity, 9301)
        fixture.publish(state); val anchor = fixture.scroll(18)
        val removed = state.cards.first { DashboardStatePolicy.stableCollectionId("card:${it.key}") == anchor.id }
        fixture.publish(state.copy(cards = state.cards - removed))
        assertTrue(fixture.visible().count > 0); assertTrue(fixture.visible().childCount > 0)
        assertNotEquals(anchor.id, fixture.anchor().id)
        fixture.publish(state.copy(selectedTabId = "area:r1", cards = state.cards - removed))
        fixture.publish(state.copy(cards = state.cards - removed))
        assertTrue(fixture.visible().childCount > 0)
    }
    @Test fun stableAnchorSurvivesCardReorderAndCollapsedSections() = withHost { activity ->
        val state = state(); val fixture = Fixture(activity, 9301)
        fixture.publish(state); fixture.scroll(18)
        instrumentation.setInTouchMode(false)
        instrumentation.runOnMainSync { fixture.visible().setSelectionFromTop(18, 0) }
        fixture.settle(); val anchor = fixture.anchor()
        val keys = state.cards.filter { it.areaId == "r0" }.map { it.key }
        fixture.publish(state.copy(config = state.config.copy(cardOrderBySpace = mapOf("area:r0" to keys.reversed()))))
        assertEquals(anchor.id, fixture.anchor().id)
        val grouped = state.copy(config = state.config.copy(groupingBySpace = mapOf("area:r0" to DashboardGrouping.TYPES)))
        fixture.publish(grouped)
        fixture.publish(grouped.copy(collapsedSections = dashboardSections(grouped).map { it.key }.toSet()))
        assertTrue(fixture.visible().childCount > 0)
        fixture.publish(grouped); assertTrue(fixture.visible().count > 20)
    }
    @Test fun moreThanTenCollectionsAreNotTruncatedAndTabOrderDoesNotSwapIds() = withHost { activity ->
        val state = state(spaces = 8); val fixture = Fixture(activity, 9301)
        fixture.publish(state.copy(selectedTabId = "area:r7")); val anchor = fixture.scroll(15)
        assertEquals(12, fixture.lists().size)
        fixture.publish(state.copy(selectedTabId = "area:r7", config = state.config.copy(spaceOrderIds = state.config.spaceOrderIds.reversed())))
        fixture.assertAnchor(anchor)
    }
    @Test fun touchModeReorderRetainsUsableViewportWithoutStableIdSearchPromise() = withHost { activity ->
        val state = state(); val fixture = Fixture(activity, 9301)
        fixture.publish(state); fixture.scroll(18)
        val before = fixture.visible().firstVisiblePosition
        val keys = state.cards.filter { it.areaId == "r0" }.map { it.key }
        fixture.publish(state.copy(config = state.config.copy(cardOrderBySpace = mapOf("area:r0" to keys.reversed()))))
        assertTrue(fixture.visible().childCount > 0)
        assertEquals(before, fixture.visible().firstVisiblePosition)
        assertEquals(keys.size, fixture.visible().count)
    }
    @Test fun hidingAndShowingSystemTabsRetainsOtherCollectionViews() = withHost { activity ->
        val state = state(); val fixture = Fixture(activity, 9301)
        fixture.publish(state); val anchor = fixture.scroll(17); val list = fixture.visible()
        fixture.publish(state.copy(config = state.config.copy(showFavorites = false, showMaintenance = false)))
        assertSame(list, fixture.visible()); fixture.assertAnchor(anchor)
        fixture.publish(state); assertSame(list, fixture.visible()); fixture.assertAnchor(anchor)
    }
    @Test fun frameworkSavedHierarchyRestoresViewportAfterHostRecreation() = withHost { activity ->
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val provider = manager.installedProviders.single { it.provider == ComponentName(context, DashboardWidgetReceiver::class.java) }
        val owner = android.appwidget.AppWidgetHost(context, 98741)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
        val id = owner.allocateAppWidgetId()
        try {
            assertTrue("Real framework widget binding", manager.bindAppWidgetIdIfAllowed(id, provider.provider))
            val state = state().let { it.copy(config = it.config.copy(appWidgetId = id)) }
            val remote = runBlocking { DashboardCollectionsRenderer.dashboard(context, state, id, DpSize(320.dp, 440.dp)) }
            manager.updateAppWidget(id, remote)
            lateinit var original: AppWidgetHostView
            instrumentation.runOnMainSync { original = owner.createView(activity, id, provider) }
            val fixture = Fixture(activity, id, original)
            fixture.settle(); val anchor = fixture.scroll(17)
            val saved = android.util.SparseArray<android.os.Parcelable>()
            instrumentation.runOnMainSync { fixture.host.saveHierarchyState(saved) }
            fixture.dispose()
            lateinit var replacement: AppWidgetHostView
            instrumentation.runOnMainSync { replacement = owner.createView(activity, id, provider) }
            val recreated = Fixture(activity, id, replacement)
            recreated.settle()
            instrumentation.runOnMainSync { recreated.host.restoreHierarchyState(saved) }
            recreated.settle(); recreated.assertAnchor(anchor)
        } finally {
            owner.deleteAppWidgetId(id)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
    @Test fun narrowLargeFontRendererKeepsExactlyOneVisibleCollection() = runBlocking {
        val state = state()
        for (width in listOf(180, 230, 320)) for (scale in listOf(1f, 1.5f, 2f)) {
            val themed = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { fontScale = scale })
            for (tab in listOf("area:r0", MAIN_TAB_ID, SCENARIOS_TAB_ID, MAINTENANCE_TAB_ID)) {
                val body = DashboardCollectionsRenderer.render(themed, state.copy(selectedTabId = tab), 9301, DpSize(width.dp, 440.dp)).body
                val remote = GlanceRemoteViews().compose(themed, DpSize(width.dp, 440.dp)) {
                    GlanceTheme { DashboardContent(themed, state.copy(selectedTabId = tab), 9301, DpSize(width.dp, 440.dp), body) }
                }.remoteViews
                instrumentation.runOnMainSync {
                    val view = remote.apply(themed, null)
                    val lists = descendants(view).filterIsInstance<ListView>()
                    assertEquals(1, lists.count { it.visibility == View.VISIBLE })
                    view.measure(View.MeasureSpec.makeMeasureSpec((width * themed.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(440.px(), View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                    assertTrue(lists.single { it.visibility == View.VISIBLE }.height > 0)
                }
            }
        }
    }
    @Test fun rc2ConfigurationNeedsNoViewportMigrationAndNewHostStartsAtTop() = withHost { activity ->
        val state = state(); val fixture = Fixture(activity, 9301)
        fixture.publish(state); assertEquals(0, fixture.visible().firstVisiblePosition)
        fixture.scroll(17); fixture.dispose()
        val rebound = Fixture(activity, 9301); rebound.publish(state)
        assertEquals(0, rebound.visible().firstVisiblePosition)
        // A completely new host has no provider-readable previous scroll state.
    }
    @Test fun nativeCollectionPreservesRealGlanceFillInActionTransport() = withHost { activity ->
        val id = 9341; val viewId = 0x00e12345
        ViewportProbe.counts.remove(id)
        val items = runBlocking { composeGlanceCollection(context, id, viewId, DpSize(320.dp, 440.dp)) {
            androidx.glance.appwidget.lazy.LazyColumn {
                item(itemId = 941L) {
                    androidx.glance.text.Text("Viewport action probe", modifier = androidx.glance.GlanceModifier.clickable(
                            androidx.glance.appwidget.action.actionRunCallback<ViewportProbeAction>(
                                androidx.glance.action.actionParametersOf(DashboardWidgetIdKey to id))))
                }
            }
        } }
        val native = android.widget.RemoteViews(context.packageName, com.danila.hacustomwidgets.R.layout.dashboard_collection_list, viewId).apply {
            setPendingIntentTemplate(viewId, DashboardCollectionsRenderer.template(context))
            setRemoteAdapter(viewId, items)
        }
        val fixture = Fixture(activity, id); fixture.publishRemote(native, "probe")
        instrumentation.runOnMainSync {
            val target = descendants(fixture.visible()).filterIsInstance<android.widget.TextView>().single { it.text.toString() == "Viewport action probe" }
            val clickable = generateSequence<View>(target) { it.parent as? View }.first { it.hasOnClickListeners() }
            assertTrue(clickable.performClick())
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while ((ViewportProbe.counts[id]?.get() ?: 0) == 0 && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(25)
        assertEquals(1, ViewportProbe.counts[id]?.get())
        assertFalse(ViewportProbe.counts.containsKey(-1))
    }
}
