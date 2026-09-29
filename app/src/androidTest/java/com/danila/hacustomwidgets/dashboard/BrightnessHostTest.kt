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

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun longNamesFixedPercentGeometryAndNarrowFallback() = runBlocking {
        val composer = GlanceRemoteViews()
        for (width in listOf(180, 230, 320)) {
            var center: Int? = null
            for (percent in listOf(5, 65, 100)) {
                val control = DashboardControl("light.a", "Очень длинное название лампы Long English lamp name", "light", "on", true, percent)
                val card = DashboardCard("device", control.label, null, null, DeviceCategory.LIGHTING, emptyList(), listOf(control))
                val remote = composer.compose(context, DpSize(width.dp, 110.dp)) {
                    GlanceTheme { DashboardDeviceCard(card, 301, width, true, emptyMap(), emptyMap()) }
                }.remoteViews
                instrumentation.runOnMainSync {
                    val view = remote.apply(context, null)
                    val pixels = (width * context.resources.displayMetrics.density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0, 0, pixels, view.measuredHeight)
                    val percentView = texts(view).single { it.text.toString() == "$percent%" }
                    val rect = android.graphics.Rect(); percentView.getDrawingRect(rect)
                    (view as ViewGroup).offsetDescendantRectToMyCoords(percentView, rect)
                    assertTrue(rect.left >= 0 && rect.right <= pixels)
                    center?.let { assertEquals(it, rect.centerX()) }
                    center = rect.centerX()
                    assertEquals(width >= 316, texts(view).any { it.text.toString() == "+" })
                }
            }
        }
    }
}
