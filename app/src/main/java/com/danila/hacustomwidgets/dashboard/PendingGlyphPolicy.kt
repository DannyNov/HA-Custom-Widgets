package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.R

/** Exactly one drawable occupies the glyph slot; its background and bounds never change. */
internal object PendingGlyphPolicy {
    fun icon(normal: Int, status: DashboardOperationStatus?): Int =
        if (status?.isActive == true) R.drawable.ic_launch_pending else normal
}
