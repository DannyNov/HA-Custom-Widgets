package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.data.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LightColorHostTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun light(modes: List<String>,mode:String="color_temp")=LightBrightness.parse(JSONObject()
        .put("supported_color_modes",org.json.JSONArray(modes)).put("color_mode",mode).put("brightness",166)
        .put("min_color_temp_kelvin",2700).put("max_color_temp_kelvin",6500).put("color_temp_kelvin",4000).put("hs_color",org.json.JSONArray(listOf(120,75))))
    private fun clicks(view:View):Int=(if(view.hasOnClickListeners())1 else 0)+
        if(view is ViewGroup)(0 until view.childCount).sumOf{clicks(view.getChildAt(it))}else 0
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun allCapabilityContoursKeepGeometryAndTargetsInWidthFontMatrix()=runBlocking {
        for(modes in listOf(listOf("brightness"),listOf("color_temp"),listOf("hs"),listOf("color_temp","rgbww")))
            for(width in listOf(180,230,320))for(font in listOf(1f,1.5f,2f))for(state in listOf("on","off")) {
                val ctx=context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply{fontScale=font})
                val control=DashboardControl("light.a","Очень длинное имя лампы / Very long lamp name","light",state,true,65,light=light(modes))
                val views=GlanceRemoteViews().compose(ctx,DpSize(width.dp,110.dp)) {
                    GlanceTheme{BrightnessControls(ctx,control,301,width)}
                }.remoteViews
                instrumentation.runOnMainSync {
                    val view=views.apply(ctx,null);val density=ctx.resources.displayMetrics.density
                    view.measure(View.MeasureSpec.makeMeasureSpec((width*density).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    view.layout(0,0,view.measuredWidth,view.measuredHeight)
                    assertEquals(if(BrightnessLayoutPolicy.showSteps(width,font))3 else 1,clicks(view))
                    assertTrue(view.measuredHeight>=48*density)
                    if(state=="on") {
                        val bitmap=android.graphics.Bitmap.createBitmap(view.measuredWidth,view.measuredHeight,android.graphics.Bitmap.Config.ARGB_8888)
                        view.draw(android.graphics.Canvas(bitmap))
                        val bytes=java.io.ByteArrayOutputStream().also{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
                        val encoded=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
                        val name="v070-capsule-${modes.joinToString("-")}-$width-$font.png"
                        instrumentation.uiAutomation.executeShellCommand("echo $encoded | base64 -d > /data/local/tmp/$name").use{java.io.FileInputStream(it.fileDescriptor).readBytes()}
                        bitmap.recycle()
                    }
                }
                if(modes!=listOf("brightness")) {
                    val bitmap=LightCapsuleContours.bitmap(ctx,BrightnessLayoutPolicy.percentWidth(font)+if(BrightnessLayoutPolicy.showSteps(width,font))96 else 0,
                        "color_temp" in modes,modes.any{it in LightColor.MODES},state=="on")
                    assertEquals(0,android.graphics.Color.alpha(bitmap.getPixel(bitmap.width/2,bitmap.height/2)))
                    assertEquals(0,android.graphics.Color.alpha(bitmap.getPixel(0,0)))
                }
            }
    }
    @Test fun contourPixelsDoNotDependOnActiveMode() {
        val before=light(listOf("color_temp","rgbww"),"color_temp")
        val after=before.copy(colorMode="rgbww")
        val a=LightCapsuleContours.bitmap(context,48f,before.temperatureCapable,before.colorCapable,true)
        val b=LightCapsuleContours.bitmap(context,48f,after.temperatureCapable,after.colorCapable,true)
        assertTrue(a.sameAs(b))
    }
    @Test fun confirmedHistoryRestoresAcrossModeSwitchRestartAndTwoDashboards() {
        val ctx=object:ContextWrapper(context) {
            val prefix="color-${UUID.randomUUID()}-"
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(prefix+name,mode)
        }
        val repo=DashboardRepository(ctx)
        val entity=HaEntity("light.a","on","Lamp",null,"2026-10-02T00:00:00Z",brightness=light(listOf("color_temp","rgbww"),"rgbww"))
        for(id in listOf(301,302))repo.saveConfiguration(DashboardConfig(id,emptyList(),emptyMap(),listOf("device"),emptyMap(),emptyMap(),false,true),
            HaCatalog(listOf(HaDeviceGroup(HaDevice("device","Lamp"),listOf(entity)))))
        for(id in listOf(301,302))repo.updateEntityStates(id,listOf(entity),DashboardStateSource.EVENT)
        val switched=entity.copy(lastUpdated="2026-10-02T00:00:01Z",brightness=entity.brightness.copy(colorMode="color_temp",color=null))
        for(id in listOf(301,302))repo.updateEntityStates(id,listOf(switched),DashboardStateSource.EVENT)
        val truth=DashboardRepository(ctx).brightnessTruth("light.a")!!
        assertEquals(LightColor(120.0,75.0),truth.lastConfirmedColor)
        assertEquals(4000,truth.lastConfirmedTemperature)
        assertEquals(166,truth.lastConfirmedBrightness)
    }
    @Test fun confirmedColorHistoryDoesNotCrossServers() {
        val ctx=object:ContextWrapper(context) {
            val prefix="color-scope-${UUID.randomUUID()}-"
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(prefix+name,mode)
        }
        val connection=com.danila.hacustomwidgets.data.security.SecureConnectionStore(ctx)
        connection.save("https://first.invalid","fixture")
        val repo=DashboardRepository(ctx)
        val entity=HaEntity("light.a","on","Lamp",null,"2026-10-02T00:00:00Z",brightness=light(listOf("hs"),"hs"))
        repo.saveConfiguration(DashboardConfig(303,emptyList(),emptyMap(),listOf("device"),emptyMap(),emptyMap(),false,true),
            HaCatalog(listOf(HaDeviceGroup(HaDevice("device","Lamp"),listOf(entity)))))
        repo.updateEntityStates(303,listOf(entity),DashboardStateSource.EVENT)
        assertNotNull(repo.brightnessTruth("light.a")!!.lastConfirmedColor)
        connection.save("https://second.invalid","fixture")
        assertNull(repo.brightnessTruth("light.a"))
        repo.updateEntityStates(303,listOf(entity.copy(state="off",lastUpdated="2026-10-02T00:00:01Z",brightness=entity.brightness.copy(color=null))),DashboardStateSource.EVENT)
        assertNull(repo.brightnessTruth("light.a")!!.lastConfirmedColor)
    }
    @Test fun floatingActivitySectionsAndPickerBackFollowCapabilities() {
        val container=(context.applicationContext as com.danila.hacustomwidgets.HaWidgetApplication).container
        val saved=container.connectionStore.load()
        val id=70301
        fun nodes(node:android.view.accessibility.AccessibilityNodeInfo?):List<android.view.accessibility.AccessibilityNodeInfo> =
            if(node==null)emptyList() else listOf(node)+(0 until node.childCount).flatMap{nodes(node.getChild(it))}
        fun visible()=nodes(instrumentation.uiAutomation.rootInActiveWindow)
        try {
            container.connectionStore.save("https://activity-fixture.invalid","fixture")
            for(modes in listOf(listOf("brightness"),listOf("color_temp"),listOf("hs"),listOf("color_temp","rgbww"))) {
                val entity=HaEntity("light.host_color","on","Очень длинное имя лампы / Very long lamp name",null,"2026-10-02T00:00:00Z",brightness=light(modes,if("hs" in modes)"hs" else "color_temp").copy(color=null))
                container.dashboards.saveConfiguration(DashboardConfig(id,emptyList(),emptyMap(),listOf("color-host"),emptyMap(),emptyMap(),false,true),
                    HaCatalog(listOf(HaDeviceGroup(HaDevice("color-host","Lamp"),listOf(entity)))))
                container.dashboards.updateEntityStates(id,listOf(entity),DashboardStateSource.EVENT)
                val intent=android.content.Intent(context,BrightnessActivity::class.java).putExtra("brightness_entity",entity.entityId)
                    .putExtra("brightness_widget",id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                androidx.test.core.app.ActivityScenario.launch<BrightnessActivity>(intent).use { scenario ->
                    instrumentation.waitForIdleSync();android.os.SystemClock.sleep(300)
                    val text=visible().mapNotNull{it.text?.toString()}.joinToString(" ")
                    assertEquals("color_temp" in modes,text.contains(com.danila.hacustomwidgets.tr("Color temperature","Цветовая температура")))
                    if(modes.any{it in LightColor.MODES}) {
                        val bar=visible().firstOrNull{it.contentDescription?.toString()?.contains(com.danila.hacustomwidgets.tr("Restore lamp color","Вернуть цвет лампы"))==true}
                            ?: visible().firstOrNull{it.contentDescription?.toString()==com.danila.hacustomwidgets.tr("Choose lamp color","Выбрать цвет лампы")}
                        assertNotNull(bar)
                        assertTrue(bar!!.performAction(if(modes==listOf("hs"))android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK else android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK))
                        instrumentation.waitForIdleSync();android.os.SystemClock.sleep(200)
                        assertTrue(visible().any{it.text?.toString()==com.danila.hacustomwidgets.tr("Choose color","Выбрать цвет")})
                        assertNull(container.brightness.colorTarget(entity.entityId))
                        instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/v070-picker-${modes.joinToString("-")}.png").use {
                            java.io.FileInputStream(it.fileDescriptor).readBytes()
                        }
                        val hue=visible().firstOrNull{it.rangeInfo!=null && it.contentDescription?.toString()==com.danila.hacustomwidgets.tr("Hue","Оттенок")}
                        assertNotNull(hue)
                        val progress=android.os.Bundle().apply{putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,200f)}
                        assertTrue(hue!!.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,progress))
                        instrumentation.waitForIdleSync()
                        android.os.SystemClock.sleep(100);instrumentation.waitForIdleSync()
                        val changedHue=visible().firstOrNull{it.rangeInfo!=null && it.contentDescription?.toString()==com.danila.hacustomwidgets.tr("Hue","Оттенок")}
                        assertNotNull(changedHue);assertEquals(200f,changedHue!!.rangeInfo.current,0.1f)
                        val apply=visible().firstOrNull{it.isClickable && it.isEnabled && it.isVisibleToUser &&
                            (it.text?.toString()==com.danila.hacustomwidgets.tr("Apply","Применить") ||
                                nodes(it).any{child->child.text?.toString()==com.danila.hacustomwidgets.tr("Apply","Применить")})}
                        assertNotNull("Enabled Apply button stays visible in compact picker",apply)
                        assertTrue("Apply dispatches a click",apply!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                        instrumentation.waitForIdleSync()
                        assertNotNull(container.brightness.colorTarget(entity.entityId))
                        container.brightness.invalidate()
                        android.os.SystemClock.sleep(100);instrumentation.waitForIdleSync()
                        val reopened=visible().firstOrNull{it.contentDescription?.toString()==com.danila.hacustomwidgets.tr("Choose lamp color","Выбрать цвет лампы")}
                        assertNotNull(reopened)
                        assertTrue(reopened!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK))
                        instrumentation.waitForIdleSync()
                        instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
                        instrumentation.waitForIdleSync();android.os.SystemClock.sleep(200)
                        scenario.onActivity{assertFalse(it.isFinishing)}
                    }
                    val name=modes.joinToString("-")
                    instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/v070-$name.png").use {
                        java.io.FileInputStream(it.fileDescriptor).readBytes()
                    }
                }
            }
        } finally {
            container.dashboards.delete(id)
            if(saved==null)container.connectionStore.clear()else container.connectionStore.save(saved.baseUrl,saved.token)
        }
    }
    @Test fun floatingActivityLargeFontsAndLandscapeRemainScrollable() {
        val container=(context.applicationContext as com.danila.hacustomwidgets.HaWidgetApplication).container
        val saved=container.connectionStore.load();val originalFont=context.resources.configuration.fontScale
        val id=70302
        fun shell(command:String) {instrumentation.uiAutomation.executeShellCommand(command).use{java.io.FileInputStream(it.fileDescriptor).readBytes()}}
        fun nodes(node:android.view.accessibility.AccessibilityNodeInfo?):List<android.view.accessibility.AccessibilityNodeInfo> =
            if(node==null)emptyList()else listOf(node)+(0 until node.childCount).flatMap{nodes(node.getChild(it))}
        try {
            container.connectionStore.save("https://activity-font-fixture.invalid","fixture")
            val entity=HaEntity("light.host_font","on","Очень длинное имя лампы для проверки переноса / Very long lamp name for wrapping",null,"2026-10-02T00:00:00Z",brightness=light(listOf("color_temp","rgbww")))
            container.dashboards.saveConfiguration(DashboardConfig(id,emptyList(),emptyMap(),listOf("font-host"),emptyMap(),emptyMap(),false,true),
                HaCatalog(listOf(HaDeviceGroup(HaDevice("font-host","Lamp"),listOf(entity)))))
            container.dashboards.updateEntityStates(id,listOf(entity),DashboardStateSource.EVENT)
            for(font in listOf(1f,1.5f,2f))for(landscape in listOf(false,true)) {
                shell("settings put system font_scale $font")
                android.os.SystemClock.sleep(300)
                val intent=android.content.Intent(context,BrightnessActivity::class.java).putExtra("brightness_entity",entity.entityId)
                    .putExtra("brightness_widget",id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                androidx.test.core.app.ActivityScenario.launch<BrightnessActivity>(intent).use { scenario ->
                    scenario.onActivity{it.requestedOrientation=if(landscape)android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
                    instrumentation.waitForIdleSync();android.os.SystemClock.sleep(500)
                    scenario.onActivity {
                        assertTrue(it.window.decorView.height<=it.windowManager.currentWindowMetrics.bounds.height())
                        assertEquals(font,it.resources.configuration.fontScale,0.01f)
                        assertFalse(it.isFinishing)
                    }
                    var close:android.view.accessibility.AccessibilityNodeInfo?=null
                    repeat(16) {
                        if(close==null) {
                            val current=nodes(instrumentation.uiAutomation.rootInActiveWindow)
                            close=current.firstOrNull{it.isVisibleToUser && it.text?.toString()==com.danila.hacustomwidgets.tr("Close","Закрыть")}
                            if(close==null)current.firstOrNull{it.actionList.any{action->action.id==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD}}
                                ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            instrumentation.waitForIdleSync();android.os.SystemClock.sleep(100)
                        }
                    }
                    assertNotNull("Close remains reachable at font=$font landscape=$landscape",close)
                    shell("screencap -p /data/local/tmp/v070-font-$font-$landscape.png")
                }
            }
        } finally {
            shell("settings put system font_scale $originalFont")
            container.dashboards.delete(id)
            if(saved==null)container.connectionStore.clear()else container.connectionStore.save(saved.baseUrl,saved.token)
        }
    }
}
