package com.danila.hacustomwidgets.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutPolicyTest {
    @Test
    fun wideWidgetUsesThreeColumns() {
        val spec = widgetLayoutSpec(widthDp = 360, heightDp = 160, itemCount = 6)
        assertEquals(3, spec.columns)
        assertTrue(spec.visibleItems >= 3)
        assertTrue(spec.showLabels)
    }

    @Test
    fun oneCellWidgetKeepsOneReadableValue() {
        val spec = widgetLayoutSpec(widthDp = 56, heightDp = 50, itemCount = 3)
        assertEquals(1, spec.columns)
        assertEquals(1, spec.visibleItems)
        assertFalse(spec.showTitle)
        assertFalse(spec.showFooter)
    }

    @Test
    fun tallerWidgetDisplaysMoreMetrics() {
        val short = widgetLayoutSpec(widthDp = 240, heightDp = 100, itemCount = 8)
        val tall = widgetLayoutSpec(widthDp = 240, heightDp = 260, itemCount = 8)
        assertTrue(tall.visibleItems > short.visibleItems)
    }
}
