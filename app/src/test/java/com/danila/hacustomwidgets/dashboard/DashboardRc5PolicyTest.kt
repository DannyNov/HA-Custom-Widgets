package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.HaEntity
import com.danila.hacustomwidgets.R
import org.junit.Assert.*
import org.junit.Test

class DashboardRc5PolicyTest {
    @Test fun pendingReplacesEveryNormalGlyphAndCompletionRestoresIt() {
        listOf(R.drawable.ic_power, R.drawable.ic_timer, R.drawable.ic_launch_play).forEach { normal ->
            DashboardOperationStatus.entries.forEach { status ->
                assertEquals(if (status.isActive) R.drawable.ic_launch_pending else normal,
                    PendingGlyphPolicy.icon(normal, status))
            }
            assertEquals(normal, PendingGlyphPolicy.icon(normal, null))
        }
    }
    @Test fun sharedDefaultMetricOrderIsTemperatureHumidityBattery() {
        val metrics = listOf(
            HaEntity("sensor.battery", "80", "Battery", "%", null, deviceClass = "battery"),
            HaEntity("sensor.humidity", "45", "Humidity", "%", null, deviceClass = "humidity"),
            HaEntity("sensor.temperature", "22", "Temperature", "°C", null, deviceClass = "temperature"),
        )
        assertEquals(listOf("sensor.temperature", "sensor.humidity", "sensor.battery"),
            defaultMetricOrder(metrics).map { it.entityId })
    }

    @Test fun staticCollectionIsLimitedToLegacyAndroidAndHasStableIdentity() {
        (26..36).forEach { assertEquals(it <= 30, StableCollectionPolicy.use(it)) }
        assertEquals(StableCollectionPolicy.identity(42), StableCollectionPolicy.identity(42))
        assertNotEquals(StableCollectionPolicy.identity(42), StableCollectionPolicy.identity(43))
    }
}
