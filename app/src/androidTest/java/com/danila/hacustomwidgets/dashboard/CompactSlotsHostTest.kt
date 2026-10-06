package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.RemoteViews
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
class CompactSlotsHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun flatten(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { flatten(v.getChildAt(it)) } else emptyList()
    private fun x(v: View): Int = v.left + ((v.parent as? View)?.let(::x) ?: 0)
    private fun target(v: View): View = if (v.hasOnClickListeners()) v else target(v.parent as View)

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun fourCombinationsHaveNoReservedSlotsAtNarrowWidthAndLargeFont() = runBlocking {
        val c = object : ContextWrapper(context) {
            val prefix = "compact-${UUID.randomUUID()}-"
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        val catalog = HaCatalog(listOf(HaDeviceGroup(HaDevice("a", "Device", areaId = "room"),
            listOf(HaEntity("sensor.battery", "6", "Battery", "%", "2026-10-06T00:00:00Z", deviceClass = "battery")))), listOf(HaArea("room", "Room")))
        val repo = DashboardRepository(c)
        val config = DashboardConfig(970, listOf("area:room"), emptyMap(), emptyList(), emptyMap(), emptyMap(), false, true)
        val headerHeight = mutableMapOf<Pair<Int,Float>,Int>()
        for (width in listOf(180,230,320)) for (font in listOf(1f,1.5f,2f))
            for (main in listOf(false,true)) for (maintenance in listOf(false,true)) for (updated in listOf(false,true)) {
                val ctx = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { fontScale = font })
                repo.saveConfiguration(config.copy(showFavorites = main, showMaintenance = maintenance, showLastUpdated = updated), catalog)
                val state = repo.get(970)!!
                val remote = DashboardCollectionsRenderer.dashboard(ctx,state,970,DpSize(width.dp,300.dp))
                instrumentation.runOnMainSync {
                    val v=android.appwidget.AppWidgetHostView(ctx)
                    val provider=android.appwidget.AppWidgetManager.getInstance(ctx).installedProviders.single { it.provider.className==DashboardWidgetReceiver::class.java.name }
                    v.setAppWidget(970,provider); v.setPadding(0,0,0,0); v.updateAppWidget(remote)
                    val density=ctx.resources.displayMetrics.density
                    v.measure(View.MeasureSpec.makeMeasureSpec((width*density).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((300*density).toInt(),View.MeasureSpec.EXACTLY))
                    v.layout(0,0,v.measuredWidth,v.measuredHeight)
                    val texts=flatten(v).filterIsInstance<TextView>()
                    val stars=texts.filter { it.text.toString()=="★" }
                    assertEquals(if(main) 1 else 0,stars.size)
                    val left=target(texts.single { it.text.toString()=="‹" })
                    val right=target(texts.single { it.text.toString()=="›" })
                    assertEquals((48*density).toInt(),left.width)
                    assertEquals((48*density).toInt(),left.height)
                    val row=left.parent as View
                    assertEquals("Left arrow fills hidden star slot",x(row)+(if(main) (48*density).toInt() else 0),x(left))
                    assertEquals(x(row)+row.width,x(right)+right.width)
                    // API31 Glance rows include zero-size ViewStub scaffolding.
                    val children = row as ViewGroup
                    assertEquals(if(main) 4 else 3,(0 until children.childCount).count {
                        children.getChildAt(it).visibility == View.VISIBLE && children.getChildAt(it).width > 0
                    })
                    val icons=flatten(v).filter { it.contentDescription?.toString()==com.danila.hacustomwidgets.tr("Maintenance","Обслуживание") }
                    assertEquals(if(maintenance) 1 else 0,icons.size)
                    val refresh=target(texts.single { it.text.toString()=="↻" })
                    val settings=target(texts.single { it.text.toString()=="⚙" })
                    assertEquals("No header gap",if(maintenance) (36*density).toInt() else 0,x(settings)-x(refresh)-refresh.width)
                    val header=refresh.parent as View
                    val chrome=v.findViewById<View>(com.danila.hacustomwidgets.R.id.dashboard_chrome)
                    assertEquals("Only accepted header, spacer and tabs height",header.height+(53*density).toInt(),chrome.height)
                    headerHeight[width to font]?.let { assertEquals(it.toInt(),header.height) }
                    headerHeight[width to font]=header.height
                    assertEquals(if(updated && width>=240) 1 else 0,texts.count { it.text.toString().contains(":") })
                }
            }
    }

    @Test fun unconfiguredChromeRemainsCompactAndClickable() = runBlocking {
        for (width in listOf(180,320)) {
            val remote = composeGlanceDashboard(context,971,DpSize(width.dp,300.dp)) {
                DashboardChrome(context,null,971,DpSize(width.dp,300.dp))
            }
            instrumentation.runOnMainSync {
                val v=remote.apply(context,null); val density=context.resources.displayMetrics.density
                v.measure(View.MeasureSpec.makeMeasureSpec((width*density).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                v.layout(0,0,v.measuredWidth,v.measuredHeight)
                val texts=flatten(v).filterIsInstance<TextView>()
                for (label in listOf("↻","⚙")) assertTrue(target(texts.single { it.text.toString()==label }).hasOnClickListeners())
                assertFalse(texts.any { it.text.toString() in listOf("★","‹","›") })
                assertFalse(flatten(v).any { it.contentDescription?.toString()==com.danila.hacustomwidgets.tr("Maintenance","Обслуживание") })
                assertTrue(texts.any { it.text.toString()==com.danila.hacustomwidgets.tr("Configure HA Dashboard","Настройте HA Dashboard") })
            }
        }
    }
}
