package com.danila.hacustomwidgets.dashboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardRc3RecyclingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun control(domain: String) = DashboardControl("$domain.rc3", domain, domain, "on")
    private fun metric(kind: String, value: String) = DashboardMetric(
        "sensor.$kind", kind, value, value, "sensor", kind)
    private fun card(name: String, controls: List<DashboardControl> = emptyList(),
                     metrics: List<DashboardMetric> = emptyList()) = DashboardCard(
        name, name, null, null, DeviceCategory.OTHER, metrics, controls)

    private fun fixtures(): List<DashboardCard> {
        val switch = control("switch")
        return listOf(
            card("light", listOf(control("light"))),
            card("switch", listOf(switch)),
            card("timer", listOf(switch)).copy(autoOffTimer = AutoOffTimerConfig(
                enabled = true, timerEntityId = "timer.rc3", controlEntityId = switch.entityId),
                timerState = DashboardMetric("timer.rc3", "Timer", "paused", "paused", "timer", null,
                    "00:30:00", "00:12:00")),
            card("battery", metrics = listOf(metric("battery", "9"))),
            card("climate", metrics = listOf(metric("temperature", "23"), metric("humidity", "51"))),
            card("attributes", metrics = (1..14).map { metric("attribute$it", "value$it") }),
            card("many controls", (1..7).map { switch.copy(entityId = "switch.$it", label = "Switch $it") }),
            *listOf("automation", "script", "scene").map { domain ->
                card("scenario:$domain", listOf(control(domain))).copy(scenarioRunnable = true)
            }.toTypedArray(),
            card("unavailable", listOf(switch.copy(state = "unavailable"))),
            card("empty"),
        )
    }

    private fun state() = DashboardState(
        DashboardConfig(42, emptyList(), emptyMap(), emptyList(), emptyMap(), emptyMap(), true, true),
        emptyList(), emptyList(), emptyList(), MAIN_TAB_ID, emptySet(), emptySet(), emptyMap(),
        1, false, 0, null)

    // Binder round-trip is intentional: test exactly the serialized actions the host receives.
    private fun wire(views: RemoteViews): RemoteViews {
        val parcel = Parcel.obtain()
        return try {
            views.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private fun snapshot(view: View): String = buildString {
        append(view.javaClass.name).append(':').append(view.visibility)
        append(':').append(view.isClickable).append(':').append(view.contentDescription)
        if (view is TextView) append(':').append(view.text).append(':').append(view.currentTextColor)
        if (view is ImageView) {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            view.drawable?.let { drawable ->
                val old = android.graphics.Rect(drawable.bounds)
                drawable.setBounds(0, 0, 32, 32)
                drawable.draw(Canvas(bitmap))
                drawable.bounds = old
            }
            val pixels = IntArray(32 * 32)
            bitmap.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            append(':').append(pixels.contentHashCode())
            bitmap.recycle()
        }
        if (view is ViewGroup) repeat(view.childCount) { append('[').append(snapshot(view.getChildAt(it))).append(']') }
    }

    private fun clickCount(view: View): Int = (if (view.hasOnClickListeners()) 1 else 0) +
        if (view is ViewGroup) (0 until view.childCount).sumOf { clickCount(view.getChildAt(it)) } else 0

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun fallbackRowsMatchFreshBindingAfterRepeatedApplyAndReapply() = runBlocking {
        val composer = GlanceRemoteViews()
        val rows = fixtures().map { card ->
            wire(composer.compose(context, DpSize(230.dp, 700.dp)) {
                GlanceTheme { DashboardDeviceCard(card, 42, 230, true, emptyMap(), emptyMap()) }
            }.remoteViews)
        }
        instrumentation.runOnMainSync {
            // A host recycles only matching layout IDs; different view types get a fresh apply.
            val recycledByLayout = mutableMapOf<Int, View>()
            repeat(10) { pass ->
                val sequence = if (pass % 2 == 0) rows else rows.reversed()
                sequence.forEach { views ->
                    val fresh = views.apply(context, null)
                    val card = fixtures()[rows.indexOf(views)]
                    val expectedClicks = when (card.key) {
                        "unavailable" -> 0
                        "timer", "scenario:automation" -> 2
                        else -> card.visibleControls.size
                    }
                    assertEquals("click targets for ${card.key}", expectedClicks, clickCount(fresh))
                    val recycled = recycledByLayout[views.layoutId]
                    if (recycled == null) recycledByLayout[views.layoutId] = fresh
                    else {
                        views.reapply(context, recycled)
                        assertEquals("Glance layout ${views.layoutId}, pass $pass", snapshot(fresh), snapshot(recycled))
                    }
                }
            }
        }
    }

    @Test fun repositoryBuildsOneSharedDeviceCardInTemperatureHumidityBatteryOrder() {
        val isolated = object : android.content.ContextWrapper(context) {
            private val prefix = "rc6-shared-${java.util.UUID.randomUUID()}-"
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences(prefix + name, mode)
        }
        val deviceId = "environment-device"
        val entities = listOf(
            com.danila.hacustomwidgets.data.model.HaEntity("sensor.battery", "81", "Battery", "%", null,
                deviceId = deviceId, deviceClass = "battery"),
            com.danila.hacustomwidgets.data.model.HaEntity("sensor.humidity", "44", "Humidity", "%", null,
                deviceId = deviceId, deviceClass = "humidity"),
            com.danila.hacustomwidgets.data.model.HaEntity("sensor.temperature", "23", "Temperature", "°C", null,
                deviceId = deviceId, deviceClass = "temperature"),
        )
        val catalog = com.danila.hacustomwidgets.data.model.HaCatalog(listOf(
            com.danila.hacustomwidgets.data.model.HaDeviceGroup(
                com.danila.hacustomwidgets.data.model.HaDevice(deviceId, "Environment"), entities,
            ),
        ))
        val widgetId = 906
        val config = DashboardConfig(widgetId, emptyList(), emptyMap(), listOf(deviceId),
            emptyMap(), emptyMap(), true, true)
        val repository = DashboardRepository(isolated)
        repository.saveConfiguration(config, catalog)
        val card = repository.get(widgetId)!!.cards.single()
        assertEquals(deviceId, card.key)
        assertEquals(listOf("sensor.temperature", "sensor.humidity", "sensor.battery"),
            card.metrics.map { it.entityId })
        assertEquals(1, repository.get(widgetId)!!.cards.count { it.key == deviceId })
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun glancePowerTimerAndScenarioPendingUseOnlyReplacementGlyph() = runBlocking {
        val composer = GlanceRemoteViews()
        for (domain in listOf("light", "switch", "automation", "script", "scene", "timer")) {
            val control = DashboardControl(if (domain == "timer") "switch.timer" else "$domain.pending",
                domain, if (domain == "timer") "switch" else domain, "on")
            val card = DashboardCard(if (domain in setOf("automation", "script", "scene")) "scenario:$domain" else domain,
                domain, null, null, DeviceCategory.OTHER, emptyList(), listOf(control),
                autoOffTimer = if (domain == "timer") AutoOffTimerConfig(true, "timer.pending") else null,
                scenarioRunnable = true)
            val statuses = mapOf(control.entityId to DashboardOperationStatus.PENDING,
                "timer.pending" to DashboardOperationStatus.PENDING)
            val views = composer.compose(context, DpSize(250.dp, 500.dp)) {
                GlanceTheme { DashboardDeviceCard(card, 42, 250, true, statuses, statuses) }
            }.remoteViews
            instrumentation.runOnMainSync {
                fun pixels(drawable: android.graphics.drawable.Drawable): IntArray {
                    val bitmap = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888)
                    drawable.setBounds(0, 0, 28, 28)
                    drawable.draw(Canvas(bitmap))
                    return IntArray(28 * 28).also {
                        bitmap.getPixels(it, 0, 28, 0, 0, 28, 28)
                        bitmap.recycle()
                    }
                }
                val pending = pixels(context.getDrawable(com.danila.hacustomwidgets.R.drawable.ic_launch_pending)!!)
                val forbidden = listOf(
                    com.danila.hacustomwidgets.R.drawable.ic_power,
                    com.danila.hacustomwidgets.R.drawable.ic_timer,
                    com.danila.hacustomwidgets.R.drawable.ic_launch_play,
                ).map { pixels(context.getDrawable(it)!!) }
                var pendingCount = 0
                fun inspect(view: View) {
                    if (view is ImageView && view.drawable != null && view.visibility == View.VISIBLE) {
                        val value = pixels(view.drawable)
                        if (value.contentEquals(pending)) pendingCount++
                        assertFalse("Normal glyph overlaps pending: $domain", forbidden.any { it.contentEquals(value) })
                    }
                    if (view is ViewGroup) repeat(view.childCount) { inspect(view.getChildAt(it)) }
                }
                inspect(views.apply(context, null))
                assertTrue("Missing pending image for $domain", pendingCount > 0)
            }
        }
    }
}
