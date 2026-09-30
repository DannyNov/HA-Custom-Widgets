package com.danila.hacustomwidgets.dashboard

/** Tint only a confirmed active timer; pending glyphs retain their existing drawable colors. */
internal object TimerGlyphPolicy {
    fun activeYellow(status: HaTimerStatus?, operation: DashboardOperationStatus?, controlState: String): Boolean =
        status == HaTimerStatus.ACTIVE && operation?.isActive != true &&
            controlState !in setOf("unknown", "unavailable")
}
