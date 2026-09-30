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
    private fun bounds(root: ViewGroup, child: View): android.graphics.Rect = android.graphics.Rect().also {
        child.getDrawingRect(it); root.offsetDescendantRectToMyCoords(child, it)
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun powerGlyphUsesActiveYellowForAllPowerDomainsInBothThemes() = runBlocking {
        for (night in listOf(android.content.res.Configuration.UI_MODE_NIGHT_NO, android.content.res.Configuration.UI_MODE_NIGHT_YES)) {
            val themed = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            })
            for (domain in listOf("light", "switch")) for (state in listOf("on", "off", "unknown", "unavailable")) {
                val control = DashboardControl("$domain.a", "Power", domain, state, domain == "light", 65)
                val card = DashboardCard("device", "Power", null, null, DeviceCategory.LIGHTING, emptyList(), listOf(control))
                val remote = GlanceRemoteViews().compose(themed, DpSize(320.dp, 110.dp)) {
                    GlanceTheme { DashboardDeviceCard(card, 301, 320, true, emptyMap(), emptyMap()) }
                }.remoteViews
                instrumentation.runOnMainSync {
                    val view = remote.apply(themed, null)
                    val glyphs = images(view).filter { it.contentDescription?.toString() == com.danila.hacustomwidgets.tr(
                        if (state == "on") "Turn off" else "Turn on", if (state == "on") "Выключить" else "Включить") }
                    if (domain == "switch" && state in setOf("unknown", "unavailable")) {
                        assertTrue(glyphs.isEmpty()); assertEquals(0, clicks(view)); return@runOnMainSync
                    }
                    val glyph = glyphs.single()
                    val expected = themed.getColor(if (state == "on") com.danila.hacustomwidgets.R.color.widget_light_on
                        else com.danila.hacustomwidgets.R.color.widget_primary)
                    assertEquals(android.graphics.PorterDuffColorFilter(expected, android.graphics.PorterDuff.Mode.SRC_ATOP), glyph.colorFilter)
                    assertEquals(if (state in setOf("unknown", "unavailable")) 0 else if (domain == "light") 4 else 1, clicks(view))
                }
            }
        }
    }

    @Test fun floatingWindowIsTransparentInsetAndSurvivesRecreation() {
        val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, BrightnessActivity::class.java), 0)
        assertTrue(info.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(info.flags and android.content.pm.ActivityInfo.FLAG_NO_HISTORY != 0)
        assertTrue(info.flags and android.content.pm.ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS != 0)
        assertEquals("com.danila.hacustomwidgets.brightness", info.taskAffinity)
        val intent = android.content.Intent(context, BrightnessActivity::class.java)
            .putExtra("brightness_entity", "light.unknown").putExtra("brightness_widget", 301)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        androidx.test.core.app.ActivityScenario.launch<BrightnessActivity>(intent).use { scenario ->
            fun verify() = scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                val metrics = activity.windowManager.currentWindowMetrics
                val inset = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                val expected = minOf(metrics.bounds.width() - inset.left - inset.right - (48 * density).toInt(), (420 * density).toInt())
                assertTrue(kotlin.math.abs(expected - activity.window.attributes.width) <= 1)
                assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, activity.window.attributes.height)
                val background = activity.window.decorView.background as android.graphics.drawable.ColorDrawable
                assertEquals(android.graphics.Color.TRANSPARENT, background.color)
                assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
                assertFalse(activity.isFinishing)
            }
            verify(); scenario.recreate(); verify()
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(250)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            java.io.File(context.getExternalFilesDir(null), "rc3-floating-window.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }

    @Test fun helperHomeBackAndReopeningDoNotRetainRecentTask() {
        val intent = android.content.Intent(context, BrightnessActivity::class.java)
            .putExtra("brightness_entity", "light.unknown").putExtra("brightness_widget", 301)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        for (exit in listOf("close", "back", "home")) {
            var activity: BrightnessActivity? = null
            val scenario = androidx.test.core.app.ActivityScenario.launch<BrightnessActivity>(intent)
            try {
                scenario.onActivity { activity = it; assertFalse(it.isFinishing) }
                when (exit) {
                    "close" -> scenario.onActivity { it.finish() }
                    "back" -> scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    else -> context.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (activity?.isDestroyed != true && android.os.SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(50)
                }
                assertTrue("Helper must finish after $exit", activity?.isDestroyed == true)
                val manager = context.getSystemService(android.app.ActivityManager::class.java)
                assertFalse("No helper task retained after $exit", manager.appTasks.any {
                    it.taskInfo.baseIntent.component?.className == BrightnessActivity::class.java.name
                })
            } finally { scenario.close() }
        }
    }

    private fun images(view: View): List<android.widget.ImageView> = listOfNotNull(view as? android.widget.ImageView) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { images(view.getChildAt(it)) } else emptyList()

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun capsuleHasTransparentCenterMatchingOutlineAndNoExtraClickTarget() = runBlocking {
        for (night in listOf(android.content.res.Configuration.UI_MODE_NIGHT_NO, android.content.res.Configuration.UI_MODE_NIGHT_YES)) {
        val themed = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
        for (state in listOf("on", "off", "unknown", "unavailable")) {
            for (width in listOf(180, 320)) {
                for (percent in listOf(5, 65, 100)) {
                    val control = DashboardControl("light.a", "Lamp", "light", state, true, percent)
                    val remote = GlanceRemoteViews().compose(themed, DpSize(width.dp, 110.dp)) {
                        GlanceTheme { BrightnessControls(themed, control, 301, width) }
                    }.remoteViews
                    instrumentation.runOnMainSync {
                        val view = remote.apply(themed, null)
                        val density = context.resources.displayMetrics.density
                        val pixels = (width * density).toInt()
                        view.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        view.layout(0, 0, pixels, view.measuredHeight)
                        val outline = images(view).single { it.drawable is android.graphics.drawable.GradientDrawable }
                        val expectedWidth = if (width == 320) 144 else 48
                        assertEquals((expectedWidth * density).toInt(), outline.width)
                        assertEquals((48 * density).toInt(), outline.height)
                        val bitmap = android.graphics.Bitmap.createBitmap(outline.width, outline.height,
                            android.graphics.Bitmap.Config.ARGB_8888)
                        outline.draw(android.graphics.Canvas(bitmap))
                        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(outline.width / 2, outline.height / 2)))
                        assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(0, 0)))
                        val edge = (0 until (3 * density).toInt()).map { bitmap.getPixel(outline.width / 2, it) }
                            .maxBy { android.graphics.Color.alpha(it) }
                        val textColor = texts(view).single { it.text.toString() == "$percent%" }.currentTextColor
                        // Compare the actual applied tint exactly. Thin strokes are antialiased;
                        // unpremultiplying a partially covered pixel can round RGB by one unit.
                        val tint = textColor
                        assertEquals(android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_ATOP),
                            outline.colorFilter)
                        assertEquals(themed.getColor(if (state == "on") com.danila.hacustomwidgets.R.color.widget_light_on
                            else com.danila.hacustomwidgets.R.color.widget_secondary), tint)
                        assertTrue(android.graphics.Color.alpha(edge) >= 200)
                        for (shift in listOf(0, 8, 16)) {
                            assertTrue(kotlin.math.abs(((edge ushr shift) and 255) - ((tint ushr shift) and 255)) <= 2)
                        }
                        assertEquals(if (state in setOf("on", "off")) (if (width == 320) 3 else 1) else 0, clicks(view))
                        bitmap.recycle()
                    }
                }
            }
        }
        }
    }

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

    private fun descriptions(view: View): List<String> = listOfNotNull(view.contentDescription?.toString()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descriptions(view.getChildAt(it)) } else emptyList()

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun timerAndBrightnessKeepOneSeparatePowerButton() = runBlocking {
        val light = DashboardControl("light.a", "Lamp", "light", "on", true, 65)
        val card = DashboardCard("device", "Lamp", null, null, DeviceCategory.LIGHTING, emptyList(), listOf(light),
            autoOffTimer = AutoOffTimerConfig(enabled = true, timerEntityId = "timer.a", controlEntityId = light.entityId),
            timerState = DashboardMetric("timer.a", "Timer", "idle", "idle", "timer", null, "00:30:00"))
        val remote = GlanceRemoteViews().compose(context, DpSize(320.dp, 180.dp)) {
            GlanceTheme { DashboardDeviceCard(card, 301, 320, true, emptyMap(), emptyMap()) }
        }.remoteViews
        instrumentation.runOnMainSync {
            val view = remote.apply(context, null)
            assertEquals(1, descriptions(view).count { it == com.danila.hacustomwidgets.tr("Turn off", "Выключить") })
            assertEquals(1, texts(view).count { it.text.toString() == "65%" })
            assertTrue(descriptions(view).contains(com.danila.hacustomwidgets.tr("Timer", "Таймер")))
        }
    }

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
                    val outline = images(view).single { it.drawable is android.graphics.drawable.GradientDrawable &&
                        kotlin.math.abs(it.height - 48 * context.resources.displayMetrics.density) <= 1 }
                    val power = images(view).single { it.contentDescription?.toString() == com.danila.hacustomwidgets.tr("Turn off", "Выключить") }
                    val outlineRect = bounds(view as ViewGroup, outline)
                    // 28dp glyph and 40dp circle share a center: circle begins 6dp before glyph.
                    val circleLeft = bounds(view, power).left - (6 * context.resources.displayMetrics.density).toInt()
                    assertTrue("Visible capsule/power gap must be 12dp", kotlin.math.abs(
                        circleLeft - outlineRect.right - 12 * context.resources.displayMetrics.density) <= 2)
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
