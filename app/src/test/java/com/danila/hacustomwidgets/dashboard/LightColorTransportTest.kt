package com.danila.hacustomwidgets.dashboard

import com.danila.hacustomwidgets.data.remote.CompressedEntitySubscriptionParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LightColorTransportTest {
    private fun parser() = CompressedEntitySubscriptionParser().also {
        it.apply(JSONObject("""{"a":{"light.a":{"s":"on","lu":10,"a":{"supported_color_modes":["color_temp","rgbww"],"color_mode":"color_temp","brightness":166,"min_color_temp_kelvin":2700,"max_color_temp_kelvin":6500,"color_temp_kelvin":4000,"hs_color":[120,75],"rgbww_color":[1,2,3,4,5]}}}}"""))
    }
    @Test fun temperatureOnlyDeltaUpdatesAndPreservesBrightness() {
        val value = parser().apply(JSONObject("""{"c":{"light.a":{"+":{"lu":11,"a":{"color_temp_kelvin":5000}}}}}""")).entities.single()
        assertEquals(5000, value.brightness.temperatureKelvin)
        assertEquals(166, value.brightness.value)
        assertTrue(value.brightness.temperatureCapable && value.brightness.colorCapable)
    }
    @Test fun modeSwitchKeepsCapabilitiesAndNativeChannels() {
        val value = parser().apply(JSONObject("""{"c":{"light.a":{"+":{"lu":11,"a":{"color_mode":"rgbww","hs_color":[240,60]}}}}}""")).entities.single()
        assertEquals(240.0, value.brightness.color!!.hue, 0.0)
        assertEquals(listOf(1.0,2.0,3.0,4.0,5.0), value.brightness.rgbwwColor)
        assertTrue(value.brightness.temperatureCapable && value.brightness.colorCapable)
    }
    @Test fun nullAndRemovalClearActualValues() {
        val p = parser()
        val nulled = p.apply(JSONObject("""{"c":{"light.a":{"+":{"a":{"hs_color":null}}}}}""")).entities.single()
        assertNull(nulled.brightness.color)
        val removed = p.apply(JSONObject("""{"c":{"light.a":{"-":{"a":["color_temp_kelvin","rgbww_color"]}}}}""")).entities.single()
        assertNull(removed.brightness.temperatureKelvin)
        assertNull(removed.brightness.rgbwwColor)
    }
    @Test fun removingRangeBoundDoesNotForgetOtherBound() {
        val p = parser()
        val removed = p.apply(JSONObject("""{"c":{"light.a":{"-":{"a":["min_color_temp_kelvin"]}}}}""")).entities.single()
        assertNull(removed.brightness.kelvinRange)
        val restored = p.apply(JSONObject("""{"c":{"light.a":{"+":{"a":{"min_color_temp_kelvin":3000}}}}}""")).entities.single()
        assertEquals(6500, restored.brightness.kelvinRange!!.maximum)
        assertEquals(3000, restored.brightness.kelvinRange!!.minimum)
    }
    @Test fun entityRemovalClearsCapabilities() {
        val value = parser().apply(JSONObject("""{"r":["light.a"]}""")).entities.single()
        assertEquals("unavailable", value.state)
        assertFalse(value.brightness.capable || value.brightness.colorCapable || value.brightness.temperatureCapable)
    }
}
