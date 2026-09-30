package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.R
import com.danila.hacustomwidgets.tr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class TimerCardHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun bounds(root: ViewGroup, view: View) = Rect().also {
        view.getDrawingRect(it); root.offsetDescendantRectToMyCoords(view, it)
    }
    private fun card(minutes: Int = 30, state: String = "active", controlState: String = "on") = DashboardCard(
        "device:dryer", "Очень длинное название сушилки Long English dryer name", null, null,
        DeviceCategory.SWITCHES, emptyList(), listOf(DashboardControl("switch.dryer", "Dryer", "switch", controlState)),
        AutoOffTimerConfig(true, "timer.dryer", controlEntityId = "switch.dryer"),
        DashboardMetric("timer.dryer", "Timer", "timer", state, state, null,
            timerDuration = "%02d:%02d:00".format(minutes / 60, minutes % 60), timerRemaining = "00:20:00"),
    )
    private suspend fun render(themed: Context, width: Int, card: DashboardCard,
        operations: Map<String, DashboardOperationStatus> = emptyMap()): ViewGroup {
        val remote = GlanceRemoteViews().compose(themed, DpSize(width.dp, 180.dp)) {
            GlanceTheme { DashboardDeviceCard(card, 301, width, true, operations, emptyMap()) }
        }.remoteViews
        lateinit var root: ViewGroup
        instrumentation.runOnMainSync {
            root = remote.apply(themed, null) as ViewGroup
            val pixels = (width * themed.resources.displayMetrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, pixels, root.measuredHeight)
        }
        return root
    }
    private fun timer(root: ViewGroup) = descendants(root).filterIsInstance<ImageView>().single {
        it.contentDescription?.toString() == tr("Timer", "Таймер") }
    private fun power(root: ViewGroup) = descendants(root).filterIsInstance<ImageView>().single {
        it.contentDescription?.toString() == tr("Turn off", "Выключить") }

    @Test fun timerPrecedesFixedRightPowerAcrossPresetsLocalesWidthsAndFonts() = runBlocking {
        val original = Locale.getDefault()
        try {
            for (language in listOf("ru", "en")) {
                Locale.setDefault(Locale(language))
                for (scale in listOf(1f, 1.5f, 2f)) for (width in listOf(180, 230, 320)) {
                    val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = scale })
                    var fixed: Rect? = null
                    for (minutes in listOf(30, 60, 90, 120)) {
                        val root = render(themed, width, card(minutes))
                        instrumentation.runOnMainSync {
                            val timerRect = bounds(root, timer(root)); val powerRect = bounds(root, power(root))
                            val label = descendants(root).filterIsInstance<TextView>().single {
                                it.text.toString().trim() == tr("$minutes min", "$minutes мин") }
                            val labelRect = bounds(root, label)
                            assertTrue(timerRect.right <= labelRect.left)
                            assertTrue("Duration cannot overlap Power", labelRect.right <= powerRect.left)
                            assertTrue(timerRect.left >= 0 && powerRect.right <= root.width)
                            val density = themed.resources.displayMetrics.density
                            // Compact card padding 9dp plus 10dp target-to-glyph inset.
                            assertTrue(kotlin.math.abs(root.width - powerRect.right - 19 * density) <= 2)
                            fixed?.let { assertEquals(it.left, powerRect.left); assertEquals(it.right, powerRect.right) }; fixed = powerRect
                            assertEquals(0, label.layout.getEllipsisCount(0))
                            assertEquals(2, descendants(root).count { it.hasOnClickListeners() })
                            assertEquals(themed.getColor(R.color.widget_accent), label.currentTextColor)
                        }
                    }
                }
            }
        } finally { Locale.setDefault(original) }
    }

    @Test fun timerActiveYellowMatchesPowerAndInactivePendingColorsStayUnchanged() = runBlocking {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            })
            for (state in listOf("active", "idle", "paused", "unknown", "unavailable")) {
                for (operation in listOf(null) + DashboardOperationStatus.entries) {
                    val operations = operation?.let { mapOf("timer.dryer" to it, "switch.dryer" to it) } ?: emptyMap()
                    val root = render(themed, 230, card(state = state), operations)
                    instrumentation.runOnMainSync {
                        val expected = if (state == "active" && operation?.isActive != true)
                            PorterDuffColorFilter(themed.getColor(R.color.widget_light_on), PorterDuff.Mode.SRC_ATOP) else null
                        assertEquals(expected, timer(root).colorFilter)
                        if (expected != null) assertEquals(power(root).colorFilter, timer(root).colorFilter)
                        assertEquals(2, descendants(root).count { it.hasOnClickListeners() })
                        val label = descendants(root).filterIsInstance<TextView>().single { it.text.toString().trim() == tr("30 min", "30 мин") }
                        assertEquals(themed.getColor(R.color.widget_accent), label.currentTextColor)
                        assertEquals(28 * themed.resources.displayMetrics.density, timer(root).width.toFloat(), 1f)
                    }
                }
            }
        }
    }

    @Test fun unavailableAndUnknownControlsPreserveClickAndTintSemantics() = runBlocking {
        for (state in listOf("unknown", "unavailable")) {
            val root = render(context, 180, card(controlState = state))
            instrumentation.runOnMainSync {
                if (state == "unavailable") {
                    assertFalse(descendants(root).any { it.contentDescription?.toString() == tr("Timer", "Таймер") })
                    assertEquals(0, descendants(root).count { it.hasOnClickListeners() })
                } else {
                    assertNull(timer(root).colorFilter)
                    assertEquals(1, descendants(root).count { it.hasOnClickListeners() })
                }
            }
        }
    }
}
