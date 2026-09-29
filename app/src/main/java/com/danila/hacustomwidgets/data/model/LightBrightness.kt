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
) {
    val capable: Boolean get() = if (modesPresent) modes?.any { it in CAPABLE_MODES } == true
        else supportedFeatures?.let { it and 1 != 0 } == true

    fun displayPercent(state: String, lastConfirmed: Int?): Int? =
        percent(value ?: lastConfirmed?.takeIf { state == "off" })

    fun toAttributes(): JSONObject = JSONObject().apply {
        put("brightness", value)
        if (modesPresent) put("supported_color_modes", modes?.let(::JSONArray) ?: JSONObject.NULL)
        put("color_mode", colorMode)
        put("supported_features", supportedFeatures)
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
            )
        }
        private fun integer(json: JSONObject, key: String): Int? = (json.opt(key) as? Number)
            ?.toDouble()?.takeIf { it.isFinite() && it == it.toInt().toDouble() }?.toInt()
        fun percent(raw: Int?): Int? = raw?.takeIf { it in 0..255 }
            ?.let { (it * 100.0 / 255).roundToInt().coerceIn(1, 100) }
        fun step(base: Int, direction: Int): Int = (base + direction.coerceIn(-1, 1) * 5).coerceIn(1, 100)
    }
}
