package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaDevice
import com.danila.hacustomwidgets.data.model.HaDeviceGroup
import com.danila.hacustomwidgets.data.model.HaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardV0522CorrectivePolicyTest {
    private fun entity(
        id: String,
        name: String = id,
        state: String = "off",
        unit: String? = null,
        deviceClass: String? = null,
    ) = HaEntity(id, state, name, unit, null, deviceClass = deviceClass)

    private fun metric(index: Int) = DashboardMetric(
        entityId = "sensor.air_$index",
        label = "Части летучих органических соединений $index",
        state = "$index ppb",
        rawState = "$index",
        domain = "sensor",
        deviceClass = null,
    )

    @Test fun fiveSelectedAirMetricsAllRemainInRenderRows() {
        val metrics = (1..5).map(::metric)
        val rows = MetricLayoutPolicy.rows(metrics, 360)
        assertEquals(metrics, rows.flatten())
        assertEquals(5, rows.size)
    }

    @Test fun threeColumnsReserveTheFullCardInset() {
        val cellWidth = MetricLayoutPolicy.cellWidthDp(360, 3)
        assertEquals(100, cellWidth)
        assertTrue(cellWidth * 3 <= 360 - 60)
    }

    @Test fun doorbellIdentityWinsOverItsIndicatorLightEntity() {
        val group = HaDeviceGroup(
            device = HaDevice("doorbell", "Mi Smart Video Doorbell with Monitor 1S"),
            entities = listOf(
                entity("light.video_doorbell_indicator_light", "Indicator Light"),
                entity("sensor.video_doorbell_battery", "Video Doorbell Battery", "78", "%", "battery"),
            ),
        )
        assertEquals(DeviceCategory.SECURITY, deviceCategory(group))
    }
}
