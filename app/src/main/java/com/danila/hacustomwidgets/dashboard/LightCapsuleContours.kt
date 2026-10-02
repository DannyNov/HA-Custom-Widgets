package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.graphics.*
import android.util.LruCache
import com.danila.hacustomwidgets.R
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin

/** Small bounded bitmap backgrounds avoid unsupported RemoteViews gradient strokes. */
object LightCapsuleContours {
    private val cache = LruCache<String, Bitmap>(24)
    @Synchronized
    fun bitmap(context: Context, widthDp: Float, temperature: Boolean, color: Boolean, on: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val tone = context.getColor(if (on) R.color.widget_light_on else R.color.widget_secondary)
        val key = "$widthDp/$density/$temperature/$color/$tone"
        cache.get(key)?.let { return it }
        val width = (widthDp*density).roundToInt().coerceAtLeast(1)
        val height = (48*density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=density; this.color=tone }
        canvas.drawRoundRect(RectF(density/2,density/2,width-density/2,height-density/2),24*density,24*density,paint)
        var inset = 3f*density
        fun contour(colors: IntArray, alpha: Int = 190, temperaturePhase: Boolean = false) {
            paint.strokeWidth=1.2f*density
            paint.shader=if (temperaturePhase) {
                // Android's positive Y points down: +90 degrees puts warm at the top
                // and cold at the bottom (+15 degrees from RC3). Normalize to the capsule's projected
                // extent so the existing palette endpoints/contrast survive the orientation.
                val angle=Math.toRadians(90.0)
                val dx=cos(angle).toFloat(); val dy=sin(angle).toFloat()
                val extent=(width-height).coerceAtLeast(0)/2f*dx + height/2f-inset
                val cx=width/2f; val cy=height/2f
                LinearGradient(cx-dx*extent,cy-dy*extent,cx+dx*extent,cy+dy*extent,colors,null,Shader.TileMode.CLAMP)
            } else LinearGradient(inset,0f,width-inset,0f,colors,null,Shader.TileMode.CLAMP)
            paint.alpha=alpha
            val radius=24*density-inset
            canvas.drawRoundRect(RectF(inset,inset,width-inset,height-inset),radius,radius,paint)
            inset += 3f*density
        }
        if (temperature) contour(intArrayOf(0xffffbf69.toInt(),0xffffedcf.toInt(),0xfff5f8ff.toInt(),0xff82beff.toInt()), alpha = 255, temperaturePhase = true)
        if (color) contour(intArrayOf(0xffff5656.toInt(),0xffffd85c.toInt(),0xff66d98b.toInt(),0xff64c9ff.toInt(),0xffa887ee.toInt(),0xfff584c7.toInt()))
        cache.put(key,bitmap)
        return bitmap
    }
}
