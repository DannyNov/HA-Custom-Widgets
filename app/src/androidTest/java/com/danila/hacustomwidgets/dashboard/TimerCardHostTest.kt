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
    private fun card(minutes: Int = 30, state: String = "active", controlState: String = "on", remaining: String = "00:20:00") = DashboardCard(
        "device:dryer", "Очень длинное название сушилки Long English dryer name", null, null,
        DeviceCategory.SWITCHES, emptyList(), listOf(DashboardControl("switch.dryer", "Dryer", "switch", controlState)),
        AutoOffTimerConfig(true, "timer.dryer", controlEntityId = "switch.dryer"),
        DashboardMetric("timer.dryer", "Timer", "timer", state, state, null,
            timerDuration = "%02d:%02d:00".format(minutes / 60, minutes % 60), timerRemaining = remaining),
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
                    var fixedTimer: Rect? = null
                    var fixedInterval: Rect? = null
                    var fixedTimerX: Int? = null
                    for (minutes in listOf(30, 60, 90, 120)) for (countdown in listOf("00:30:00", "01:00:00", "01:30:00", "02:00:00")) {
                        val root = render(themed, width, card(minutes, remaining = countdown))
                        instrumentation.runOnMainSync {
                            val timerRect = bounds(root, timer(root)); val powerRect = bounds(root, power(root))
                            fixedTimerX?.let { assertEquals("Presets keep Timer anchor", it, timerRect.left) }
                            fixedTimerX = timerRect.left
                            val label = descendants(root).filterIsInstance<TextView>().single {
                                it.text.toString().trim() == tr("$minutes min", "$minutes мин") }
                            val labelRect = bounds(root, label)
                            assertTrue(timerRect.right <= labelRect.left)
                            assertTrue("Interval stays at Timer center", kotlin.math.abs(timerRect.centerY() - labelRect.centerY()) <= 2)
                            val remaining = descendants(root).filterIsInstance<TextView>().single {
                                it.text.toString().startsWith(tr("Remaining ", "Осталось ")) }
                            val remainingRect = bounds(root, remaining)
                            assertEquals("Interval starts exactly above remaining", labelRect.left, remainingRect.left)
                            assertEquals(0f, remaining.layout.getLineLeft(0), 0.01f)
                            fixedTimer?.let { assertEquals(it, timerRect) }; fixedTimer = timerRect
                            fixedInterval?.let { assertEquals(it.left, labelRect.left); assertEquals(it.right, labelRect.right) }; fixedInterval = labelRect
                            assertTrue("Remaining belongs below interval", remainingRect.top >= labelRect.bottom)
                            assertTrue("Remaining is below Power", remainingRect.top >= powerRect.bottom)
                            assertTrue(remainingRect.right <= root.width)
                            assertTrue(remaining.layout.height <= remaining.height)
                            assertEquals(remaining.text.length, remaining.layout.getLineEnd(remaining.layout.lineCount - 1))
                            if (width == 320 && scale == 1f && countdown == "01:30:00" && minutes >= 90) {
                                assertEquals(tr("Remaining 1 h 30 min", "Осталось 1 ч 30 мин"), remaining.text.toString())
                                assertEquals("Full normal Honor caption fits", 1, remaining.layout.lineCount)
                            }
                            assertTrue("Duration cannot overlap Power", labelRect.right <= powerRect.left)
                            assertTrue(timerRect.left >= 0 && powerRect.right <= root.width)
                            val density = themed.resources.displayMetrics.density
                            // Compact card padding 9dp plus 10dp target-to-glyph inset.
                            assertTrue(kotlin.math.abs(root.width - powerRect.right - 19 * density) <= 2)
                            fixed?.let { assertEquals(it.left, powerRect.left); assertEquals(it.right, powerRect.right) }; fixed = powerRect
                            for (line in 0 until label.layout.lineCount) assertEquals(0, label.layout.getEllipsisCount(line))
                            for (line in 0 until remaining.layout.lineCount) assertEquals(0, remaining.layout.getEllipsisCount(line))
                            val timerTarget = generateSequence(timer(root) as View?) { it.parent as? View }
                                .first { it.hasOnClickListeners() }
                            assertTrue(timerTarget.width >= 48 * density - 1)
                            assertTrue(timerTarget.height >= 48 * density - 1)
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

    @Test fun remainingTicksAndPausedLongTextKeepTheSameBlockAndPower() = runBlocking {
        val original = Locale.getDefault()
        try {
            for (language in listOf("ru", "en")) for (width in listOf(180, 320, 360)) for (scale in listOf(1f, 2f)) {
                Locale.setDefault(Locale(language))
                val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = scale })
                var fixedTimer: Rect? = null
                var fixedPower: Rect? = null
                var fixedTextX: Int? = null
                for (state in listOf("active", "paused")) for (remaining in listOf("00:30:00", "01:00:00", "01:30:00", "02:00:00", "00:01:00", "01:59:00")) {
                    val root = render(themed, width, card(120, state = state, remaining = remaining))
                    instrumentation.runOnMainSync {
                        val timerRect = bounds(root, timer(root)); val powerRect = bounds(root, power(root))
                        fixedTimer?.let { assertEquals(it, timerRect) }; fixedTimer = timerRect
                        fixedPower?.let { assertEquals(it, powerRect) }; fixedPower = powerRect
                        val label = descendants(root).filterIsInstance<TextView>().single {
                            it.text.toString() == tr("120 min", "120 мин") }
                        val text = descendants(root).filterIsInstance<TextView>().single {
                            it.text.toString().startsWith(tr("Remaining ", "Осталось ")) ||
                                it.text.toString().startsWith(tr("Paused · ", "Пауза · ")) }
                        val r = bounds(root, text)
                        assertEquals("Shared fixed left edge", bounds(root, label).left, r.left)
                        fixedTextX?.let { assertEquals("Countdown cannot move left edge", it, r.left) }
                        fixedTextX = r.left
                        assertEquals(0f, text.layout.getLineLeft(0), 0.01f)
                        assertTrue(r.top >= bounds(root, label).bottom)
                        assertTrue(r.top >= powerRect.bottom && r.right <= root.width && r.bottom <= root.height)
                        if (width >= 320 && scale == 1f) {
                            assertEquals("Normal caption fits one line", 1, text.layout.lineCount)
                            assertEquals(text.text.length, text.layout.getLineEnd(0))
                        }
                        for (line in 0 until text.layout.lineCount) assertEquals(0, text.layout.getEllipsisCount(line))
                        assertTrue("Remaining clipped: $language/$width/$scale layout=${text.layout.height} view=${text.height}", text.layout.height <= text.height)
                        assertTrue("Interval clipped: $language/$width/$scale layout=${label.layout.height} view=${label.height}", label.layout.height <= label.height)
                    }
                }
            }
        } finally { Locale.setDefault(original) }
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
