package com.blueshield.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** User-facing options. Field names/defaults match the desktop app and shared/pipeline.json. */
@Serializable
data class CensorSettings(
    val color: String = "#FFFFFF",
    val sensitivity: Int = 60,
    val softness: Int = 20,
    val aggressive: Boolean = false,
    val animated: Boolean = false,
    @SerialName("include_face") val includeFace: Boolean = false,
    val speed: String = "quality",
    val quality: String = "balanced",
    @SerialName("keep_audio") val keepAudio: Boolean = true,
    /** "female" = only people classified as women, "everyone" = every detected person. */
    val target: String = "female",
    /** Confidence (51–99 %) required to call someone female or male. */
    @SerialName("gender_threshold") val genderThreshold: Int = 70,
    /** "censor" (safe default) or "keep": what happens to people the classifier is unsure about. */
    @SerialName("uncertain_policy") val uncertainPolicy: String = "censor",
    /** "color" = a solid colour; "clothing" = the garment next to the skin continued over it (a sleeve, a collar). */
    val fill: String = "color",
) {
    fun validated(): CensorSettings = copy(
        sensitivity = sensitivity.coerceIn(0, 100),
        softness = softness.coerceIn(0, 100),
        genderThreshold = genderThreshold.coerceIn(51, 99),
        speed = if (speed in setOf("quality", "balanced", "fast")) speed else "quality",
        quality = if (quality in setOf("high", "balanced", "small")) quality else "balanced",
        target = if (target in setOf("female", "everyone")) target else "female",
        uncertainPolicy = if (uncertainPolicy in setOf("censor", "keep")) uncertainPolicy else "censor",
        fill = if (fill in setOf("color", "clothing")) fill else "color",
    ).also { parseColor(it.color) }

    val sensitivity01: Float get() = sensitivity / 100f
    val threshold01: Double get() = genderThreshold / 100.0
    /** Skin that can't be attributed to any detected person is censored only when we'd censor an unknown person. */
    val censorUnassigned: Boolean get() = target == "everyone" || uncertainPolicy == "censor"

    companion object {
        /** "#RRGGBB" / "RRGGBB" / "#RGB" -> 0xRRGGBB. */
        fun parseColor(color: String): Int {
            var c = color.trim().removePrefix("#")
            if (c.length == 3) c = c.map { "$it$it" }.joinToString("")
            require(c.length == 6 && c.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Invalid color: $color" }
            return c.toInt(16)
        }
    }
}
