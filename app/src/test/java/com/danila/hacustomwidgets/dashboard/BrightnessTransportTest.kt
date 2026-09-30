package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.model.LightBrightness
import com.danila.hacustomwidgets.data.remote.CompressedEntitySubscriptionParser
import com.danila.hacustomwidgets.data.remote.HomeAssistantClient
import com.danila.hacustomwidgets.data.security.HomeAssistantConnection
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrightnessTransportTest {
    @Test fun fullStateChangedAttributesAndEntityRemoval() {
        val client = HomeAssistantClient()
        val event = JSONObject("""{"event_type":"state_changed","data":{"entity_id":"light.a","new_state":{"entity_id":"light.a","state":"on","attributes":{"brightness":242,"supported_color_modes":["white"],"color_mode":"white"}}}}""")
        val entity = client.stateChangedEntity(event)!!
        assertTrue(entity.brightness.capable)
        assertEquals(95, LightBrightness.percent(entity.brightness.value))
        assertEquals("white", entity.brightness.colorMode)
        event.getJSONObject("data").getJSONObject("new_state").getJSONObject("attributes").put("brightness", JSONObject.NULL)
        assertNull(client.stateChangedEntity(event)!!.brightness.value)
        event.getJSONObject("data").put("new_state", JSONObject.NULL)
        assertEquals("unavailable", client.stateChangedEntity(event)!!.state)
    }
    @Test fun sliderSendsOnlyLastFinishedValueAndNeverUnknownBase() {
        val selection = BrightnessSelection()
        assertNull(selection.finish(true))
        (1..100).forEach { selection.change(it.toFloat()) }
        assertEquals(100, selection.finish(true))
        assertNull(selection.finish(true))
        selection.change(75f)
        assertNull(selection.finish(false))
        assertNull(selection.finish(true))
        val missing = LightBrightness(null, true, listOf("brightness"))
        assertNull(missing.displayPercent("on", 166))
        assertNull(missing.displayPercent("off", null))
        assertEquals(65, missing.displayPercent("off", 166))
    }
    @Test fun restStateAndServicePayload() = runBlocking {
        val requests = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = okio.Buffer(); request.body?.writeTo(buffer)
            requests += buffer.readUtf8()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"entity_id":"light.a","state":"off","attributes":{"brightness":166,"supported_color_modes":["color_temp"],"color_mode":"color_temp"}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = HomeAssistantClient(http)
        val connection = HomeAssistantConnection("https://test.invalid", "test")
        val entity = client.getEntity(connection, "light.a")
        assertTrue(entity.brightness.capable); assertEquals(65, LightBrightness.percent(entity.brightness.value))
        client.callService(connection, "light", "turn_on", "light.a", mapOf("brightness_pct" to 70))
        val payload = JSONObject(requests.last())
        assertEquals(70, payload.getInt("brightness_pct")); assertEquals("light.a", payload.getString("entity_id"))
        assertFalse(payload.has("brightness_step_pct")); assertFalse(payload.has("brightness"))
    }
    @Test fun compactInitialDeltaRemovalAndExplicitNull() {
        val parser = CompressedEntitySubscriptionParser()
        fun event(value: String) = parser.apply(JSONObject(value)).entities.single()
        val initial = event("""{"a":{"light.a":{"s":"on","lu":10,"a":{"brightness":166,"supported_color_modes":["rgb"],"color_mode":"rgb","supported_features":1}}}}""")
        assertTrue(initial.brightness.capable)
        val delta = event("""{"c":{"light.a":{"+":{"lu":11,"a":{"brightness":179}}}}}""")
        assertEquals("on", delta.state); assertEquals(70, LightBrightness.percent(delta.brightness.value))
        assertEquals(listOf("rgb"), delta.brightness.modes)
        val removed = event("""{"c":{"light.a":{"-":{"a":["brightness","color_mode"]}}}}""")
        assertNull(removed.brightness.value); assertNull(removed.brightness.colorMode); assertTrue(removed.brightness.capable)
        val invalid = event("""{"c":{"light.a":{"+":{"a":{"supported_color_modes":null}}}}}""")
        assertFalse(invalid.brightness.capable)
        val legacy = event("""{"c":{"light.a":{"-":{"a":["supported_color_modes"]}}}}""")
        assertTrue(legacy.brightness.capable)
        val deleted = event("""{"r":["light.a"]}""")
        assertEquals("unavailable", deleted.state); assertNull(deleted.brightness.value)
    }
    @Test fun brightnessOnlyDiffAndStaleTimestamps() {
        val old = VersionedEntityState("light.a", "on", "on", 2000, 1, brightness = LightBrightness(166, true, listOf("brightness")))
        val incoming = com.danila.hacustomwidgets.data.model.HaEntity("light.a", "on", "A", null, null, brightness = old.brightness.copy(value = 179))
        assertFalse(DashboardRefreshPolicy.samePayload(old, incoming))
        assertTrue(DashboardRefreshPolicy.samePayload(old, incoming.copy(brightness = old.brightness)))
        assertFalse(DashboardStatePolicy.decide(old, "on", 1000, null).accept)
        assertTrue(DashboardStatePolicy.decide(old, "on", 3000, null).accept)
    }
    @Test fun adaptiveLayoutUsesRealAvailableWidth() {
        assertFalse(BrightnessLayoutPolicy.showSteps(180, 1f))
        assertFalse(BrightnessLayoutPolicy.showSteps(250, 1f))
        assertTrue(BrightnessLayoutPolicy.showSteps(320, 1f))
        assertFalse(BrightnessLayoutPolicy.showSteps(320, 1.5f))
    }
}
