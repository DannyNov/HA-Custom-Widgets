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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrightnessHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun isolated(): Context = object : ContextWrapper(context) {
        private val prefix = "brightness-${UUID.randomUUID()}-"
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    }
    @Test fun confirmedHistoryPersistsAcrossOffRestartAndMultipleWidgets() {
        val ctx = isolated()
        val repo = DashboardRepository(ctx)
        val entity = HaEntity("light.a", "off", "Lamp", null, "2026-09-29T00:00:00Z",
            brightness = LightBrightness(null, true, listOf("brightness")))
        for (id in listOf(301, 302)) repo.saveConfiguration(
            DashboardConfig(id, emptyList(), emptyMap(), listOf("device"), emptyMap(), emptyMap(), false, true),
            HaCatalog(listOf(HaDeviceGroup(HaDevice("device", "Lamp"), listOf(entity)))),
        )
        assertNull(repo.get(301)!!.cards.single().controls.single().brightnessPercent)
        fun update(e: HaEntity) = listOf(301, 302).forEach { repo.updateEntityStates(it, listOf(e), DashboardStateSource.EVENT) }
        update(entity.copy(state = "on", lastUpdated = "2026-09-29T00:00:01Z", brightness = entity.brightness.copy(value = 166)))
        val revision = repo.currentStateRevision(301)
        update(entity.copy(state = "on", lastUpdated = "2026-09-29T00:00:02Z", brightness = entity.brightness.copy(value = 179)))
        assertTrue(repo.currentStateRevision(301) > revision)
        update(entity.copy(lastUpdated = "2026-09-29T00:00:03Z"))
        val restored = DashboardRepository(ctx)
        for (id in listOf(301, 302)) assertEquals(70, restored.get(id)!!.cards.single().controls.single().brightnessPercent)
        update(entity.copy(state = "on", lastUpdated = "2026-09-29T00:00:01Z", brightness = entity.brightness.copy(value = 13)))
        assertEquals(70, repo.get(301)!!.cards.single().controls.single().brightnessPercent)
    }

    private fun texts(view: View): List<TextView> = listOfNotNull(view as? TextView) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()

    private fun clicks(view: View): Int = (if (view.hasOnClickListeners()) 1 else 0) +
        if (view is ViewGroup) (0 until view.childCount).sumOf { clicks(view.getChildAt(it)) } else 0

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun capabilityAndAvailabilityControlActualHostClickTargets() = runBlocking {
        val composer = GlanceRemoteViews()
        val base = DashboardControl("light.a", "Lamp", "light", "on", true, 65)
        for ((control, expected) in listOf(base to 4, base.copy(state = "off") to 4,
            base.copy(brightnessPercent = null) to 2, base.copy(state = "unavailable") to 0,
            base.copy(state = "unknown") to 0, base.copy(brightnessCapable = false) to 1)) {
            val card = DashboardCard("device", "Lamp", null, null, DeviceCategory.LIGHTING, emptyList(), listOf(control))
            val remote = composer.compose(context, DpSize(320.dp, 110.dp)) {
                GlanceTheme { DashboardDeviceCard(card, 301, 320, true, emptyMap(), emptyMap()) }
            }.remoteViews
            instrumentation.runOnMainSync {
                val view = remote.apply(context, null)
                assertEquals("Unexpected targets for $control", expected, clicks(view))
                assertEquals(control.brightnessCapable, texts(view).any { it.text.toString().endsWith("%") })
            }
        }
    }

    @Test fun sliderActivityRoutesToEntityAndWaitsSafelyWithoutKnownBrightness() {
        val component = android.content.ComponentName(context, BrightnessActivity::class.java)
        assertFalse(context.packageManager.getActivityInfo(component, 0).exported)
        val intent = android.content.Intent().setComponent(component)
            .putExtra("brightness_entity", "light.not_yet_known").putExtra("brightness_widget", 301)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        androidx.test.core.app.ActivityScenario.launch<BrightnessActivity>(intent).use { scenario ->
            scenario.onActivity {
                assertEquals("light.not_yet_known", it.intent.getStringExtra("brightness_entity"))
                assertFalse(it.isFinishing)
            }
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun longNamesFixedPercentGeometryAndNarrowFallback() = runBlocking {
        val composer = GlanceRemoteViews()
        for (fontScale in listOf(1f, 1.5f, 2f)) {
        val themed = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
        for (width in listOf(180, 230, 320)) {
            var center: Int? = null
            for (percent in listOf(5, 65, 100)) {
                val control = DashboardControl("light.a", "Очень длинное название лампы Long English lamp name", "light", "on", true, percent)
                val card = DashboardCard("device", control.label, null, null, DeviceCategory.LIGHTING, emptyList(), listOf(control))
                val remote = composer.compose(themed, DpSize(width.dp, 110.dp)) {
                    GlanceTheme { DashboardDeviceCard(card, 301, width, true, emptyMap(), emptyMap()) }
                }.remoteViews
                instrumentation.runOnMainSync {
                    val view = remote.apply(themed, null)
                    val pixels = (width * context.resources.displayMetrics.density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0, 0, pixels, view.measuredHeight)
                    val percentView = texts(view).single { it.text.toString() == "$percent%" }
                    val rect = android.graphics.Rect(); percentView.getDrawingRect(rect)
                    (view as ViewGroup).offsetDescendantRectToMyCoords(percentView, rect)
                    assertTrue(rect.left >= 0 && rect.right <= pixels)
                    center?.let { assertEquals(it, rect.centerX()) }
                    center = rect.centerX()
                    assertEquals(width >= 316 && fontScale == 1f, texts(view).any { it.text.toString() == "+" })
                    assertEquals("Percent must not be ellipsized at fontScale=$fontScale", 0, percentView.layout.getEllipsisCount(0))
                }
            }
        }
        }
    }
}
