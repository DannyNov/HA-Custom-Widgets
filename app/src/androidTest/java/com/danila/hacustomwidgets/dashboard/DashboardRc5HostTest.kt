package com.danila.hacustomwidgets.dashboard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ListView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class DashboardRc5HostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(message: String, predicate: () -> Boolean) {
        repeat(150) {
            var done = false
            instrumentation.runOnMainSync { done = predicate() }
            if (done) return
            Thread.sleep(100)
        }
        fail(message)
    }

    @Test fun realRemoteAdapterDeliversClicksAndPreservesViewportAcrossTwentyUpdates() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "appwidget grantbind --package ${context.packageName} --user 0")).use { it.readBytes() }
        val activity = instrumentation.startActivitySync(Intent(context, ScrollPrototypeHost::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ScrollPrototypeHost
        val manager = AppWidgetManager.getInstance(context)
        var widgetId = -1
        lateinit var list: ListView
        var observedCount = -1
        var observedChildren = -1
        try {
            instrumentation.runOnMainSync {
                ScrollPrototypeData.revision = 0
                ScrollPrototypeData.clicks.clear()
                widgetId = activity.host.allocateAppWidgetId()
                val component = ComponentName(context, ScrollPrototypeProvider::class.java)
                assertTrue("Fixture widget must bind", manager.bindAppWidgetIdIfAllowed(widgetId, component))
                val info = manager.getAppWidgetInfo(widgetId)
                val hostView = activity.host.createView(activity, widgetId, info)
                activity.content.addView(hostView)
                val views = DashboardStableCollection.buildViews(context, widgetId, ScrollPrototypeData.state(42), true,
                    Intent(context, ScrollPrototypeService::class.java).setData(Uri.parse("hacw://rc5-test/$widgetId")))
                views.setPendingIntentTemplate(R.id.legacy_list, PendingIntent.getBroadcast(context, widgetId,
                    Intent(context, ScrollPrototypeReceiver::class.java).setData(Uri.parse("hacw://rc5-click/$widgetId")),
                    PendingIntent.FLAG_UPDATE_CURRENT))
                manager.updateAppWidget(widgetId, views)
            }
            try { await("Real remote list did not populate") {
                val candidate = activity.content.findViewById<ListView>(R.id.legacy_list)
                observedCount = candidate?.count ?: -1
                observedChildren = candidate?.childCount ?: -1
                if (candidate != null && candidate.count >= 30 && candidate.childCount > 0) { list = candidate; true } else false
            } } catch (error: AssertionError) {
                var hierarchy = ""
                instrumentation.runOnMainSync {
                    fun describe(view: View): String = view.javaClass.simpleName + ":" + view.id +
                        (if (view is android.widget.TextView) ":${view.text}" else "") +
                        (if (view is android.view.ViewGroup) (0 until view.childCount).joinToString(prefix = "[", postfix = "]") { describe(view.getChildAt(it)) } else "")
                    hierarchy = describe(activity.content)
                }
                throw AssertionError("Remote list count=$observedCount children=$observedChildren; factory=${ScrollPrototypeData.factoryRows}; $hierarchy", error)
            }
            val fixture = ScrollPrototypeData.state(42)
            val deliveredKinds = mutableSetOf<String>()
            for (index in 0..9) {
                instrumentation.runOnMainSync { list.setSelectionFromTop(index, 0) }
                await("Fixture $index not visible") { list.firstVisiblePosition == index &&
                    list.getChildAt(0).findViewById<android.widget.TextView>(R.id.stable_title)?.text?.startsWith("Fixture ${index.toString().padStart(2, '0')}") == true }
                for (slot in 0..7) {
                    var clicked = false
                    instrumentation.runOnMainSync {
                        val button = list.getChildAt(0).findViewById<View>(context.resources.getIdentifier("stable_button_$slot", "id", context.packageName))
                        if (button.isShown && button.isEnabled) { assertTrue(button.performClick()); clicked = true }
                    }
                    if (clicked) {
                        val delivered = ScrollPrototypeData.clicks.poll(5, TimeUnit.SECONDS)
                        assertNotNull("Missing click row=$index slot=$slot", delivered)
                        val card = fixture.cards[index]
                        assertEquals(card.key, delivered!!.getStringExtra("key"))
                        val kind = delivered.getStringExtra("action")!!
                        if (kind != "timer") {
                            val entity = card.controls.single { it.entityId == delivered.getStringExtra("entity") }
                            assertEquals(entity.domain, delivered.getStringExtra("domain"))
                            deliveredKinds.add("$kind:${entity.domain}")
                        } else deliveredKinds.add("timer")
                    }
                }
            }
            assertTrue(deliveredKinds.containsAll(setOf("control:switch", "control:light", "control:automation",
                "scenario:automation", "scenario:script", "scenario:scene", "timer")))
            instrumentation.runOnMainSync { list.setSelectionFromTop(15, -7) }
            await("Did not scroll to middle") { list.firstVisiblePosition == 15 }
            var jumpedToTop = false
            instrumentation.runOnMainSync {
                list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                    override fun onScrollStateChanged(view: android.widget.AbsListView?, state: Int) = Unit
                    override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                        if (first == 0 && total > 0) jumpedToTop = true
                    }
                })
            }
            repeat(20) { iteration ->
                var anchor = 0L
                var position = 0
                var offset = 0
                instrumentation.runOnMainSync {
                    anchor = list.getItemIdAtPosition(list.firstVisiblePosition)
                    position = list.firstVisiblePosition
                    offset = list.getChildAt(0).top
                    val button = list.getChildAt(iteration % 2).findViewById<View>(R.id.stable_button_0)
                    assertNotNull(button)
                    assertTrue("Click must have listener on API ${android.os.Build.VERSION.SDK_INT}", button.performClick())
                }
                val intent = ScrollPrototypeData.clicks.poll(5, TimeUnit.SECONDS)
                assertNotNull("Click was not delivered on pass $iteration", intent)
                assertEquals("control", intent!!.getStringExtra("action"))
                assertEquals(if (iteration % 2 == 0) "switch" else "light", intent.getStringExtra("domain"))
                assertEquals(42, intent.getIntExtra("widget", -1))
                assertTrue(intent.getStringExtra("entity").orEmpty().isNotEmpty())
                ScrollPrototypeData.revision++
                instrumentation.runOnMainSync {
                    manager.partiallyUpdateAppWidget(widgetId,
                        DashboardStableCollection.buildViews(context, widgetId, ScrollPrototypeData.state(42), false))
                    manager.notifyAppWidgetViewDataChanged(widgetId, R.id.legacy_list)
                }
                await("Host did not apply revision ${ScrollPrototypeData.revision}") {
                    list.getChildAt(0)?.findViewById<android.widget.TextView>(R.id.stable_title)?.text?.endsWith("· r${ScrollPrototypeData.revision}") == true
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals("Position reset on pass $iteration", position, list.firstVisiblePosition)
                    assertFalse("Transient jump-to-top on pass $iteration", jumpedToTop)
                    assertEquals("Anchor changed on pass $iteration", anchor, list.getItemIdAtPosition(list.firstVisiblePosition))
                    assertTrue("Offset changed on pass $iteration", kotlin.math.abs(offset - list.getChildAt(0).top) <= 2)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                if (widgetId >= 0) activity.host.deleteAppWidgetId(widgetId)
                activity.finish()
            }
        }
    }

    @Test fun staticRowsReapplyWithoutAccumulatingChildrenOrPendingGlyphs() {
        instrumentation.runOnMainSync {
            val renderer = DashboardStableRows(context, 42)
            val state = ScrollPrototypeData.state(42)
            val roots = mutableMapOf<Int, View>()
            repeat(20) {
                state.cards.forEach { card ->
                    val views = renderer.card(card, state).first().views
                    val root = roots.getOrPut(views.layoutId) { views.apply(context, null) }
                    views.reapply(context, root)
                    val fresh = views.apply(context, null)
                    fun structure(view: View): String = buildString {
                        append(view.id).append(':').append(view.visibility)
                        if (view is android.widget.TextView) append(view.text)
                        if (view is android.view.ViewGroup) repeat(view.childCount) { append(structure(view.getChildAt(it))) }
                    }
                    assertEquals(structure(fresh), structure(root))
                }
            }
        }
    }

    @Test fun staticGlyphSlotReplacesPendingAndRestoresNormalAfterReapply() {
        instrumentation.runOnMainSync {
            val renderer = DashboardStableRows(context, 42)
            val state = ScrollPrototypeData.state(42)
            fun pixels(drawable: android.graphics.drawable.Drawable): IntArray {
                val bitmap = android.graphics.Bitmap.createBitmap(28, 28, android.graphics.Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 28, 28)
                drawable.draw(android.graphics.Canvas(bitmap))
                return IntArray(28 * 28).also { bitmap.getPixels(it, 0, 28, 0, 0, 28, 28); bitmap.recycle() }
            }
            val pendingPixels = pixels(context.getDrawable(R.drawable.ic_launch_pending)!!)
            listOf("light", "switch", "automation", "script", "scene", "timer").forEach { domain ->
                val control = DashboardControl("$domain.pending", domain, domain, "on")
                val card = DashboardCard(if (domain in setOf("automation", "script", "scene")) "scenario:$domain" else domain,
                    domain, null, null, DeviceCategory.OTHER, emptyList(), listOf(control), scenarioRunnable = true)
                val normal = renderer.card(card, state).first().views
                val root = normal.apply(context, null)
                val icon = root.findViewById<android.widget.ImageView>(R.id.stable_icon_0)
                val normalPixels = pixels(icon.drawable)
                DashboardOperationStatus.entries.filter { it.isActive }.forEach { status ->
                    val busy = state.copy(operationStatusByEntity = mapOf(control.entityId to status),
                        scenarioRunStatusByEntity = mapOf(control.entityId to status))
                    renderer.card(card, busy).first().views.reapply(context, root)
                    assertArrayEquals("Pending glyph for $domain", pendingPixels, pixels(icon.drawable))
                    assertEquals(View.VISIBLE, icon.visibility)
                    normal.reapply(context, root)
                    assertArrayEquals("Restored glyph for $domain", normalPixels, pixels(icon.drawable))
                }
            }
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun glancePendingContainsOnlyReplacementGlyph() = runBlocking {
        val composer = GlanceRemoteViews()
        for (domain in listOf("light", "switch", "automation", "script", "scene", "timer")) {
            val control = DashboardControl(if (domain == "timer") "switch.timer" else "$domain.pending",
                domain, if (domain == "timer") "switch" else domain, "on")
            val card = DashboardCard(if (domain in setOf("automation", "script", "scene")) "scenario:$domain" else domain,
                domain, null, null, DeviceCategory.OTHER, emptyList(), listOf(control),
                autoOffTimer = if (domain == "timer") AutoOffTimerConfig(true, "timer.pending") else null,
                scenarioRunnable = true)
            val statuses = mapOf(control.entityId to DashboardOperationStatus.PENDING, "timer.pending" to DashboardOperationStatus.PENDING)
            val views = composer.compose(context, DpSize(250.dp, 500.dp)) {
                GlanceTheme { DashboardDeviceCard(card, 42, 250, true, statuses, statuses) }
            }.remoteViews
            instrumentation.runOnMainSync {
                fun pixels(drawable: android.graphics.drawable.Drawable): IntArray {
                    val bitmap = android.graphics.Bitmap.createBitmap(28, 28, android.graphics.Bitmap.Config.ARGB_8888)
                    drawable.setBounds(0, 0, 28, 28)
                    drawable.draw(android.graphics.Canvas(bitmap))
                    return IntArray(28 * 28).also { bitmap.getPixels(it, 0, 28, 0, 0, 28, 28); bitmap.recycle() }
                }
                val pending = pixels(context.getDrawable(R.drawable.ic_launch_pending)!!)
                val forbidden = listOf(R.drawable.ic_power, R.drawable.ic_timer, R.drawable.ic_launch_play)
                    .map { pixels(context.getDrawable(it)!!) }
                var pendingCount = 0
                fun inspect(view: View) {
                    if (view is android.widget.ImageView && view.drawable != null && view.visibility == View.VISIBLE) {
                        val value = pixels(view.drawable)
                        if (value.contentEquals(pending)) pendingCount++
                        assertFalse("Normal glyph overlaps pending: $domain", forbidden.any { it.contentEquals(value) })
                    }
                    if (view is android.view.ViewGroup) repeat(view.childCount) { inspect(view.getChildAt(it)) }
                }
                inspect(views.apply(context, null))
                assertTrue("Missing pending image for $domain", pendingCount > 0)
            }
        }
    }
}
