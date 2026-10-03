package com.danila.hacustomwidgets.data.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LightColorTest {
    private fun light(modes: String, mode: String = "onoff") = LightBrightness.parse(
        JSONObject("""{"supported_color_modes":$modes,"color_mode":"$mode"}"""))

    @Test fun capabilitiesFollowSupportedModes() {
        assertFalse(light("[\"brightness\"]").temperatureCapable)
        assertFalse(light("[\"brightness\"]").colorCapable)
        assertTrue(light("[\"color_temp\"]").temperatureCapable)
        LightColor.MODES.forEach {
            assertTrue(light("[\"$it\"]").colorCapable)
            val both = light("[\"color_temp\",\"$it\"]")
            assertTrue(both.temperatureCapable && both.colorCapable && both.capable)
        }
    }
    @Test fun activeModeDoesNotChangeCapabilities() {
        listOf("color_temp", "hs", "onoff", "unknown").forEach {
            val value = light("[\"color_temp\",\"hs\"]", it)
            assertTrue(value.temperatureCapable && value.colorCapable)
        }
    }
    @Test fun invalidModesDoNotInferFromValues() {
        listOf("null", "[]", "17", "[null]", "[\"future\"]", "[\"hs\",7]").forEach {
            val value = light(it)
            assertFalse(value.temperatureCapable || value.colorCapable)
        }
        val legacy = LightBrightness.parse(JSONObject("""{"supported_features":1,"color_temp_kelvin":4000,"hs_color":[42,75]}"""))
        assertTrue(legacy.capable)
        assertFalse(legacy.temperatureCapable || legacy.colorCapable)
    }
    @Test fun rangeAndPresetsUseReachableKelvin() {
        assertEquals(listOf(2700,4000,6500), KelvinRange(2000,7000).presets())
        assertEquals(listOf(3500,4000,4500), KelvinRange(3500,4500).presets())
        assertEquals(listOf(4000,4000,4000), KelvinRange(4000,4000).presets())
        assertEquals(4500, KelvinRange(3500,4500).clamp(9000))
    }
    @Test fun invalidRangeIsUnavailable() {
        listOf("{}", """{"min_color_temp_kelvin":null,"max_color_temp_kelvin":6500}""",
            """{"min_color_temp_kelvin":6500,"max_color_temp_kelvin":2700}""",
            """{"min_color_temp_kelvin":0,"max_color_temp_kelvin":6500}""",
            """{"min_color_temp_kelvin":2700.5,"max_color_temp_kelvin":6500}""").forEach {
            assertNull(KelvinRange.parse(JSONObject(it)))
        }
    }
    @Test fun canonicalHsDoesNotIncludeBrightness() {
        val value = LightColor(120.0,75.0)
        val data = value.serviceData()
        assertEquals(setOf("hs_color"), data.keys)
        assertEquals(value, LightColor.parse(JSONObject(data)))
    }
    @Test fun malformedColorIsUnknown() {
        listOf("null", "[]", "[1]", "[1,2,3]", "[-1,50]", "[361,50]", "[1,101]", "[\"1\",50]").forEach {
            assertNull(LightColor.parse(JSONObject("""{"hs_color":$it}""")))
        }
    }
    @Test fun attributesRoundTripKelvinAndColor() {
        val value = LightBrightness.parse(JSONObject("""{"supported_color_modes":["color_temp","rgbww"],"color_mode":"rgbww","brightness":166,"color_temp_kelvin":4000,"min_color_temp_kelvin":2700,"max_color_temp_kelvin":6500,"hs_color":[280,72]}"""))
        assertEquals(value, LightBrightness.parse(value.toAttributes()))
    }
}
