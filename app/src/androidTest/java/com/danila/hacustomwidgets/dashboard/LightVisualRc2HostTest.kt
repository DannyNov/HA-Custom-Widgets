package com.danila.hacustomwidgets.dashboard

import android.graphics.*
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danila.hacustomwidgets.ui.HaCustomWidgetsTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class LightVisualRc2HostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** RC1 bitmap reference makes preservation of the successful rainbow independently testable. */
    private fun rc1(widthDp: Float, color: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (widthDp*density).roundToInt(); val height = (48*density).roundToInt()
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE; strokeWidth=density
            this.color=context.getColor(com.danila.hacustomwidgets.R.color.widget_light_on)
        }
        canvas.drawRoundRect(RectF(density/2,density/2,width-density/2,height-density/2),24*density,24*density,paint)
        if(color) {
            val inset=3*density
            paint.strokeWidth=1.2f*density; paint.alpha=190
            paint.shader=LinearGradient(inset,0f,width-inset,0f,intArrayOf(0xffff5656.toInt(),0xffffd85c.toInt(),0xff66d98b.toInt(),0xff64c9ff.toInt(),0xffa887ee.toInt(),0xfff584c7.toInt()),null,Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(inset,inset,width-inset,height-inset),24*density-inset,24*density-inset,paint)
        }
        return bitmap
    }

    @Test fun rainbowAndBrightnessOnlyPixelsRemainExactlyRc1() {
        for(width in listOf(48f,96f,144f)) for(color in listOf(false,true)) {
            assertTrue(LightCapsuleContours.bitmap(context,width,false,color,true).sameAs(rc1(width,color)))
        }
    }

    @Test fun temperatureReadsAgainstHonorCardAndSeparatesFromRainbow() {
        val density=context.resources.displayMetrics.density
        fun sample(b:Bitmap, right:Boolean, inset:Int):Int = b.getPixel(if(right)b.width-(inset*density).roundToInt()-1 else (inset*density).roundToInt(),b.height/2)
        fun distance(a:Int,b:Int)=kotlin.math.abs(Color.red(a)-Color.red(b))+kotlin.math.abs(Color.green(a)-Color.green(b))+kotlin.math.abs(Color.blue(a)-Color.blue(b))
        fun overCard(c:Int):Int {
            val a=Color.alpha(c)/255f
            return Color.rgb((Color.red(c)*a+44*(1-a)).roundToInt(),(Color.green(c)*a+50*(1-a)).roundToInt(),(Color.blue(c)*a+54*(1-a)).roundToInt())
        }
        for(width in listOf(48f,144f)) {
            val temp=LightCapsuleContours.bitmap(context,width,true,false,true)
            val dual=LightCapsuleContours.bitmap(context,width,true,true,true)
            val warm=overCard(sample(temp,false,3)); val cold=overCard(sample(temp,true,3))
            assertTrue("Temperature endpoints distinct on #2c3236",distance(warm,cold)>55)
            assertTrue(Color.red(warm)>Color.blue(warm)+15)
            assertTrue(Color.blue(cold)>Color.red(cold)+10)
            for(right in listOf(false,true)) {
                val t=overCard(sample(dual,right,3)); val c=overCard(sample(dual,right,6))
                assertTrue("Dual contours distinct",distance(t,c)>60)
                assertTrue("Contour differs from Honor card",distance(t,Color.rgb(44,50,54))>240)
                assertTrue("Temperature differs from neutral outer border",distance(t,overCard(sample(dual,right,0)))>25)
            }
        }
    }

    @Test fun renderedTrackThumbAndHeaderMatrixUsesOneGeometry() {
        val intent=android.content.Intent(context,BrightnessActivity::class.java)
            .putExtra("brightness_entity","light.visual_fixture").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<BrightnessActivity>(intent).use { scenario ->
            for(width in listOf(180,280,372)) for(font in listOf(1f,1.5f,2f)) {
                val bounds=java.util.concurrent.ConcurrentHashMap<String,androidx.compose.ui.geometry.Rect>()
                scenario.onActivity { activity -> activity.setContent {
                    HaCustomWidgetsTheme {
                        val original=LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(original.density,font)) {
                            Column(Modifier.width(width.dp).background(ComposeColor(0xff10171c))) {
                                LightControlHeader("Яркость", "65%")
                                LightControlSlider(65f, {}, valueRange=1f..100f,steps=98,
                                    modifier=Modifier.onGloballyPositioned{bounds["brightness"]=it.boundsInWindow()})
                                LightControlHeader("Цветовая температура", "4000 K")
                                LightControlSlider(4000f, {}, valueRange=2700f..6500f,
                                    gradient=Brush.horizontalGradient(LightControlStyle.temperatureColors),
                                    modifier=Modifier.onGloballyPositioned{bounds["temperature"]=it.boundsInWindow()})
                                LightControlColorTrack(Brush.horizontalGradient(listOf(ComposeColor.Red,ComposeColor.Blue)),
                                    Modifier.onGloballyPositioned{bounds["color"]=it.boundsInWindow()})
                            }
                        }
                    }
                } }
                instrumentation.waitForIdleSync();android.os.SystemClock.sleep(200)
                val d=context.resources.displayMetrics.density
                assertEquals(3,bounds.size)
                val origin=IntArray(2)
                scenario.onActivity { it.window.decorView.getLocationOnScreen(origin) }
                val offset=androidx.compose.ui.geometry.Offset(origin[0].toFloat(),origin[1].toFloat())
                val b=bounds.getValue("brightness").translate(offset)
                val t=bounds.getValue("temperature").translate(offset)
                val c=bounds.getValue("color").translate(offset)
                val image=instrumentation.uiAutomation.takeScreenshot()!!
                val file=java.io.File(context.getExternalFilesDir(null),"v070-rc2-style-$width-$font.png")
                file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
                instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/${file.name}").use{java.io.FileInputStream(it.fileDescriptor).readBytes()}
                file.delete()
                fun nodes(node:android.view.accessibility.AccessibilityNodeInfo?):List<android.view.accessibility.AccessibilityNodeInfo> =
                    if(node==null || !node.refresh())emptyList()else listOf(node)+(0 until node.childCount).flatMap{nodes(node.getChild(it))}
                val tree=nodes(instrumentation.uiAutomation.rootInActiveWindow)
                fun textRect(text:String):Rect {
                    val node=tree.firstOrNull{it.text?.toString()==text}
                    assertNotNull("Header text stays visible: $text",node)
                    return Rect().also{node!!.getBoundsInScreen(it)}
                }
                val label=textRect("Яркость");val value=textRect("65%")
                val tempLabel=textRect("Цветовая температура");val tempValue=textRect("4000 K")
                assertTrue(label.bottom<=b.top+1);assertTrue(value.bottom<=b.top+1)
                assertTrue(tempLabel.bottom<=t.top+1);assertTrue(tempValue.bottom<=t.top+1)
                assertFalse(Rect.intersects(label,value));assertFalse(Rect.intersects(tempLabel,tempValue))
                if(width>=372 && font==1f) {
                    assertEquals("Brightness header shares row",label.centerY(),value.centerY())
                    assertEquals("Temperature header shares row",tempLabel.centerY(),tempValue.centerY())
                }
                for(rect in listOf(b,t,c)) {
                    assertTrue("Effective target >=48dp",rect.height>=48*d-1)
                    assertEquals(b.width,rect.width,1f)
                }
                // The centerline away from thumbs is 10dp high in every real rendered component.
                for(rect in listOf(b,t,c)) {
                    val x=(rect.left+rect.width*.3f).roundToInt()
                    val cy=((rect.top+rect.bottom)/2).roundToInt()
                    fun differs(y:Int):Boolean {
                        val p=image.getPixel(x,y)
                        return Color.red(p)+Color.green(p)+Color.blue(p)>130
                    }
                    assertTrue(differs(cy));assertTrue(differs(cy+(3*d).roundToInt()))
                    assertFalse("Track stays compact",differs(cy+(7*d).roundToInt()))
                }
                // Thumb extends beyond the track, but no longer forms RC1's tall vertical bar.
                for((rect,fraction) in listOf(b to (64f/99f),t to (1300f/3800f))) {
                    val x=(rect.left+10*d+(rect.width-20*d)*fraction).roundToInt()
                    val cy=((rect.top+rect.bottom)/2).roundToInt()
                    val inside=image.getPixel(x,cy+(7*d).roundToInt())
                    val outside=image.getPixel(x,cy+(12*d).roundToInt())
                    assertTrue(Color.red(inside)+Color.green(inside)+Color.blue(inside)>200)
                    assertTrue(Color.red(outside)+Color.green(outside)+Color.blue(outside)<130)
                }
                image.recycle()
            }
        }
    }

    @Test fun circularSlidersRetainDragFinishAndAccessibleAbsoluteProgress() {
        val intent=android.content.Intent(context,BrightnessActivity::class.java)
            .putExtra("brightness_entity","light.drag_fixture").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val values=java.util.concurrent.ConcurrentHashMap<String,Float>()
        val finishes=java.util.concurrent.ConcurrentHashMap<String,Int>()
        val bounds=java.util.concurrent.ConcurrentHashMap<String,androidx.compose.ui.geometry.Rect>()
        ActivityScenario.launch<BrightnessActivity>(intent).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                HaCustomWidgetsTheme {
                    Column(Modifier.width(280.dp)) {
                        for(name in listOf("brightness","temperature")) {
                            val range=if(name=="brightness")1f..100f else 2700f..6500f
                            var selected by remember(name) { mutableStateOf(if(name=="brightness")50f else 4000f) }
                            LightControlSlider(selected, { selected=it;values[name]=it }, valueRange=range,
                                steps=if(name=="brightness")98 else 0,
                                gradient=if(name=="temperature")Brush.horizontalGradient(LightControlStyle.temperatureColors)else null,
                                onValueChangeFinished={finishes[name]=(finishes[name]?:0)+1},
                                modifier=Modifier.onGloballyPositioned{bounds[name]=it.boundsInWindow()}.semantics{contentDescription=name})
                        }
                    }
                }
            } }
            instrumentation.waitForIdleSync();android.os.SystemClock.sleep(200)
            val origin=IntArray(2);scenario.onActivity{it.window.decorView.getLocationOnScreen(origin)}
            for(name in listOf("brightness","temperature")) {
                val rect=bounds.getValue(name).translate(androidx.compose.ui.geometry.Offset(origin[0].toFloat(),origin[1].toFloat()))
                val y=((rect.top+rect.bottom)/2).roundToInt()
                instrumentation.uiAutomation.executeShellCommand("input swipe ${(rect.left+rect.width*.5f).roundToInt()} $y ${(rect.left+rect.width*.8f).roundToInt()} $y 250").use{java.io.FileInputStream(it.fileDescriptor).readBytes()}
                instrumentation.waitForIdleSync();android.os.SystemClock.sleep(100)
                assertTrue("Drag changes $name",values.getValue(name)>if(name=="brightness")50 else 4000)
                assertEquals("Single finish callback",1,finishes[name])
                fun nodes(node:android.view.accessibility.AccessibilityNodeInfo?):List<android.view.accessibility.AccessibilityNodeInfo> =
                    if(node==null || !node.refresh())emptyList()else listOf(node)+(0 until node.childCount).flatMap{nodes(node.getChild(it))}
                val slider=nodes(instrumentation.uiAutomation.rootInActiveWindow).first{it.contentDescription?.toString()==name && it.rangeInfo!=null}
                val target=if(name=="brightness")85f else 5500f
                val args=android.os.Bundle().apply{putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,target)}
                assertTrue(slider.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,args))
                instrumentation.waitForIdleSync()
                assertEquals(target,values.getValue(name),1f)
            }
        }
    }
}
