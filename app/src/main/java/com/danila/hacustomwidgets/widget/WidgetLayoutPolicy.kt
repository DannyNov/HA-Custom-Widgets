package com.danila.hacustomwidgets.widget

import kotlin.math.max
import kotlin.math.min

data class WidgetLayoutSpec(
    val columns: Int,
    val visibleItems: Int,
    val paddingDp: Int,
    val gapDp: Int,
    val showTitle: Boolean,
    val showLabels: Boolean,
    val showFooter: Boolean,
    val valueTextSp: Int,
)

fun widgetLayoutSpec(widthDp: Int, heightDp: Int, itemCount: Int): WidgetLayoutSpec {
    val columns = when {
        widthDp >= 330 -> 3
        widthDp >= 210 -> 2
        else -> 1
    }
    val headerHeight = if (heightDp >= 72) 24 else 0
    val footerHeight = if (heightDp >= 110) 18 else 0
    val rowHeight = when {
        heightDp >= 220 -> 60
        heightDp >= 140 -> 54
        else -> 46
    }
    val rows = max(1, (heightDp - headerHeight - footerHeight - 12) / rowHeight)
    val capacity = max(1, columns * rows)
    val tileWidth = max(48, (widthDp - 20 - (columns - 1) * 6) / columns)
    return WidgetLayoutSpec(
        columns = columns,
        visibleItems = min(itemCount.coerceAtLeast(1), capacity),
        paddingDp = if (widthDp < 120 || heightDp < 80) 8 else 12,
        gapDp = if (widthDp < 160) 4 else 6,
        showTitle = heightDp >= 72,
        showLabels = heightDp >= 82 && tileWidth >= 72,
        showFooter = heightDp >= 110,
        valueTextSp = when {
            tileWidth >= 120 && heightDp >= 120 -> 22
            tileWidth >= 78 -> 18
            else -> 15
        },
    )
}
