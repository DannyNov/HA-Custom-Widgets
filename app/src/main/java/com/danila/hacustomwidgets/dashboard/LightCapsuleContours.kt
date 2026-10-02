package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.graphics.*
import android.util.LruCache
import com.danila.hacustomwidgets.R
import kotlin.math.roundToInt

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
        fun contour(colors: IntArray, alpha: Int = 190) {
            paint.strokeWidth=1.2f*density
            paint.shader=LinearGradient(inset,0f,width-inset,0f,colors,null,Shader.TileMode.CLAMP)
            paint.alpha=alpha
            val radius=24*density-inset
            canvas.drawRoundRect(RectF(inset,inset,width-inset,height-inset),radius,radius,paint)
            inset += 3f*density
        }
        if (temperature) contour(intArrayOf(0xffffbf69.toInt(),0xffffedcf.toInt(),0xfff5f8ff.toInt(),0xff82beff.toInt()), alpha = 255)
        if (color) contour(intArrayOf(0xffff5656.toInt(),0xffffd85c.toInt(),0xff66d98b.toInt(),0xff64c9ff.toInt(),0xffa887ee.toInt(),0xfff584c7.toInt()))
        cache.put(key,bitmap)
        return bitmap
    }
}
