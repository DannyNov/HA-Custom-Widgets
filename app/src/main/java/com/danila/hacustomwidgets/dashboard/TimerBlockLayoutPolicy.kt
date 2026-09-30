package com.danila.hacustomwidgets.dashboard

import kotlin.math.ceil
import kotlin.math.max

internal data class TimerBlockLayout(val blockWidth: Int, val textWidth: Int, val intervalHeight: Int, val leadingSpace: Int, val remainingLeading: Int, val remainingWidth: Int)

internal object TimerBlockLayoutPolicy {
    // User 1.png: caption begins 464px into the 1098px card (after card edge).
    // This design coordinate depends only on allocated width, never on either string.
    private const val TEXT_ANCHOR_FRACTION = 464f / 1098f
    fun resolve(width: Int, compact: Boolean, power: Boolean, interval: Float, remaining: Float,
        fontScale: Float): TimerBlockLayout {
        val captionAvailable = width - (if (compact) 18 else 24) - (if (width < 250) 16 else 20)
        val available = captionAvailable - (if (power) 48 else 0) - 4
        val textX = (captionAvailable * TEXT_ANCHOR_FRACTION).toInt()
            .coerceIn(56, max(56, available - 1))
        val leading = textX - 56
        val text = minOf(ceil(interval + 4).toInt(), available - textX).coerceAtLeast(1)
        val lines = ceil(interval / text).toInt().coerceAtLeast(1)
        // Caption is below the complete control row, so it may use Power's horizontal area.
        // Full remaining row width and Start alignment make every tick use exactly textX.
        return TimerBlockLayout(56 + text, text,
            max(48, ceil(lines * 11 * fontScale * 1.5f).toInt()), leading,
            textX, (captionAvailable - textX).coerceAtLeast(1))
    }
}
