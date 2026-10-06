package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.remote.*
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MaintenanceTransportTest {
    private class Socket : WebSocket {
        val commands = mutableListOf<String>()
        override fun request() = Request.Builder().url("https://fixture.invalid").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { commands += text; return true }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }
    @Test fun registrySubscriptionsUseExistingSocketAndDistinctIds() {
        val socket = Socket(); HomeAssistantClient().subscribeMaintenanceEvents(socket, 101)
        val commands = socket.commands.map(::JSONObject)
        assertEquals(listOf(101,102,103,104), commands.map { it.getInt("id") })
        assertEquals(setOf("repairs_issue_registry_updated", "entity_registry_updated", "device_registry_updated", "area_registry_updated"), commands.map { it.getString("event_type") }.toSet())
        assertTrue(commands.all { it.getString("type") == "subscribe_events" })
    }
    @Test fun compressedBatteryDeltasPreserveSemanticsAndUpdateBoundary() {
        val parser = CompressedEntitySubscriptionParser()
        val initial = parser.apply(JSONObject("""{"a":{"sensor.charge":{"s":"6","lu":1,"a":{"friendly_name":"Charge","device_class":"battery","unit_of_measurement":"%"}}}}"""))
        assertFalse(MaintenancePolicy.batteryAttention(initial.entities.single()))
        val changed = parser.apply(JSONObject("""{"c":{"sensor.charge":{"+":{"s":"5","lu":2}}}}"""))
        assertTrue(MaintenancePolicy.batteryAttention(changed.entities.single()))
        assertEquals("%", changed.entities.single().unit)
        val unknown = parser.apply(JSONObject("""{"c":{"sensor.charge":{"+":{"s":"unavailable","lu":3}}}}"""))
        assertFalse(MaintenancePolicy.batteryAttention(unknown.entities.single()))
    }
    @Test fun compressedUpdateOnOffAndRemovalDoNotLeaveAlert() {
        val parser = CompressedEntitySubscriptionParser()
        assertTrue(MaintenancePolicy.updateAttention(parser.apply(JSONObject("""{"a":{"update.a":{"s":"on","lu":1,"a":{"friendly_name":"Firmware"}}}}""")).entities.single()))
        assertFalse(MaintenancePolicy.updateAttention(parser.apply(JSONObject("""{"c":{"update.a":{"+":{"s":"off","lu":2}}}}""")).entities.single()))
        assertFalse(MaintenancePolicy.updateAttention(parser.apply(JSONObject("""{"r":["update.a"]}""")).entities.single()))
    }
}
