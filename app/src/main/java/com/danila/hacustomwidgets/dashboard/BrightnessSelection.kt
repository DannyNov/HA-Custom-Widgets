package com.danila.hacustomwidgets.dashboard

import kotlin.math.roundToInt

/** Keeps drag updates local. A completed gesture can yield at most one absolute target. */
class BrightnessSelection {
    private var pending: Int? = null
    fun change(value: Float) { pending = value.roundToInt().coerceIn(1, 100) }
    fun finish(enabled: Boolean): Int? = pending.also { pending = null }?.takeIf { enabled }
}
