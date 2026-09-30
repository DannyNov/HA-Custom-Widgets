package com.danila.hacustomwidgets.dashboard

import kotlin.math.ceil
import kotlin.math.max

internal data class TimerBlockLayout(val blockWidth: Int, val textWidth: Int, val intervalHeight: Int)

internal object TimerBlockLayoutPolicy {
    fun resolve(width: Int, compact: Boolean, power: Boolean, interval: Float, remaining: Float,
        fontScale: Float): TimerBlockLayout {
        // Account for card and outer widget padding; host-only cards simply have extra breathing room.
        val available = width - (if (compact) 18 else 24) - (if (width < 250) 16 else 20) -
            (if (power) 48 else 0) - 4
        val text = minOf(ceil(max(interval, remaining) + 4).toInt(), available - 56).coerceAtLeast(1)
        val lines = ceil(interval / text).toInt().coerceAtLeast(1)
        return TimerBlockLayout(56 + text, text, max(48, ceil(lines * 11 * fontScale * 1.5f).toInt()))
    }
}
