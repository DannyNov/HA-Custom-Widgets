package com.danila.hacustomwidgets.dashboard

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRc7LayoutPolicyTest {
    @Test fun groupingRowUsesStableTwoColumnContract() {
        assertEquals(0.58f, dashboardGroupingLabelLayout.leftColumnWeight)
        assertEquals(0.42f, dashboardGroupingLabelLayout.rightColumnWeight)
        assertEquals(
            1f,
            dashboardGroupingLabelLayout.leftColumnWeight + dashboardGroupingLabelLayout.rightColumnWeight,
            0.0001f,
        )
        assertTrue(dashboardGroupingLabelLayout.leftColumnWeight in 0.55f..0.60f)
        assertTrue(dashboardGroupingLabelLayout.rightColumnWeight in 0.40f..0.45f)
        assertTrue(dashboardGroupingLabelLayout.horizontalInsetDp > 0)
        assertTrue(dashboardGroupingLabelLayout.fillsAvailableWidth)
        assertEquals(TextAlign.Start, dashboardGroupingLabelLayout.textAlign)
        assertEquals(2, dashboardGroupingLabelLayout.maxLines)
        assertTrue(dashboardGroupingLabelLayout.softWrap)
        assertEquals(TextOverflow.Clip, dashboardGroupingLabelLayout.overflow)

        listOf(1f, 1.5f, 2f, 3f, 4f).forEach { density ->
            assertTrue(dashboardGroupingLabelLayout.horizontalInsetDp * density >= density)
        }
    }

    @Test fun rightColumnIsReservedWhileGroupOrderIsTypesOnly() {
        assertTrue(dashboardGroupingLabelLayout.rightColumnWeight > 0f)
        assertTrue(showGroupOrderButton(DashboardGrouping.TYPES))
        assertFalse(showGroupOrderButton(DashboardGrouping.ROOMS))
        assertFalse(showGroupOrderButton(DashboardGrouping.NONE))
        assertFalse(showGroupOrderButton(null))
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
