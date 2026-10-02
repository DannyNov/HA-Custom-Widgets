package com.danila.hacustomwidgets.dashboard

import android.graphics.*
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.text.TextLayoutResult
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
class LightVisualRc3HostTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    @Test fun temperatureOrientationAndProtectedRainbowAcrossWidthsAndPower() {
        val d=context.resources.displayMetrics.density
        val colors=intArrayOf(0xffffbf69.toInt(),0xffffedcf.toInt(),0xfff5f8ff.toInt(),0xff82beff.toInt())
        for(width in listOf(48f,96f,144f)) for(on in listOf(false,true)) for(dual in listOf(false,true)) {
            val actual=LightCapsuleContours.bitmap(context,width,true,dual,on)
            val reference=Bitmap.createBitmap(actual.width,actual.height,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(reference)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=d; color=context.getColor(if(on)com.danila.hacustomwidgets.R.color.widget_light_on else com.danila.hacustomwidgets.R.color.widget_secondary)}
            canvas.drawRoundRect(RectF(d/2,d/2,actual.width-d/2,actual.height-d/2),24*d,24*d,paint)
            val inset=3*d
            // Independent geometric construction: extrema are capsule support points at 270/90 degrees.
            val vx=0f;val vy=1f
            val extent=(actual.width-actual.height)/2f*vx+actual.height/2f-inset
            val cx=actual.width/2f;val cy=actual.height/2f
            paint.strokeWidth=1.2f*d;paint.alpha=255
            paint.shader=LinearGradient(cx-vx*extent,cy-vy*extent,cx+vx*extent,cy+vy*extent,colors,null,Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(inset,inset,actual.width-inset,actual.height-inset),24*d-inset,24*d-inset,paint)
            if(dual) {
                val inner=6*d;paint.alpha=190
                paint.shader=LinearGradient(inner,0f,actual.width-inner,0f,intArrayOf(0xffff5656.toInt(),0xffffd85c.toInt(),0xff66d98b.toInt(),0xff64c9ff.toInt(),0xffa887ee.toInt(),0xfff584c7.toInt()),null,Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(inner,inner,actual.width-inner,actual.height-inner),24*d-inner,24*d-inner,paint)
            }
            assertTrue("+90 degree temperature, RC2 outer/rainbow geometry/palette: $width/$on/$dual",actual.sameAs(reference))
            assertEquals(0,Color.alpha(actual.getPixel(actual.width/2,actual.height/2)))
            val name="v070-rc4-contour-$width-$on-$dual.png"
            val file=java.io.File(context.getExternalFilesDir(null),name)
            file.outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)}
            instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/$name").use{java.io.FileInputStream(it.fileDescriptor).readBytes()}
            file.delete();reference.recycle()
        }
    }

    @Test fun finalRuEnHelpWrapsWithoutClippingAtNarrowWidthsAndLargeFonts() {
        val strings=listOf(listOf("Нажать — включить цвет","Удерживать — выбрать цвет"),listOf("Tap — turn on color","Hold — choose color"))
        val intent=android.content.Intent(context,BrightnessActivity::class.java).putExtra("brightness_entity","light.visual_rc3").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<BrightnessActivity>(intent).use { scenario ->
            for(width in listOf(180,280,372)) for(font in listOf(1f,1.5f,2f)) for((language,text) in strings.withIndex()) {
                val layouts=java.util.concurrent.CopyOnWriteArrayList<TextLayoutResult>()
                val bounds=java.util.concurrent.ConcurrentHashMap<String,androidx.compose.ui.geometry.Rect>()
                scenario.onActivity { activity -> activity.setContent {
                    HaCustomWidgetsTheme {
                        val original=LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(original.density,font)) {
                            Column(Modifier.width(width.dp).background(ComposeColor(0xff10171c)).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                text.forEach { line -> Text(line,modifier=Modifier.onGloballyPositioned { bounds[line]=it.boundsInRoot() },style=MaterialTheme.typography.bodySmall,color=ComposeColor.White,onTextLayout={layouts.add(it)}) }
                            }
                        }
                    }
                } }
                instrumentation.waitForIdleSync();android.os.SystemClock.sleep(150)
                assertTrue("Explicit help instructions do not overlap",bounds.getValue(text[0]).bottom < bounds.getValue(text[1]).top)
                for(line in text) {
                    val layout=layouts.last { it.layoutInput.text.text==line }
                    assertFalse("Help fits $language/$width/$font",layout.hasVisualOverflow)
                    assertFalse(layout.didOverflowWidth);assertFalse(layout.didOverflowHeight)
                    assertEquals(line.length,layout.getLineEnd(layout.lineCount-1,visibleEnd=true))
                    if(width==180 && font==2f) assertTrue("Narrow large font wraps",layout.lineCount>1)
                    if(width==372 && font==1f) assertEquals("Each instruction is one normal line",1,layout.lineCount)
                }
                val name="v070-rc4-help-$language-$width-$font.png"
                instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$name").use{java.io.FileInputStream(it.fileDescriptor).readBytes()}
            }
        }
    }
}
