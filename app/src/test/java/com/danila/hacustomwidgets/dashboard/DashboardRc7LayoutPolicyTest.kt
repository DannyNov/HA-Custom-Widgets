package com.danila.hacustomwidgets.dashboard

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRc7LayoutPolicyTest {
    @Test fun groupingLabelUsesBoundedStartAlignedLayoutWithPositiveInset() {
        assertTrue(dashboardGroupingLabelLayout.horizontalInsetDp > 0)
        assertTrue(dashboardGroupingLabelLayout.fillsAvailableWidth)
        assertEquals(TextAlign.Start, dashboardGroupingLabelLayout.textAlign)
        assertEquals(1, dashboardGroupingLabelLayout.maxLines)
        assertEquals(TextOverflow.Ellipsis, dashboardGroupingLabelLayout.overflow)

        listOf(1f, 1.5f, 2f, 3f, 4f).forEach { density ->
            assertTrue(dashboardGroupingLabelLayout.horizontalInsetDp * density >= density)
        }
    }

    @Test fun groupingLabelsNeedNoWhitespaceWorkaroundInEitherLocale() {
        val previous = Locale.getDefault()
        try {
            listOf(Locale.ENGLISH, Locale.forLanguageTag("ru")).forEach { locale ->
                Locale.setDefault(locale)
                (DashboardGrouping.entries + listOf<DashboardGrouping?>(null)).forEach { grouping ->
                    val label = dashboardGroupingLabel(grouping)
                    assertFalse(label.startsWith(" "))
                    assertTrue(label.isNotBlank())
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
