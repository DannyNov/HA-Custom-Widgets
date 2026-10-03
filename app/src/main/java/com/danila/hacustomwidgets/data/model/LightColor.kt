package com.danila.hacustomwidgets.data.model

import org.json.JSONObject
import org.json.JSONArray

/** Chromaticity only. Brightness remains owned by the existing brightness control. */
data class LightColor(val hue: Double, val saturation: Double) {
    init {
        require(hue.isFinite() && hue in 0.0..360.0)
        require(saturation.isFinite() && saturation in 0.0..100.0)
    }

    fun serviceData(): Map<String, Any> = mapOf("hs_color" to JSONArray(listOf(hue, saturation)))

    companion object {
        val MODES = setOf("hs", "xy", "rgb", "rgbw", "rgbww")

        fun parse(attributes: JSONObject): LightColor? {
            // HA exposes converted HS for every supported chromatic mode, including
            // RGBW/RGBWW. White-channel conversion is HA's documented approximation.
            val pair = attributes.optJSONArray("hs_color") ?: return null
            if (pair.length() != 2) return null
            val hue = (pair.opt(0) as? Number)?.toDouble() ?: return null
            val saturation = (pair.opt(1) as? Number)?.toDouble() ?: return null
            return runCatching { LightColor(hue, saturation) }.getOrNull()
        }
    }
}

data class KelvinRange(val minimum: Int, val maximum: Int) {
    init { require(minimum > 0 && maximum >= minimum) }
    fun clamp(value: Int): Int = value.coerceIn(minimum, maximum)
    /** Canonical labels stay distinct even for narrow ranges or a fixed temperature. */
    fun presets(): List<Int> = listOf(clamp(2700), clamp(4000), clamp(6500))

    companion object {
        fun parse(attributes: JSONObject): KelvinRange? {
            val minimum = integer(attributes, "min_color_temp_kelvin") ?: return null
            val maximum = integer(attributes, "max_color_temp_kelvin") ?: return null
            return runCatching { KelvinRange(minimum, maximum) }.getOrNull()
        }
        fun integer(attributes: JSONObject, key: String): Int? {
            val value = (attributes.opt(key) as? Number)?.toDouble() ?: return null
            return value.takeIf { it.isFinite() && it > 0 && it <= Int.MAX_VALUE && it == it.toInt().toDouble() }?.toInt()
        }
    }
}
