package com.danila.hacustomwidgets.dashboard

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.widget.FrameLayout

/** Debug-only surface for unmodified framework AppWidgetHostViews. */
class ViewportHostActivity : ComponentActivity() {
    lateinit var surface: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = FrameLayout(this)
        setContentView(surface)
    }
}
