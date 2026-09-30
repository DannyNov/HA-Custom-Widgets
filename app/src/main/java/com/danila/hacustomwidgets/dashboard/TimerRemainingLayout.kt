package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import com.danila.hacustomwidgets.tr
import kotlin.math.ceil

/** Reserve the tallest possible caption for this configuration, rather than the current tick.
 * StaticLayout accounts for actual Android font padding and word wrapping. A bounded cache
 * avoids measuring the duration range on each realtime/countdown update.
 */
internal object TimerRemainingLayout {
    private data class Key(val width: Int, val density: Float, val scaledDensity: Float,
        val maximum: Int, val remaining: String, val paused: String)
    private val cache = linkedMapOf<Key, Int>()

    @Synchronized
    fun heightDp(context: Context, widthDp: Int, maximumMinutes: Int): Int {
        val metrics = context.resources.displayMetrics
        val key = Key(widthDp, metrics.density, metrics.scaledDensity,
            maximumMinutes.coerceIn(120, 1440), tr("Remaining ", "Осталось "), tr("Paused · ", "Пауза · "))
        return cache.getOrPut(key) {
            val paint = TextPaint().apply { textSize = 10 * metrics.scaledDensity; typeface = Typeface.DEFAULT }
            val width = (widthDp * metrics.density).toInt().coerceAtLeast(1)
            val height = (0..key.maximum).maxOf { minutes ->
                val caption = HaTimerPresentationPolicy.formatRemaining(minutes * 60_000L)
                listOf(key.remaining + caption, key.paused + caption).maxOf { label ->
                    StaticLayout.Builder.obtain(label, 0, label.length, paint, width)
                        .setIncludePad(true).build().height
                }
            }
            if (cache.size >= 32) cache.remove(cache.keys.first())
            ceil(height / metrics.density).toInt() + 2
        }
    }
}
