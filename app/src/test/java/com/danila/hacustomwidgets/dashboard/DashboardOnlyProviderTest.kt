package com.danila.hacustomwidgets.dashboard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import com.danila.hacustomwidgets.data.MetricLabels

class DashboardOnlyProviderTest {
    @Test fun manifestExposesOnlyDashboardProviderAndConfiguration() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val metadata = doc.getElementsByTagName("meta-data")
        val providers = (0 until metadata.length).map { metadata.item(it) as org.w3c.dom.Element }
            .filter { it.getAttributeNS(android, "name") == "android.appwidget.provider" }
        assertEquals(1, providers.size)
        assertEquals(".dashboard.DashboardWidgetReceiver",
            (providers.single().parentNode as org.w3c.dom.Element).getAttributeNS(android, "name"))
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("EntityStateWidget"))
        val actions = doc.getElementsByTagName("action")
        val configure = (0 until actions.length).map { actions.item(it) as org.w3c.dom.Element }
            .filter { it.getAttributeNS(android, "name") == "android.appwidget.action.APPWIDGET_CONFIGURE" }
        assertEquals(1, configure.size)
        assertEquals(".dashboard.DashboardWidgetConfigActivity",
            (configure.single().parentNode.parentNode as org.w3c.dom.Element).getAttributeNS(android, "name"))
    }

    @Test fun sharedDashboardMetricLabelsRemainCompatible() {
        assertEquals("Temperature", MetricLabels.compactMetricName("Bedroom", "bedroom Temperature"))
        assertEquals("Bedroom", MetricLabels.compactMetricName("Bedroom", "Bedroom"))
        assertEquals("Outside", MetricLabels.compactMetricName("Bedroom", " Outside "))
    }
}
