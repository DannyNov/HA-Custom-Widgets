package com.danila.hacustomwidgets.data.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Modern capability presence is distinct from validity: explicit null never enables legacy fallback. */
data class LightBrightness(
    val value: Int? = null,
    val modesPresent: Boolean = false,
    val modes: List<String>? = null,
    val colorMode: String? = null,
    val supportedFeatures: Int? = null,
    val temperatureKelvin: Int? = null,
    val kelvinRange: KelvinRange? = null,
    val color: LightColor? = null,
    val minimumKelvin: Int? = kelvinRange?.minimum,
    val maximumKelvin: Int? = kelvinRange?.maximum,
    val xyColor: List<Double>? = null,
    val rgbColor: List<Double>? = null,
    val rgbwColor: List<Double>? = null,
    val rgbwwColor: List<Double>? = null,
) {
    val temperatureCapable: Boolean get() = modesPresent && modes?.contains("color_temp") == true
    val colorCapable: Boolean get() = modesPresent && modes?.any { it in LightColor.MODES } == true
    val capable: Boolean get() = if (modesPresent) modes?.any { it in CAPABLE_MODES } == true
        else supportedFeatures?.let { it and 1 != 0 } == true

    fun displayPercent(state: String, lastConfirmed: Int?): Int? =
        percent(value ?: lastConfirmed?.takeIf { state == "off" })

    fun toAttributes(): JSONObject = JSONObject().apply {
        put("brightness", value)
        if (modesPresent) put("supported_color_modes", modes?.let(::JSONArray) ?: JSONObject.NULL)
        put("color_mode", colorMode)
        put("supported_features", supportedFeatures)
        put("color_temp_kelvin", temperatureKelvin)
        put("min_color_temp_kelvin", minimumKelvin)
        put("max_color_temp_kelvin", maximumKelvin)
        put("hs_color", color?.let { JSONArray(listOf(it.hue, it.saturation)) })
        put("xy_color", xyColor?.let(::JSONArray))
        put("rgb_color", rgbColor?.let(::JSONArray))
        put("rgbw_color", rgbwColor?.let(::JSONArray))
        put("rgbww_color", rgbwwColor?.let(::JSONArray))
    }

    companion object {
        val CAPABLE_MODES = setOf("brightness", "color_temp", "hs", "xy", "rgb", "rgbw", "rgbww", "white")
        fun parse(attributes: JSONObject): LightBrightness {
            val array = attributes.optJSONArray("supported_color_modes")
            val modes = array?.let { a ->
                (0 until a.length()).map { a.opt(it) }.takeIf { list -> list.all { it is String } }
                    ?.map { it as String }
            }
            return LightBrightness(
                value = integer(attributes, "brightness")?.takeIf { it in 0..255 },
                modesPresent = attributes.has("supported_color_modes"), modes = modes,
                colorMode = (attributes.opt("color_mode") as? String),
                supportedFeatures = integer(attributes, "supported_features"),
                temperatureKelvin = KelvinRange.integer(attributes, "color_temp_kelvin"),
                kelvinRange = KelvinRange.parse(attributes),
                color = LightColor.parse(attributes),
                minimumKelvin = KelvinRange.integer(attributes, "min_color_temp_kelvin"),
                maximumKelvin = KelvinRange.integer(attributes, "max_color_temp_kelvin"),
                xyColor = components(attributes, "xy_color", 2, 1.0),
                rgbColor = components(attributes, "rgb_color", 3, 255.0),
                rgbwColor = components(attributes, "rgbw_color", 4, 255.0),
                rgbwwColor = components(attributes, "rgbww_color", 5, 255.0),
            )
        }
        private fun integer(json: JSONObject, key: String): Int? = (json.opt(key) as? Number)
            ?.toDouble()?.takeIf { it.isFinite() && it == it.toInt().toDouble() }?.toInt()
        private fun components(json: JSONObject, key: String, size: Int, maximum: Double): List<Double>? {
            val array = json.optJSONArray(key) ?: return null
            if (array.length() != size) return null
            return (0 until size).map {
                val value = (array.opt(it) as? Number)?.toDouble() ?: return null
                if (!value.isFinite() || value !in 0.0..maximum ||
                    (maximum == 255.0 && value != value.toInt().toDouble())) return null
                value
            }
        }
        fun percent(raw: Int?): Int? = raw?.takeIf { it in 0..255 }
            ?.let { (it * 100.0 / 255).roundToInt().coerceIn(1, 100) }
        fun step(base: Int, direction: Int): Int = (base + direction.coerceIn(-1, 1) * 5).coerceIn(1, 100)
    }
}
