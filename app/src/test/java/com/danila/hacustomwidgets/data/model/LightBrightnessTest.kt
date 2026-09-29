package com.danila.hacustomwidgets.data.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LightBrightnessTest {
    @Test fun modernCapabilityHasPriority() {
        LightBrightness.CAPABLE_MODES.forEach { mode ->
            assertTrue(LightBrightness.parse(JSONObject("""{"supported_color_modes":["$mode"]}""")).capable)
        }
        listOf("[]", "null", "7", "[7]", "[\"onoff\"]", "[\"future\"]").forEach { modes ->
            assertFalse(LightBrightness.parse(JSONObject("""{"supported_color_modes":$modes,"supported_features":1,"brightness":255}""")).capable)
        }
        assertTrue(LightBrightness.parse(JSONObject("""{"supported_features":1}""")).capable)
        assertFalse(LightBrightness.parse(JSONObject("""{"brightness":255}""")).capable)
    }
    @Test fun conversionAndSteps() {
        assertNull(LightBrightness.percent(null))
        assertNull(LightBrightness.percent(-1))
        assertNull(LightBrightness.percent(256))
        assertEquals(1, LightBrightness.percent(0))
        assertEquals(1, LightBrightness.percent(1))
        assertEquals(5, LightBrightness.percent(13))
        assertEquals(65, LightBrightness.percent(166))
        assertEquals(95, LightBrightness.percent(242))
        assertEquals(100, LightBrightness.percent(255))
        assertEquals(70, LightBrightness.step(65, 1))
        assertEquals(60, LightBrightness.step(65, -1))
        assertEquals(1, LightBrightness.step(5, -1))
        assertEquals(1, LightBrightness.step(1, -1))
        assertEquals(100, LightBrightness.step(95, 1))
        assertEquals(95, LightBrightness.step(100, -1))
        assertEquals(100, LightBrightness.step(100, 1))
    }
    @Test fun persistenceKeepsInvalidModernPresence() {
        val value = LightBrightness.parse(JSONObject("""{"supported_color_modes":null,"supported_features":1}"""))
        assertEquals(value, LightBrightness.parse(value.toAttributes()))
        assertFalse(LightBrightness.parse(value.toAttributes()).capable)
    }
}
